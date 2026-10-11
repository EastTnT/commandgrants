package com.example.commandgrants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * What the live command tree demands for every <em>root literal</em>.
 *
 * <p>A root literal is the granularity the host reasons about ({@code /tp} is one checkbox) and the
 * granularity {@code Commands.fillUsableCommands} trims at, so it is the granularity this records.
 *
 * <p>Vanilla expresses "this command needs level N" by installing one of four shared
 * {@code PermissionCheck} singletons ({@code Commands.LEVEL_GAMEMASTERS} and friends) as the node's
 * requirement — by way of {@code Commands.hasPermission}, which wraps the constant, so the level is
 * read back through {@link VanillaLevel} rather than by comparing the requirement itself. Anything
 * left over is probed instead: the node is asked whether synthetic sources at each level would pass,
 * so a modded command with a hand-rolled requirement is answered by what the game actually enforces
 * rather than by a guess.
 */
public final class CommandRequirements {
    private static final CommandRequirements EMPTY =
            new CommandRequirements(Map.of(), new LinkedHashSet<>(), Map.of());

    private final Map<String, PermissionLevel> byLiteral;
    private final Set<String> knownLiterals;
    private final Map<String, Set<String>> aliasGroups;

    private CommandRequirements(Map<String, PermissionLevel> byLiteral, Set<String> knownLiterals,
                                Map<String, Set<String>> aliasGroups) {
        this.byLiteral = Map.copyOf(byLiteral);
        this.knownLiterals = Set.copyOf(knownLiterals);
        this.aliasGroups = Map.copyOf(aliasGroups);
    }

    public static CommandRequirements empty() {
        return EMPTY;
    }

    /** Root literals, alphabetically. */
    public List<String> literals() {
        List<String> names = new ArrayList<>(knownLiterals);
        names.sort(java.util.Comparator.naturalOrder());
        return names;
    }

    public boolean contains(String literal) {
        return literal != null && knownLiterals.contains(literal);
    }

    /** Highest level vanilla demands for {@code literal}, or {@code null} when it is unknown. */
    public PermissionLevel levelOf(String literal) {
        return byLiteral.get(literal);
    }

    /** The literal -> required-level table, used to expand presets. */
    public Map<String, PermissionLevel> levels() {
        return byLiteral;
    }

    /**
     * Names that are the same command as {@code literal}, including itself, or {@code null} when it
     * stands alone.
     *
     * <p>Vanilla registers {@code /tp} as a second root literal redirecting at {@code /teleport}, so
     * they are two nodes (two checkboxes, two catalogue entries) that run the same code. Nothing in
     * Brigadier or in the permission system connects them — the redirect is the only link — which is
     * why {@link #collectAliases} has to go looking for it.
     */
    public Set<String> aliasGroup(String literal) {
        return literal == null ? null : aliasGroups.get(literal);
    }

    /** The literal -> group table, used to label a catalogue entry with the names it answers to. */
    public Map<String, Set<String>> aliasGroups() {
        return aliasGroups;
    }

