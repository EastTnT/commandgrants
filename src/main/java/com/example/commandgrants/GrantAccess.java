package com.example.commandgrants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Turns a player's grant table into the two things the game actually enforces: the command tree their
 * client is given, and the command tree the server parses their input against.
 *
 * <p>Both are needed because vanilla restricts them separately.
 *
 * <ul>
 *   <li><b>What the client may see and type.</b> {@code Commands.sendCommands} builds a tree with
 *       {@code child.canUse(source)} and ships it as {@code ClientboundCommandsPacket}; the client
 *       rebuilds its dispatcher from that packet, so a command left out neither completes nor parses
 *       there. {@link #filteredRoot} produces that trimmed tree.
 *   <li><b>What the server will accept.</b> The server parses against the <em>full</em> dispatcher, so
 *       the tree sent to the client is a filter, not a gate — and because widening a permission to
 *       admit one command admits every other command at the same level, the level alone cannot express
 *       the host's intent. {@link #narrowDispatcher} closes that gap, and since
 *       {@code handleCustomCommandSuggestions} parses through the same funnel, server suggestions agree
 *       with the tree the client was given.
 * </ul>
 *
 * <p>Both work at root-literal granularity, because that is the only granularity that can express the
 * host's intent: {@code /tp} and {@code /gamemode} share {@code Permissions.COMMANDS_GAMEMASTER}, so no
 * permission can separate them. {@link GrantedPermissionSet} still widens by level, which is what makes
 * the <em>sub</em>commands of an opened command work ({@code /time set}, {@code /gamemode creative});
 * the root literal decides whether the player gets in at all.
 *
 * <p>Results are cached per player and per grant set. The key includes the literals themselves, so an
 * edit produces a different key and the stale entry is simply never asked for again — no invalidation
 * hook to forget. Entries for revoked grants age out of the cache, which is bounded by the number of
 * distinct grant sets a server ever sees.
 */
public final class GrantAccess {
    private final Map<Key, RootCommandNode<CommandSourceStack>> trimmedRoots = new ConcurrentHashMap<>();
    private final Map<Key, CommandDispatcher<CommandSourceStack>> narrowDispatchers = new ConcurrentHashMap<>();

    /**
     * A player, what vanilla already lets them reach, and the exact command set that was open when
     * something was cached for them.
     *
     * <p>{@code baseAllowed} is part of the key because it decides the result just as much as the
     * grants do: an op whose permissions change must not keep a tree built for their old level.
     */
    private record Key(UUID playerId, List<String> baseAllowed, List<String> granted) {
    }

    private static Key keyOf(ServerPlayer player, PermissionSet base, GrantTable grants,
                             CommandIndex index) {
        List<String> baseAllowed = new ArrayList<>();
        if (base != null && index != null && !index.isEmpty()) {
            for (String literal : index.literals()) {
                CommandIndex.Entry entry = index.entry(literal);
                if (entry != null && base.hasPermission(new Permission.HasCommandLevel(entry.required()))) {
                    baseAllowed.add(literal);
                }
            }
        }
        baseAllowed.sort(String::compareTo);

        List<String> granted = new ArrayList<>(grants.literals().keySet());
        granted.sort(String::compareTo);

        return new Key(player.getUUID(), List.copyOf(baseAllowed), List.copyOf(granted));
    }

    private static PermissionLevel levelOf(CommandNode<CommandSourceStack> node, CommandIndex index) {
        if (index != null) {
            CommandIndex.Entry entry = index.entry(node.getName());
            if (entry != null) {
                return entry.required();
            }
        }
        return requiredLevel(node);
    }

    /**
     * Whether {@code literal} survives for a player whose vanilla permissions are {@code base} and who
     * holds {@code grants}.
     *
     * <p>The tree vanilla builds is already trimmed by {@code child.canUse}, so a level-gated literal
     * only reaches this point when the player holds that level — through the grant, which is what
     * {@link GrantedPermissionSet} widened. Sending it on is therefore always the right answer, and
     * refusing it here is what would cut a grant short.
     *
     * <p>What does <em>not</em> reach this point is a literal the host opened for someone else, nor one
     * whose requirement is not a plain permission level (a mod's custom predicate). Those are left
     * exactly as vanilla decided: this only ever refines a tree that was already permission-checked,
     * and never widens one.
     */
    private static boolean reachable(CommandNode<CommandSourceStack> node, PermissionSet base,
                                     GrantTable grants, CommandIndex index) {
        if (!(node instanceof LiteralCommandNode<CommandSourceStack>)) {
            // A root that is not a literal is not something a host can hand out by name, and dropping
            // it would silently change what vanilla offers.
            return true;
        }
        // Told apart from a vanilla level gate by asking where the level came from: only this game's
        // own checks carry one, and VanillaLevel is what reads it back.
        if (requiredLevel(node) == PermissionLevel.ALL) {
            return true;
        }
        PermissionLevel required = levelOf(node, index);
        if (base != null && base.hasPermission(new Permission.HasCommandLevel(required))) {
            return true;
        }
        if (grants == null) {
            return false;
        }
        // `/tp` and `/gamemode` share COMMANDS_GAMEMASTER, so a level cannot express the host's choice;
        // the literal is the whole decision. The literal is not the whole *name* though: a command
        // that redirects at another root is one command under two names, and opening either has to
        // open it — a host who ticked "teleport" and then typed `/tp` is asking for the same command.
        String literal = node.getName();
        if (grants.allowsLiteral(literal)) {
            return true;
        }
        for (String name : index.group(literal)) {
            if (grants.allowsLiteral(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ client-facing tree

    /**
     * The tree to send a player: vanilla's already permission-trimmed root, minus the restricted
     * literals that were opened for somebody else.
     *
     * <p>Only root literals are re-decided. Deeper nodes keep vanilla's decision, because the host is
     * shown one checkbox per root command and entry to an unopened root is refused anyway.
     *
     * @return {@code root} unchanged when this player has no grants
     */
    public RootCommandNode<CommandSourceStack> filteredRoot(ServerPlayer player,
                                                            PermissionSet base,
                                                            RootCommandNode<CommandSourceStack> root,
                                                            GrantTable grants,
                                                            CommandIndex index) {
        if (root == null || grants == null || grants.isEmpty()) {
            return root;
        }
        return trimmedRoots.computeIfAbsent(keyOf(player, base, grants, index),
                ignored -> trim(root, base, grants, index));
    }

    private static RootCommandNode<CommandSourceStack> trim(RootCommandNode<CommandSourceStack> root,
                                                            PermissionSet base, GrantTable grants,
                                                            CommandIndex index) {
        RootCommandNode<CommandSourceStack> trimmed = new RootCommandNode<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            if (reachable(child, base, grants, index)) {
                trimmed.addChild(child);
            }
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ server-side gate

    /**
     * The dispatcher to parse this player's input against: vanilla's, with the unopened root commands
     * removed.
     *
     * <p>The kept nodes are vanilla's own objects, so nothing about the commands themselves is altered
     * — only which of them the dispatcher can reach from the top. A live tree is never mutated: mutating
     * shared nodes would need restoring before anything else ran, and would be visible to every thread
     * in the meantime.
     *
     * @return {@code dispatcher} unchanged when this player has no grants
     */
    public CommandDispatcher<CommandSourceStack> narrowDispatcher(ServerPlayer player,
                                                                  PermissionSet base,
                                                                  CommandDispatcher<CommandSourceStack> dispatcher,
                                                                  GrantTable grants,
                                                                  CommandIndex index) {
        if (dispatcher == null || dispatcher.getRoot() == null || grants == null || grants.isEmpty()) {
            return dispatcher;
        }
        return narrowDispatchers.computeIfAbsent(keyOf(player, base, grants, index),
                ignored -> new CommandDispatcher<>(mirror(dispatcher.getRoot(), base, grants, index)));
    }

    /**
     * A copy of a root node keeping only the literals the player may enter, reusing the original child
     * nodes.
     */
    private static RootCommandNode<CommandSourceStack> mirror(RootCommandNode<CommandSourceStack> root,
                                                              PermissionSet base, GrantTable grants,
                                                              CommandIndex index) {
        RootCommandNode<CommandSourceStack> mirrored = new RootCommandNode<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            if (reachable(child, base, grants, index)) {
                mirrored.addChild(child);
            }
        }
        return mirrored;
    }

    /**
     * The level a root command demands, read back from the gate vanilla put on it, or
     * {@link PermissionLevel#ALL} when there is no level to read.
     *
     * <p>Vanilla installs {@code Commands.LEVEL_*} on its commands by way of
     * {@code Commands.hasPermission}, which wraps the constant — see {@link VanillaLevel}, which is
     * what unwraps it. Anything still unrecognised (a mod's own predicate) is reported as
     * {@code ALL}, so {@link #reachable} leaves it exactly as vanilla built it rather than guessing.
     */
    private static PermissionLevel requiredLevel(CommandNode<CommandSourceStack> node) {
        PermissionLevel level = VanillaLevel.of(node.getRequirement());
        return level == null ? PermissionLevel.ALL : level;
    }
}
