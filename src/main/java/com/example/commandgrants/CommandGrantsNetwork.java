package com.example.commandgrants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * The wire format that keeps the host's editor in step with the server's ledger.
 *
 * <p>Two directions, one shape: the server sends the whole ledger plus the command catalogue
 * ({@link Sync}), and the client sends back a single change ({@link Edit}). Sending the ledger whole
 * is what makes the editor safe to open at any time: it never shows a grant that the server has
 * already dropped, and a preset click cannot silently combine with a stale local copy.
 *
 * <p>The catalogue travels with the ledger rather than being derived on the client, because only the
 * server can see the live command tree — and the tree depends on installed mods and on the host's own
 * permissions.
 */
public final class CommandGrantsNetwork {
    /** Ids are namespaced so they cannot collide with another mod's payloads. */
    public static final Identifier SYNC_ID = Identifier.fromNamespaceAndPath(CommandGrantsMod.MOD_ID, "sync");
    public static final Identifier EDIT_ID = Identifier.fromNamespaceAndPath(CommandGrantsMod.MOD_ID, "edit");
    public static final Identifier SETTINGS_ID = Identifier.fromNamespaceAndPath(CommandGrantsMod.MOD_ID, "settings");
    public static final Identifier PRESET_ID = Identifier.fromNamespaceAndPath(CommandGrantsMod.MOD_ID, "preset");

    public static final CustomPacketPayload.Type<Sync> SYNC_TYPE = new CustomPacketPayload.Type<>(SYNC_ID);
    public static final CustomPacketPayload.Type<Edit> EDIT_TYPE = new CustomPacketPayload.Type<>(EDIT_ID);
    public static final CustomPacketPayload.Type<Settings> SETTINGS_TYPE =
            new CustomPacketPayload.Type<>(SETTINGS_ID);
    public static final CustomPacketPayload.Type<Preset> PRESET_TYPE = new CustomPacketPayload.Type<>(PRESET_ID);

    /** A listed command: what it is called, what it demands, and whether it can be offered. */
    public record Entry(String literal, PermissionLevel required, boolean offered, boolean available,
                        String group) {
        /**
         * Whether the host may hand this command out at all.
         *
         * <p>Mirrors {@code CommandIndex.Entry#grantable} for the client's copy of the catalogue, so
         * the editor can grey out what the server would refuse instead of offering a dead checkbox.
         */
        public boolean grantable() {
            return offered && available;
        }

        /**
         * The other names this command answers to, or an empty list when it has just its own.
         *
         * <p>Sent by the server rather than worked out here: which roots redirect at which is a
         * property of the live tree, and the client has no way to see one.
         */
        public List<String> aliases() {
            if (group == null || group.isBlank()) {
                return List.of();
            }
            return List.of(group.split(","));
        }
    }

    /** One player's record as stored. */
    public record Player(UUID playerId, String name, Map<String, PermissionLevel> granted) {
        /** Placeholder for an empty cycle button; its id is never a real player. */
        public static final Player EMPTY = new Player(new UUID(0L, 0L), "", Map.of());
    }

