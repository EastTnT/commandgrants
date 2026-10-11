package com.example.commandgrants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.RootCommandNode;

import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One live grant manager per running server: owns the ledger, the command catalogue, and the derived
 * per-player tables the mixins consult.
 *
 * <p>Read paths ({@link #permissionsFor}, {@link #filteredRoot}) may be reached from the network
 * thread while a player handshakes, so the derived tables live in concurrent maps and are only ever
 * replaced wholesale by {@link #invalidate(UUID)} on the server thread.
 */
public final class CommandGrantsServer {
    /**
     * Registry key for the question "has this player been granted anything at all?", so permission
     * managers and other mods can ask without knowing about the ledger.
     */
    public static final Identifier GRANTED_COMMANDS_NODE = Identifier.fromNamespaceAndPath("commandgrants",
            "granted_commands");

    /** Registry key for "does this player's grant set reach the given command level?" */
    public static final String LEVEL_NODE_PREFIX = "command_level_";

    private static final String LEVEL_NODE_NAMESPACE = "commandgrants";
    private static final String LEVEL_NODE_PATH_PREFIX = "command_level_";

    private static final String[] LEVEL_NODE_PATHS = {
            LEVEL_NODE_PREFIX + "moderators",
            LEVEL_NODE_PREFIX + "gamemasters",
            LEVEL_NODE_PREFIX + "admins",
            LEVEL_NODE_PREFIX + "owners"
    };

    /** Every running server in this JVM, newest last. */
    private static final List<CommandGrantsServer> SERVERS = new CopyOnWriteArrayList<>();

    private static final ThreadLocal<CommandGrantsServer> CURRENT = new ThreadLocal<>();

    private final MinecraftServer server;
    private final GrantsStorage storage;

    private volatile CommandRequirements requirements = CommandRequirements.empty();
    private volatile CommandIndex index = CommandIndex.EMPTY;

    public CommandGrantsServer(MinecraftServer server) {
        this.server = server;
        this.storage = new GrantsStorage(server.getWorldPath(LevelResource.ROOT).resolve("commandgrants.json"));
        this.storage.load();
        this.disabled = this.storage.disabled();
    }

    /** Shared derivation helper; its caches are keyed by grant set, so one instance serves all players. */
    private final GrantAccess access = new GrantAccess();

    /** Mirror of {@link GrantsStorage#disabled()}, readable without touching the ledger's lock. */
    private volatile boolean disabled;

    // ------------------------------------------------------------------ lifecycle

    public static CommandGrantsServer open(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        CommandGrantsServer existing = find(server);
        if (existing != null) {
            CURRENT.set(existing);
            return existing;
        }
        CommandGrantsServer created = new CommandGrantsServer(server);
        SERVERS.add(created);
        CURRENT.set(created);
        created.reload();
        return created;
    }

    /** The manager for {@code server}, or {@code null} when the server is not running the mod. */
    public static CommandGrantsServer of(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        CommandGrantsServer cached = CURRENT.get();
        if (cached != null && cached.server == server) {
            return cached;
        }
        CommandGrantsServer found = find(server);
        if (found != null) {
            CURRENT.set(found);
        }
        return found;
    }

    private static CommandGrantsServer find(MinecraftServer server) {
        for (CommandGrantsServer candidate : SERVERS) {
            if (candidate.server == server) {
                return candidate;
            }
        }
        return null;
    }

    public static CommandGrantsServer of(ServerPlayer player) {
        return player == null ? null : of(player.level().getServer());
    }

    /** Alias of {@link #of(ServerPlayer)} that {@code ServerPlayerMixin} binds against. */
    public static CommandGrantsServer instanceFor(ServerPlayer player) {
        return of(player);
    }

    /**
     * The permission set to hand {@code player}, or {@code null} to leave vanilla's untouched.
     *
     * <p>Never returns a set that grants more than the ledger says, so the static call site in
     * {@code ServerPlayerMixin} can be a two-liner: ask for the widened set, and use the original
     * when there is nothing to widen.
     */
    public static PermissionSet widenPermissions(ServerPlayer player, PermissionSet base) {
        if (player == null || base == null) {
            return null;
        }
        CommandGrantsServer manager = of(player);
        if (manager == null || manager.disabled) {
            return null;
        }
        if (manager.table(player.getUUID()).isEmpty()) {
            return null;
        }
        return manager.permissionsFor(player);
    }

    public static void close(MinecraftServer server) {
        CommandGrantsServer manager = find(server);
        if (manager == null) {
            return;
        }
        manager.save();
        SERVERS.remove(manager);
        if (CURRENT.get() == manager) {
            CURRENT.remove();
        }
    }

    public static void closeAll() {
        for (CommandGrantsServer manager : List.copyOf(SERVERS)) {
            manager.save();
        }
        SERVERS.clear();
        CURRENT.remove();
    }

    /** Registers the permission-node responses, once per JVM. */
    public static void registerPermissionNodes() {
        PermissionEvents.ON_REQUEST.register(CommandGrantsServer::answerPermissionRequest);
    }

    // ------------------------------------------------------------------ accessors

    /** The ledger, for commands and the LAN editor. */
    public GrantsStorage storage() {
        return storage;
    }

    public MinecraftServer server() {
        return server;
    }

    /**
     * The grants of everyone currently connected, whether or not they have a stored record.
     *
     * <p>Connected players are listed by the editor even before anything was ever granted to them: a
     * record only materialises once a grant is written, so waiting for the ledger to name someone would
     * leave the very first player unselectable and the editor unusable on a fresh world. Nothing is
     * stored here — these are read-only views the editor renders as empty rows.
     */
    public List<PlayerGrant> onlineGrants() {
        return onlineGrants(null);
    }

    /**
     * The grants of everyone connected, plus {@code extra} when the caller knows of a player the list
     * does not carry yet.
     *
     * <p>See {@code CommandGrantsNetwork.sendGrants} for why the join handler has to pass one.
     */
    public List<PlayerGrant> onlineGrants(ServerPlayer extra) {
        List<PlayerGrant> out = new ArrayList<>();
        if (extra != null) {
            out.add(PlayerGrant.empty(extra.getUUID(), extra.nameAndId().name()));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            out.add(PlayerGrant.empty(player.getUUID(), player.nameAndId().name()));
        }
        return out;
    }

    public CommandIndex index() {
        return index;
    }

    public CommandRequirements requirements() {
        return requirements;
    }

    // ------------------------------------------------------------------ derived tables

    /** Re-reads the tree and pushes every online player a new command tree. */
    public void reload() {
        this.requirements = CommandRequirements.discover(server.getCommands().getDispatcher());
        this.index = CommandIndex.build(this.requirements, this::hostHolds);
        invalidateAll();
    }

    /**
     * Whether the host may offer a command that demands {@code level}.
     *
     * <p>On an integrated server the host holds the console's authority over their own world, so every
     * level is offerable — exactly like the console on a dedicated server. The reason is not a
     * formality: with cheats off the singleplayer owner's {@code getProfilePermissions} answers
     * <em>false</em> for every level, so measuring the cap by that profile made the owner unable to
     * offer anything. The first ticked row then rebuilt the catalogue with {@code available=false} for
     * every command at that level, the editor greyed them out, and every row after that silently
     * refused to send a payload — the "the second command I ticked still cannot be used" report.
     *
     * <p>On a dedicated server the operators' own profile permissions remain the cap, because there the
     * host is merely an operator and should not be able to hand out more than they hold themselves.
     */
    private boolean hostHolds(PermissionLevel level) {
        if (server.isDedicatedServer()) {
            ServerPlayer host = findHost();
            if (host == null) {
                // Dedicated server with its owner offline: the console holds everything, so every
                // restricted level is offerable.
                return true;
            }
            return server.getProfilePermissions(host.nameAndId()).hasPermission(permissionFor(level));
        }
        // Integrated server. The profile permissions are not asked: see the class comment above — they
        // read as "no level at all" with cheats off, which is the case this mod exists for.
        return true;
    }

    /** The vanilla permission a command of {@code level} needs from a {@code PermissionSet}. */
    private static Permission permissionFor(PermissionLevel level) {
        return switch (level) {
            case ALL, MODERATORS -> Permissions.COMMANDS_MODERATOR;
            case GAMEMASTERS -> Permissions.COMMANDS_GAMEMASTER;
            case ADMINS -> Permissions.COMMANDS_ADMIN;
            case OWNERS -> Permissions.COMMANDS_OWNER;
        };
    }

    private ServerPlayer findHost() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        if (!server.isDedicatedServer()) {
            // Integrated server: the only player is the host unless the LAN session has guests, and
            // the list starts with the host.
            return players.get(0);
        }
        for (ServerPlayer player : players) {
            if (server.isSingleplayerOwner(player.nameAndId())) {
                return player;
            }
        }
        return null;
    }

    /**
     * The permission set handed to {@code player}: their real one, widened by whatever the host opened
     * for them. A player with no record gets their vanilla set untouched, so a server where nobody has
     * been granted anything behaves exactly like vanilla.
     *
     * <p>The host is wrapped like anybody else. Skipping the wrapper for the owner looks like a
     * harmless simplification — "they hold everything on their own world" — but with cheats off the
     * singleplayer owner holds <em>no</em> level permissions, so it silently dropped the grants they
     * just made: {@code /gamemode} was left out of their own tree and their parse of it failed. A host
     * handing out access to themselves is the very first thing anyone tries, and it has to work.
     */
    public PermissionSet permissionsFor(ServerPlayer player) {
        PermissionSet base = server.getProfilePermissions(player.nameAndId());
        GrantTable table = table(player.getUUID());
        if (table.isEmpty()) {
            return base;
        }
        return new GrantedPermissionSet(base, table);
    }

    /** The grant table currently governing {@code playerId}, or {@link GrantTable#EMPTY}. */
    public GrantTable table(UUID playerId) {
        return storage.table(playerId, requirements);
    }

    /**
     * The tree a player may see: vanilla's, trimmed to the root literals opened for them.
     *
     * <p>Derived through the player's own wrapper, so the trim is always computed from the table that
     * {@link #permissionsFor} is handing out — the tree and the permissions can never disagree.
     *
     * @return {@code root} unchanged when this player has no grants
     */
    public RootCommandNode<CommandSourceStack> filteredRoot(ServerPlayer player,
                                                            RootCommandNode<CommandSourceStack> root) {
        if (player == null || root == null) {
            return root;
        }
        GrantTable grants = table(player.getUUID());
        if (grants.isEmpty()) {
            return root;
        }
        // The real set, not permissionsFor: the trim has to tell "vanilla already let them in" apart
        // from "this grant let them in", and the widened set answers yes to both.
        return access.filteredRoot(player, server.getProfilePermissions(player.nameAndId()), root, grants,
                index);
    }

    /**
     * The dispatcher the server parses this player's chat commands against, with unopened root
     * commands removed.
     *
     * @return {@code dispatcher} unchanged when this player has no grants
     */
    public CommandDispatcher<CommandSourceStack> narrowedDispatcher(ServerPlayer player,
                                                                    CommandDispatcher<CommandSourceStack> dispatcher) {
        if (player == null || dispatcher == null) {
            return dispatcher;
        }
        GrantTable grants = table(player.getUUID());
        if (grants.isEmpty()) {
            return dispatcher;
        }
        return access.narrowDispatcher(player, server.getProfilePermissions(player.nameAndId()), dispatcher,
                grants, index);
    }

    /** Called after any ledger change; kept for call sites that only invalidate derived state. */
    public void invalidate(UUID playerId) {
        // Derived tables are keyed by grant set, so an edit naturally misses the cache. Nothing to do.
    }

    public void invalidateAll() {
        // See invalidate(UUID).
    }

    /** Rebuilds every derived table and pushes the catalogue to connected clients. */
    public void pulse() {
        this.disabled = storage.disabled();
        reload();
        // Permission first, then the tree: the tree's filter reads the player's permission set, which
        // the wrapper supplies from the ledger, but vanilla's own command packet is only re-sent when
        // the profile level changed — so ask for it explicitly and then send both.
        pushAll();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
        syncAll();
    }

    // ------------------------------------------------------------------ authorisation

    /**
     * Whether {@code player} is allowed to change grants.
     *
     * <p>The same test for every entry point — the LAN menu, the {@code /commandgrants} command and the
     * network payloads — so a guest cannot reach the ledger through a path the host forgot to close. On
     * an integrated server that is the host and only the host, which is exactly the "关闭作弊也能开放"
     * case; on a dedicated server it is the operators, who are the only ones with any business handing
     * out command access.
     */
    public boolean mayEdit(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (server.isSingleplayerOwner(player.nameAndId())) {
            return true;
        }
        return server.getPlayerList().isOp(player.nameAndId());
    }

    // ------------------------------------------------------------------ edits

    /** Opens or closes one root command for one player, then pushes the change out. */
    public void edit(ServerPlayer actor, UUID targetId, String literal, PermissionLevel required, boolean open) {
        if (targetId == null || literal == null) {
            return;
        }
        String name = nameOf(targetId);
        PlayerGrant grant = storage.grant(targetId, name);
        PlayerGrant updated = open ? grant.with(literal, required) : grant.without(literal);
        if (updated.granted().equals(grant.granted())) {
            syncTo(actor);
            return;
        }
        put(updated);
        pulse();
        announce(actor, targetId, literal, open);
    }

    /** Applies a preset, replacing whatever was open. Used by the command's convenience shortcuts. */
    public void applyPreset(ServerPlayer actor, UUID targetId, GrantPresets.Kind preset) {
        if (targetId == null) {
            return;
        }
        PlayerGrant grant = storage.grant(targetId, nameOf(targetId));
        PlayerGrant updated = preset == GrantPresets.Kind.NONE
                ? grant.withPreset(GrantPresets.Kind.NONE, Map.of())
                : grant.withPreset(preset, index.expand(preset.ceiling()));
        put(updated);
        pulse();
        message(actor, Component.translatable("commandgrants.feedback.preset",
                nameOf(targetId), preset.id()));
    }

    /** The display name stored for {@code playerId}, falling back to the live player or the id. */
    public String nameOf(UUID playerId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(playerId)) {
                return player.getName().getString();
            }
        }
        PlayerGrant stored = storage.grant(playerId, "");
        return stored.name().isEmpty() ? playerId.toString() : stored.name();
    }

    /**
     * Tells the host what just changed, in chat, so a click in the menu leaves a trace.
     *
     * <p>The name is passed as an argument rather than nested inside another translation: a translated
     * component used as an argument is flattened into the outer format, and any {@code %s} inside it is
     * then consumed by the outer {@code String.format}, so nesting one message in another renders raw
     * placeholders into chat.
     */
    private void announce(ServerPlayer actor, UUID targetId, String literal, boolean open) {
        if (actor == null) {
            return;
        }
        message(actor, Component.translatable(open ? "commandgrants.feedback.opened"
                : "commandgrants.feedback.closed", literal, nameOf(targetId)));
    }

    // ------------------------------------------------------------------ mutations

    /** Stores a player's record and immediately makes it effective for them. */
    public void put(PlayerGrant grant) {
        if (grant == null) {
            return;
        }
        storage.put(grant);
        storage.save();
        push(grant.playerId());
    }

    public boolean remove(UUID playerId) {
        if (!storage.remove(playerId)) {
            return false;
        }
        storage.save();
        pulse();
        return true;
    }

    public void clear() {
        storage.clear();
        storage.save();
        pulse();
    }

    public void save() {
        storage.save();
    }

    public void setDisabled(boolean disabled) {
        storage.setDisabled(disabled);
        storage.save();
        pulse();
    }

    /**
     * Re-derives a player's permissions and command tree and tells them about it. Called after every
     * edit so an opened command becomes usable — and visible in chat completion — immediately.
     */
    public void push(UUID playerId) {
        if (playerId == null) {
            return;
        }
        invalidate(playerId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(playerId)) {
                server.getPlayerList().sendPlayerPermissionLevel(player);
                return;
            }
        }
    }

    public void pushAll() {
        invalidateAll();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getPlayerList().sendPlayerPermissionLevel(player);
        }
    }

    // ------------------------------------------------------------------ notifications

    /** Tells every connected client the current ledger, so host UIs stay in step. */
    public void syncAll() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CommandGrantsNetwork.sendGrants(this, player);
        }
    }

    /** Tells one client the current ledger. */
    public void syncTo(ServerPlayer player) {
        // The player is passed as `extra` so a sync during the join event still carries a roster: at
        // that moment the player list does not contain them yet, and an empty roster makes the editor
        // render nothing at all.
        CommandGrantsNetwork.sendGrants(this, player, player);
    }

    public void message(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    // ------------------------------------------------------------------ permission bridge

    private static <T> T answerPermissionRequest(PermissionContext context, PermissionNode<T> node) {
        Identifier key = node.key();
        if (GRANTED_COMMANDS_NODE.equals(key)) {
            return node.cast(hasAnyGrant(context));
        }
        int index = levelNodeIndex(key);
        if (index >= 0) {
            return node.cast(reachesLevel(context, GrantLevels.GRANTABLE[index]));
        }
        // Every other request gets no opinion, which Fabric turns into the vanilla default.
        return null;
    }

    private static int levelNodeIndex(Identifier key) {
        if (!LEVEL_NODE_NAMESPACE.equals(key.getNamespace())) {
            return -1;
        }
        for (int i = 0; i < LEVEL_NODE_PATHS.length; i++) {
            if (LEVEL_NODE_PATHS[i].equals(key.getPath())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasAnyGrant(PermissionContext context) {
        CommandGrantsServer manager = of(context.get(PermissionContext.SERVER));
        UUID playerId = context.uuid();
        if (manager == null || playerId == null || manager.storage.disabled()) {
            return false;
        }
        return manager.storage.exists(playerId);
    }

    /** Whether the player's open commands let them run something that needs {@code level}. */
    private static boolean reachesLevel(PermissionContext context, PermissionLevel level) {
        CommandGrantsServer manager = of(context.get(PermissionContext.SERVER));
        UUID playerId = context.uuid();
        if (manager == null || playerId == null || manager.storage.disabled()) {
            return false;
        }
        for (PermissionLevel opened : manager.table(playerId).literals().values()) {
            if (GrantLevels.covers(opened, level)) {
                return true;
            }
        }
        return false;
    }
}
