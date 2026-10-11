package com.example.commandgrants.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

import com.example.commandgrants.CommandGrantsMod;
import com.example.commandgrants.CommandGrantsNetwork;

/**
 * Client entry point.
 *
 * <p>The client never decides anything: it caches the snapshot the server pushed so the LAN editor can
 * render without a round trip, and forwards every edit back as an {@link CommandGrantsNetwork.Edit}
 * payload. The server re-checks ownership on arrival, so a forged edit is only ignored.
 */
public final class CommandGrantsClient implements ClientModInitializer {
    /** Keeps the header button off the very edge of the screen, like vanilla's own corner widgets. */
    public static final int HEADER_MARGIN = 4;

    private static CommandGrantsNetwork.Sync snapshot = new CommandGrantsNetwork.Sync(false, List.of(), List.of());

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CommandGrantsNetwork.SYNC_TYPE, (payload, context) -> {
            snapshot = payload;
            context.client().execute(CommandGrantsClient::refreshScreen);
        });
        CommandGrantsMod.LOGGER.info("[CommandGrants] client initialized");
    }

    /** Nudges an open editor to rebuild; the snapshot it renders is the one just received. */
    private static void refreshScreen() {
        // 26.2 moved the active screen off `Minecraft` and onto its `Gui`.
        if (Minecraft.getInstance().gui.screen() instanceof GrantEditorScreen editor) {
            editor.markStale();
        }
    }

    /** The most recent snapshot, or an empty one while offline. */
    public static CommandGrantsNetwork.Sync snapshot() {
        return snapshot;
    }

    public static List<CommandGrantsNetwork.Player> players() {
        return snapshot.players();
    }

    public static List<CommandGrantsNetwork.Entry> commands() {
        return snapshot.commands();
    }

    public static boolean disabled() {
        return snapshot.disabled();
    }

    public static CommandGrantsNetwork.Player player(UUID playerId) {
        for (CommandGrantsNetwork.Player player : snapshot.players()) {
            if (player.playerId().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    public static boolean isGranted(UUID playerId, String literal) {
        CommandGrantsNetwork.Player player = player(playerId);
        return player != null && player.granted().containsKey(literal);
    }

    public static PermissionLevel levelOf(UUID playerId, String literal) {
        CommandGrantsNetwork.Player player = player(playerId);
        return player == null ? null : player.granted().get(literal);
    }

    /**
     * Whether this client may open the editor.
     *
     * <p>Mirrors the server's own gate: the integrated server's owner holds everything, and a dedicated
     * server's operators hold {@code GAMEMASTERS} and up. The button is only a convenience — the payload
     * handlers enforce the same rule.
     *
     * <p>The owner half cannot be answered by permissions. With cheats off, the host of a singleplayer
     * world holds a plain {@code ALL} set, so asking for {@code GAMEMASTERS} disables the very button
     * this mod exists to offer. The integrated server is the authority on who owns the world, so ask it
     * directly. Deliberately not {@code getSingleplayerServer() != null} alone: that would light the
     * button up for anyone who later joins the same world.
     */
    public static boolean canEdit() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null && integrated.isSingleplayerOwner(minecraft.player.nameAndId())) {
            return true;
        }
        PermissionLevel level = highestLevel();
        return level != null && level.isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
    }

    /**
     * The highest command level this client's player holds, or {@code null} when it holds none.
     *
     * <p>{@code PermissionSet} only answers {@code hasPermission}, so the level has to be recovered by
     * probing downward from the top. That is also why the editor trusts the server's snapshot for
     * everything else: the client can see its own level, but the ledger is not its business to judge.
     */
    private static PermissionLevel highestLevel() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        PermissionSet permissions = player.permissions();
        if (permissions == null) {
            return null;
        }
        // Strongest first: the enum runs ALL, MODERATORS, GAMEMASTERS, ADMINS, OWNERS, so walking it
        // forwards would report an admin as a mere moderator.
        PermissionLevel[] levels = PermissionLevel.values();
        for (int i = levels.length - 1; i >= 0; i--) {
            PermissionLevel level = levels[i];
            if (level == PermissionLevel.ALL) {
                continue;
            }
            if (permissions.hasPermission(new Permission.HasCommandLevel(level))) {
                return level;
            }
        }
        return null;
    }

    /** Sends one toggle; the server answers with a fresh snapshot. */
    public static void toggle(UUID playerId, String literal, boolean open) {
        if (ClientPlayNetworking.canSend(CommandGrantsNetwork.EDIT_TYPE)) {
            ClientPlayNetworking.send(new CommandGrantsNetwork.Edit(playerId, literal, open));
        }
    }

    /** Asks the server to apply a whole preset; it recomputes the set against its own command tree. */
    public static void preset(UUID playerId, String presetId) {
        if (ClientPlayNetworking.canSend(CommandGrantsNetwork.PRESET_TYPE)) {
            ClientPlayNetworking.send(new CommandGrantsNetwork.Preset(playerId, presetId));
        }
    }
}