    /**
     * Server to client: everything the editor needs to draw itself.
     *
     * @param disabled whether the whole mod is switched off
     * @param players  every player with a stored record
     * @param commands the catalogue of root commands, whether grantable or not
     */
    public record Sync(boolean disabled, List<Player> players, List<Entry> commands) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Sync> type() {
            return SYNC_TYPE;
        }
    }

    /** Client to server: open or close one command for one player. */
    public record Edit(UUID playerId, String literal, boolean open) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Edit> type() {
            return EDIT_TYPE;
        }
    }

    /** Client to server: the editor's own controls — currently just the global off switch. */
    public record Settings(boolean disabled) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Settings> type() {
            return SETTINGS_TYPE;
        }
    }

    /**
     * Client to server: apply a whole preset to one player.
     *
     * <p>The preset travels as its id rather than as the resulting grant set, so the client never has to
     * know which commands a ceiling covers — the server recomputes that against the live tree.
     */
    public record Preset(UUID playerId, String presetId) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Preset> type() {
            return PRESET_TYPE;
        }
    }

    // ------------------------------------------------------------------ codecs

    private static final StreamCodec<FriendlyByteBuf, Sync> SYNC_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.disabled());
                buf.writeVarInt(payload.players().size());
                for (Player player : payload.players()) {
                    buf.writeUUID(player.playerId());
                    buf.writeUtf(player.name(), 64);
                    buf.writeVarInt(player.granted().size());
                    for (Map.Entry<String, PermissionLevel> grant : player.granted().entrySet()) {
                        buf.writeUtf(grant.getKey(), 64);
                        buf.writeEnum(grant.getValue());
                    }
                }
                buf.writeVarInt(payload.commands().size());
                for (Entry entry : payload.commands()) {
                    buf.writeUtf(entry.literal(), 64);
                    buf.writeEnum(entry.required());
                    buf.writeBoolean(entry.offered());
                    buf.writeBoolean(entry.available());
                    buf.writeUtf(entry.group() == null ? "" : entry.group(), 64);
                }
            },
            buf -> {
                boolean disabled = buf.readBoolean();
                int playerCount = buf.readVarInt();
                List<Player> players = new ArrayList<>(playerCount);
                for (int i = 0; i < playerCount; i++) {
                    UUID playerId = buf.readUUID();
                    String name = buf.readUtf(64);
                    int grantCount = buf.readVarInt();
                    Map<String, PermissionLevel> granted = new TreeMap<>();
                    for (int g = 0; g < grantCount; g++) {
                        String literal = buf.readUtf(64);
                        granted.put(literal, buf.readEnum(PermissionLevel.class));
                    }
                    players.add(new Player(playerId, name, granted));
                }
                int commandCount = buf.readVarInt();
                List<Entry> commands = new ArrayList<>(commandCount);
                for (int c = 0; c < commandCount; c++) {
                    commands.add(new Entry(buf.readUtf(64), buf.readEnum(PermissionLevel.class),
                            buf.readBoolean(), buf.readBoolean(), buf.readUtf(64)));
                }
                return new Sync(disabled, players, commands);
            });

    private static final StreamCodec<FriendlyByteBuf, Edit> EDIT_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.playerId());
                buf.writeUtf(payload.literal(), 64);
                buf.writeBoolean(payload.open());
            },
            buf -> new Edit(buf.readUUID(), buf.readUtf(64), buf.readBoolean()));

    private static final StreamCodec<FriendlyByteBuf, Settings> SETTINGS_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBoolean(payload.disabled()),
            buf -> new Settings(buf.readBoolean()));

    private static final StreamCodec<FriendlyByteBuf, Preset> PRESET_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.playerId());
                buf.writeUtf(payload.presetId(), 32);
            },
            buf -> new Preset(buf.readUUID(), buf.readUtf(32)));

    // ------------------------------------------------------------------ lifecycle

    /**
     * Registers both directions. Called once from the mod entry point, which Fabric runs on both
     * sides, so the client half of each codec is always registered too.
     */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(SYNC_TYPE, SYNC_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SETTINGS_TYPE, SETTINGS_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EDIT_TYPE, EDIT_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SETTINGS_TYPE, SETTINGS_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PRESET_TYPE, PRESET_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(EDIT_TYPE,
                (payload, context) -> applyEdit(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SETTINGS_TYPE,
                (payload, context) -> applySettings(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(PRESET_TYPE,
                (payload, context) -> applyPreset(context.player(), payload));
    }

    // ------------------------------------------------------------------ server side

    /** Sends one player the current ledger and catalogue. */
    public static void sendGrants(CommandGrantsServer manager, ServerPlayer recipient) {
        sendGrants(manager, recipient, null);
    }

    /**
     * Sends {@code recipient} the ledger and catalogue, guaranteeing that they are on the roster.
     *
     * <p>{@code extra} exists for the join handler: Fabric fires the join event while the player is
     * still being placed, and on a real client their entry is not yet in the player list at that
     * moment — so building the roster from the list alone sends an empty one, and the host's editor
     * then has nobody to select and refuses to render a single command. The event already hands the
     * player over, so the roster is seeded from that instead of from the list.
     */
    public static void sendGrants(CommandGrantsServer manager, ServerPlayer recipient, ServerPlayer extra) {
        if (manager == null || recipient == null || !ServerPlayNetworking.canSend(recipient, SYNC_TYPE)) {
            return;
        }
        Sync payload = snapshot(manager, extra);
        ServerPlayNetworking.send(recipient, payload);
    }

    /** Sends the off switch on its own, for clients that only need to follow it. */
    public static void sendSettings(CommandGrantsServer manager, ServerPlayer recipient) {
        if (manager == null || recipient == null || !ServerPlayNetworking.canSend(recipient, SETTINGS_TYPE)) {
            return;
        }
        ServerPlayNetworking.send(recipient, new Settings(manager.storage().disabled()));
    }

    /** Builds the payload for {@code manager}'s current state. */
    public static Sync snapshot(CommandGrantsServer manager) {
        return snapshot(manager, null);
    }

    /** Builds the payload, with {@code extra} forced onto the roster even if the list lacks them. */
    public static Sync snapshot(CommandGrantsServer manager, ServerPlayer extra) {
        // Stored records first, then everyone currently connected on top. A player has a record only
        // once a grant was written for them, so a fresh world would otherwise send an empty roster and
        // the editor would have nobody to select — and with nobody selected it disables every
        // checkbox, which reads as "this mod does nothing" rather than "grant something first".
        Map<UUID, Player> players = new LinkedHashMap<>();
        for (PlayerGrant grant : manager.storage().players(manager.index())) {
            players.put(grant.playerId(), new Player(grant.playerId(), grant.name(),
                    new TreeMap<>(grant.granted())));
        }
        for (PlayerGrant grant : manager.onlineGrants(extra)) {
            players.putIfAbsent(grant.playerId(), new Player(grant.playerId(), grant.name(),
                    new TreeMap<>(grant.granted())));
        }
        List<Entry> commands = new ArrayList<>();
        for (CommandIndex.Entry entry : manager.index().entries().values()) {
            commands.add(new Entry(entry.literal(), entry.required(), entry.offered(), entry.available(),
                    entry.group()));
        }
        commands.sort((a, b) -> a.literal().compareToIgnoreCase(b.literal()));
        return new Sync(manager.storage().disabled(), List.copyOf(players.values()), commands);
    }

    /**
     * Applies one checkbox from the host's editor.
     *
     * <p>Every edit is validated against the server's own catalogue rather than trusted, because the
     * client's copy of it may be older than the tree — a command can disappear when a mod is removed.
     */
    private static void applyEdit(ServerPlayer player, Edit edit) {
        CommandGrantsServer manager = CommandGrantsServer.of(player);
        if (manager == null || !manager.mayEdit(player)) {
            return;
        }
        CommandIndex.Entry entry = manager.index().entry(edit.literal());
        if (entry == null || (edit.open() && !entry.grantable())) {
            // Opened something that is not offerable, or is no longer in the tree at all.
            manager.syncTo(player);
            return;
        }
        manager.edit(player, edit.playerId(), edit.literal(), entry.required(), edit.open());
    }

    private static void applySettings(ServerPlayer player, Settings settings) {
        CommandGrantsServer manager = CommandGrantsServer.of(player);
        if (manager == null || !manager.mayEdit(player)) {
            return;
        }
        manager.setDisabled(settings.disabled());
    }

    /** Applies a preset id; an unknown id resolves to {@link GrantPresets.Kind#CUSTOM}, which is a no-op. */
    private static void applyPreset(ServerPlayer player, Preset preset) {
        CommandGrantsServer manager = CommandGrantsServer.of(player);
        if (manager == null || !manager.mayEdit(player)) {
            return;
        }
        manager.applyPreset(player, preset.playerId(), GrantPresets.Kind.byId(preset.presetId()));
    }
}
