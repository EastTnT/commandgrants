package com.example.commandgrants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.minecraft.server.permissions.PermissionLevel;

/**
 * The per-world grant ledger, persisted as {@code <world>/commandgrants.json}.
 *
 * <p>Writes are atomic (temp file + move) so a crash cannot leave a half-written ledger that would
 * silently revoke every grant on the next load. Reads are tolerant: an unreadable or hand-edited
 * file logs a warning and falls back to "nothing open" rather than failing server start.
 *
 * <p>The file stores concrete grants, not preset names: what the host agreed to is the list of open
 * commands, and a preset is only a shortcut for producing that list. Storing the expansion keeps the
 * meaning of a saved file stable even if the preset definitions or the command tree change.
 */
public final class GrantsStorage {
    /** Bumped when the on-disk shape changes in a way older readers cannot handle. */
    private static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(PermissionLevel.class, new PermissionLevelAdapter())
            .create();

    private final Path file;
    private final Map<UUID, PlayerGrant> byPlayer = new LinkedHashMap<>();
    private boolean disabled;

    public GrantsStorage(Path file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ access

    /** Every player with a stored record, in a stable order for UIs and commands. */
    public List<PlayerGrant> players(CommandIndex index) {
        List<PlayerGrant> out = new ArrayList<>();
        for (PlayerGrant grant : byPlayer.values()) {
            out.add(index == null ? grant : sanitize(grant, index));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** All records regardless of the index, for tests and diagnostics. */
    public Collection<PlayerGrant> raw() {
        return byPlayer.values();
    }

    public PlayerGrant grant(UUID playerId, String fallbackName) {
        if (playerId == null) {
            return PlayerGrant.NONE;
        }
        PlayerGrant existing = byPlayer.get(playerId);
        if (existing != null) {
            if (fallbackName != null && !fallbackName.isBlank() && !fallbackName.equals(existing.name())) {
                PlayerGrant renamed = existing.withName(fallbackName);
                byPlayer.put(playerId, renamed);
                return renamed;
            }
            return existing;
        }
        return PlayerGrant.empty(playerId, fallbackName == null ? "" : fallbackName);
    }

    /** Valid even when the ledger is disabled and even when nothing was ever granted. */
    public GrantTable table(UUID playerId, CommandRequirements requirements) {
        if (disabled || playerId == null) {
            return GrantTable.EMPTY;
        }
        PlayerGrant grant = byPlayer.get(playerId);
        return grant == null ? GrantTable.EMPTY : GrantTable.of(grant, requirements);
    }

    public boolean isEmpty() {
        return byPlayer.isEmpty();
    }

    public boolean exists(UUID playerId) {
        return playerId != null && byPlayer.containsKey(playerId);
    }

    /**
     * Global off switch for the whole mod. Grants stay on disk (so re-enabling restores the previous
     * state) but stop applying anywhere.
     */
    public boolean disabled() {
        return disabled;
    }

    public void setDisabled(boolean value) {
        this.disabled = value;
    }

    // ------------------------------------------------------------------ edits

    /** Replaces one player's record. An empty record is removed rather than stored as an empty entry. */
    public void put(PlayerGrant grant) {
        if (grant == null || grant.playerId() == null) {
            return;
        }
        if (grant.isEmpty()) {
            byPlayer.remove(grant.playerId());
        } else {
            byPlayer.put(grant.playerId(), grant);
        }
    }

    public boolean remove(UUID playerId) {
        return playerId != null && byPlayer.remove(playerId) != null;
    }

    public void clear() {
        byPlayer.clear();
    }

    /** Drops stored commands that no longer exist in the live tree. */
    private static PlayerGrant sanitize(PlayerGrant grant, CommandIndex index) {
        Map<String, PermissionLevel> kept = index.retainKnown(grant.granted());
        return kept.size() == grant.granted().size() ? grant : new PlayerGrant(grant.playerId(), grant.name(), kept);
    }

    // ------------------------------------------------------------------ file

    public void load() {
        byPlayer.clear();
        disabled = false;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Root root = GSON.fromJson(reader, Root.class);
            if (root == null) {
                return;
            }
            disabled = root.disabled;
            if (root.players != null) {
                for (Entry entry : root.players) {
                    PlayerGrant grant = entry.toGrant();
                    if (grant != null && !grant.isEmpty()) {
                        byPlayer.put(grant.playerId(), grant);
                    }
                }
            }
            CommandGrantsMod.LOGGER.info("Loaded {} command grant record(s) from {}", byPlayer.size(), file);
        } catch (IOException | JsonSyntaxException e) {
            CommandGrantsMod.LOGGER.warn("Could not read {} — starting with no grants open ({})",
                    file, e.getMessage());
        }
    }

    /** @return {@code true} when the ledger was written */
    public boolean save() {
        if (file == null) {
            return false;
        }
        Root root = new Root();
        root.version = FORMAT_VERSION;
        root.disabled = disabled;
        root.players = new ArrayList<>();
        for (PlayerGrant grant : byPlayer.values()) {
            root.players.add(new Entry(grant));
        }
        Path parent = file.getParent();
        Path temp = parent == null ? null : parent.resolve(file.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (temp == null) {
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                }
                return true;
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            // Atomic replace: a crash mid-write leaves the previous ledger intact.
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            CommandGrantsMod.LOGGER.error("Could not write {}", file, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort; the stale temp file is harmless.
                }
            }
            return false;
        }
    }

    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------ json shape

    private static final class Root {
        @SerializedName("version")
        int version = FORMAT_VERSION;
        @SerializedName("disabled")
        boolean disabled;
        @SerializedName("players")
        List<Entry> players = new ArrayList<>();
    }

    private static final class Entry {
        @SerializedName("uuid")
        String uuid;
        @SerializedName("name")
        String name;
        @SerializedName("granted")
        Map<String, PermissionLevel> granted = new LinkedHashMap<>();

        Entry() {
        }

        Entry(PlayerGrant grant) {
            this.uuid = grant.playerId().toString();
            this.name = grant.name();
            this.granted = new LinkedHashMap<>(grant.granted());
        }

        PlayerGrant toGrant() {
            if (uuid == null) {
                return null;
            }
            UUID id;
            try {
                id = UUID.fromString(uuid.trim());
            } catch (IllegalArgumentException e) {
                CommandGrantsMod.LOGGER.warn("Ignoring grant record with malformed uuid '{}'", uuid);
                return null;
            }
            Map<String, PermissionLevel> levels = new LinkedHashMap<>();
            if (granted != null) {
                granted.forEach((literal, level) -> {
                    if (literal != null && level != null) {
                        levels.put(literal, level);
                    }
                });
            }
            return new PlayerGrant(id, name == null ? "" : name, levels);
        }
    }

    /**
     * Serializes a level as the same lowercase name the command tree prints ({@code "gamemasters"}),
     * so the file stays readable and hand-editable; unknown names read back as absent.
     */
    private static final class PermissionLevelAdapter extends TypeAdapter<PermissionLevel> {
        @Override
        public void write(JsonWriter out, PermissionLevel value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.getSerializedName());
            }
        }

        @Override
        public PermissionLevel read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return GrantLevels.parse(in.nextString());
        }
    }
}
