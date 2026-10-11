package com.example.commandgrants;

import java.util.Map;

import net.minecraft.server.permissions.PermissionLevel;

/**
 * Coarse bundles offered next to the per-command checkboxes in the host UI.
 *
 * <p>A preset is a <em>ceiling</em>: selecting it opens every command whose vanilla requirement is
 * at or below {@link #ceiling()}, and never opens anything stronger. The host can refine the result
 * command by command afterwards, which turns the record into {@link Kind#CUSTOM}.
 */
final class GrantPresets {
    private GrantPresets() {
    }

    enum Kind {
        /** Nothing at all. */
        NONE("none", null),
        /** Everything the moderator level reaches: /kick, /ban, /whitelist, ... */
        MODERATOR("moderator", PermissionLevel.MODERATORS),
        /** Everything up to gamemaster: /tp, /gamemode, /give, /time, ... */
        GAMEMASTER("gamemaster", PermissionLevel.GAMEMASTERS),
        /** Manual selection; produced by the UI, never picked directly from the preset list. */
        CUSTOM("custom", null);

        private final String id;
        private final PermissionLevel ceiling;

        Kind(String id, PermissionLevel ceiling) {
            this.id = id;
            this.ceiling = ceiling;
        }

        String id() {
            return id;
        }

        /** Strongest level this preset may open, or {@code null} for NONE/CUSTOM. */
        PermissionLevel ceiling() {
            return ceiling;
        }

        static Kind byId(String id) {
            if (id != null) {
                String trimmed = id.trim();
                for (Kind kind : values()) {
                    if (kind.id.equalsIgnoreCase(trimmed) || kind.name().equalsIgnoreCase(trimmed)) {
                        return kind;
                    }
                }
            }
            return CUSTOM;
        }
    }

    /**
     * Best-effort detection of which preset produced a grant set, used when the host UI loads a
     * player and when a file written by another version is read.
     */
    static Kind infer(Map<String, PermissionLevel> granted, Map<String, PermissionLevel> required) {
        if (granted.isEmpty()) {
            return Kind.NONE;
        }
        if (matchesExactly(granted, required, Kind.MODERATOR)) {
            return Kind.MODERATOR;
        }
        if (matchesExactly(granted, required, Kind.GAMEMASTER)) {
            return Kind.GAMEMASTER;
        }
        return Kind.CUSTOM;
    }

    private static boolean matchesExactly(Map<String, PermissionLevel> granted,
                                          Map<String, PermissionLevel> required,
                                          Kind preset) {
        PermissionLevel ceiling = preset.ceiling();
        int expected = 0;
        for (Map.Entry<String, PermissionLevel> entry : required.entrySet()) {
            if (GrantLevels.covers(ceiling, entry.getValue())) {
                expected++;
                if (granted.get(entry.getKey()) != entry.getValue()) {
                    return false;
                }
            } else if (granted.containsKey(entry.getKey())) {
                return false;
            }
        }
        return granted.size() == expected;
    }
}
