package com.example.commandgrants;

import java.util.TreeMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.permissions.PermissionLevel;

/**
 * One player's opened commands: {@code root command literal -> the level the host opened}.
 *
 * <p>Keyed by the root literal ({@code tp}, {@code gamemode}, ...) because that is the granularity
 * the host reasons about and the granularity the vanilla command tree is filtered at
 * ({@code Commands.fillUsableCommands} walks children of the dispatcher root). The stored level is
 * the <em>ceiling</em> for that command, compared against the command's vanilla requirement with
 * {@link GrantLevels#covers} — it never changes what the command itself demands.
 *
 * <p>Instances are immutable; edits produce a new instance so a stale wrapper can never observe a
 * half-applied change.
 */
public final class PlayerGrant {
    public static final PlayerGrant NONE = new PlayerGrant(new UUID(0L, 0L), "", Map.of());

    private final UUID playerId;
    private final String name;
    private final Map<String, PermissionLevel> granted;

    public PlayerGrant(UUID playerId, String name, Map<String, PermissionLevel> granted) {
        this.playerId = playerId;
        this.name = name == null ? "" : name;
        this.granted = Map.copyOf(new TreeMap<>(granted));
    }

    public static PlayerGrant empty(UUID playerId, String name) {
        return new PlayerGrant(playerId, name, Map.of());
    }

    public UUID playerId() {
        return playerId;
    }

    public String name() {
        return name;
    }

    public PlayerGrant withName(String newName) {
        return new PlayerGrant(playerId, newName, granted);
    }

    /** Immutable view of the opened commands. */
    public Map<String, PermissionLevel> granted() {
        return granted;
    }

    public boolean isEmpty() {
        return granted.isEmpty();
    }

    public boolean isOpen(String literal) {
        return literal != null && granted.containsKey(literal);
    }

    /** @return a copy with {@code literal} opened at {@code level} */
    public PlayerGrant with(String literal, PermissionLevel level) {
        Map<String, PermissionLevel> next = new TreeMap<>(granted);
        next.put(literal, level);
        return new PlayerGrant(playerId, name, next);
    }

    /** @return a copy with {@code literal} closed again */
    public PlayerGrant without(String literal) {
        if (!granted.containsKey(literal)) {
            return this;
        }
        Map<String, PermissionLevel> next = new TreeMap<>(granted);
        next.remove(literal);
        return new PlayerGrant(playerId, name, next);
    }

    /**
     * @return a copy holding exactly the commands whose vanilla requirement is at or below the
     *         preset's ceiling, and nothing stronger
     */
    public PlayerGrant withPreset(GrantPresets.Kind preset, Map<String, PermissionLevel> required) {
        Map<String, PermissionLevel> next = new TreeMap<>();
        PermissionLevel ceiling = preset.ceiling();
        if (ceiling != null) {
            for (Map.Entry<String, PermissionLevel> entry : required.entrySet()) {
                if (GrantLevels.covers(ceiling, entry.getValue())) {
                    next.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new PlayerGrant(playerId, name, next);
    }

    /** Highest opened level, or {@code null} when nothing is opened. */
    public PermissionLevel highestLevel() {
        PermissionLevel highest = null;
        for (PermissionLevel level : granted.values()) {
            highest = GrantLevels.stronger(highest, level);
        }
        return highest;
    }
}