    public static CommandRequirements discover(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == null || dispatcher.getRoot() == null) {
            return EMPTY;
        }
        Map<String, PermissionLevel> levels = new LinkedHashMap<>();
        Set<String> known = new LinkedHashSet<>();
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        Map<String, String> redirects = new LinkedHashMap<>();
        List<LiteralCommandNode<CommandSourceStack>> roots = new ArrayList<>();
        for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
            if (!(node instanceof LiteralCommandNode<CommandSourceStack> literal)) {
                // A namespace-style root argument is not something a host can hand out by name.
                continue;
            }
            known.add(literal.getName());
            roots.add(literal);
        }
        for (LiteralCommandNode<CommandSourceStack> node : roots) {
            String literal = node.getName();
            levels.put(literal, overall(node, null));
            if (node.getRedirect() != null) {
                redirects.put(literal, node.getRedirect().getName());
            }
        }
        for (LiteralCommandNode<CommandSourceStack> node : roots) {
            collectAliases(node.getName(), redirects, levels, aliases);
        }
        return new CommandRequirements(levels, known, aliases);
    }

    /**
     * Records the names under which one command can also be run: its redirect target, and everything
     * that redirects at it.
     *
     * <p>A redirect points at the <em>body</em> of another literal rather than at a copy of it —
     * Brigadier's {@code CommandNode.getRedirect} hands the parse over at that point — so the two
     * roots are one command wearing two names. {@code /tp} is exactly this: {@code TeleportCommand}
     * registers {@code /teleport}, then registers {@code /tp} redirecting at that same node.
     *
     * <p>Grouped only when both names are root literals the live tree actually has, and never when
     * they demand different levels: a redirect that re-gates the command makes the two names genuinely
     * different choices, and merging those would hide one of them from the host.
     */
    private static void collectAliases(String literal, Map<String, String> redirects,
                                       Map<String, PermissionLevel> levels,
                                       Map<String, Set<String>> aliases) {
        Set<String> group = new LinkedHashSet<>();
        group.add(literal);
        String target = redirects.get(literal);
        if (target != null && levels.containsKey(target) && levels.get(target) == levels.get(literal)) {
            group.add(target);
        }
        for (Map.Entry<String, String> other : redirects.entrySet()) {
            if (!other.getKey().equals(literal) && other.getValue().equals(literal)
                    && levels.get(other.getKey()) == levels.get(literal)) {
                group.add(other.getKey());
            }
        }
        if (group.size() > 1) {
            Set<String> copy = Set.copyOf(group);
            for (String name : group) {
                aliases.put(name, copy);
            }
        }
    }

    /**
     * The highest level anything inside {@code node}'s subtree demands.
     *
     * <p>Vanilla installs a level check as the requirement of every command node, and
     * {@code Commands.fillUsableCommands} calls {@code canUse} on <em>each</em> node while walking,
     * so the effective gate of {@code /tp} is the strongest requirement anywhere below it — not just
     * the one on {@code /tp} itself. Taking the maximum over the subtree is therefore exactly what the
     * host needs: handing out {@code /tp} at that level opens precisely the same nodes vanilla would
     * open.
     */
    private static PermissionLevel overall(CommandNode<CommandSourceStack> node, PermissionLevel highest) {
        PermissionLevel level = matchCheck(node);
        if (level == null) {
            level = probe(node);
        }
        highest = GrantLevels.stronger(highest, level);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            highest = overall(child, highest);
        }
        // A subtree that demands nothing at all is open to everyone, which is the baseline level —
        // never null, so the caller can rely on every known literal having an entry.
        return highest == null ? GrantLevels.BASELINE : highest;
    }

    /** @return the vanilla level this node's requirement stands for, or {@code null} if it is not one */
    private static PermissionLevel matchCheck(CommandNode<CommandSourceStack> node) {
        return VanillaLevel.of(node.getRequirement());
    }

    /**
     * Slow path: the lowest level whose synthetic source the node accepts.
     *
     * <p>Brigadier exposes no way to read a node's parent and no way to replace a requirement, so
     * this asks the node directly. {@code canUse} consults only this node's own requirement, which
     * is enough: {@link #overall} takes the maximum over the whole subtree, so an ancestor's gate is
     * accounted for by the ancestor's own contribution to that maximum.
     *
     * <p>Anything even {@code OWNERS} refuses is reported as {@code OWNERS} rather than dropped, so a
     * modded command is never silently offered as if it were free.
     */
    private static PermissionLevel probe(CommandNode<CommandSourceStack> node) {
        if (node.canUse(Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS))) {
            return PermissionLevel.ALL;
        }
        for (PermissionLevel level : GrantLevels.GRANTABLE) {
            CommandSourceStack source =
                    Commands.createCompilationContext(LevelBasedPermissionSet.forLevel(level));
            if (node.canUse(source)) {
                return level;
            }
        }
        return PermissionLevel.OWNERS;
    }

}
