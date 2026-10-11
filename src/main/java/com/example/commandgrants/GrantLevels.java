package com.example.commandgrants;

import net.minecraft.server.permissions.PermissionLevel;

/**
 * The vanilla command levels a host is allowed to hand out, in ascending order, plus the parsing
 * helpers that turn them into the strings stored in {@code commandgrants.json}.
 *
 * <p>Vanilla expresses "this command needs level N" through {@code Permission.HasCommandLevel}, so
 * the grantable set is exactly the {@link PermissionLevel} ladder. {@link PermissionLevel#ALL}
 * is deliberately not grantable: a node that carries no level requirement at all is already usable
 * by everyone, so "granting" it would be a no-op.
 */
final class GrantLevels {
    private GrantLevels() {
    }

    /** Weakest first — the order presets and the UI iterate in. */
    static final PermissionLevel[] GRANTABLE = {
            PermissionLevel.MODERATORS,
            PermissionLevel.GAMEMASTERS,
            PermissionLevel.ADMINS,
            PermissionLevel.OWNERS
    };

    /** What a command needs when the host has opened nothing: every player may run the free ones. */
    static final PermissionLevel BASELINE = PermissionLevel.ALL;

    /**
     * Parses a stored level name. Both the serialized spelling ({@code "gamemasters"}) and the
     * enum spelling ({@code "GAMEMASTERS"}) are accepted so a hand-edited file still loads.
     *
     * @return the level, or {@code null} when {@code name} is not a grantable level
     */
    static PermissionLevel parse(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (PermissionLevel level : GRANTABLE) {
            if (level.getSerializedName().equalsIgnoreCase(trimmed) || level.name().equalsIgnoreCase(trimmed)) {
                return level;
            }
        }
        if (BASELINE.getSerializedName().equalsIgnoreCase(trimmed) || BASELINE.name().equalsIgnoreCase(trimmed)) {
            return BASELINE;
        }
        return null;
    }

    /** Same as {@link #parse} but returning the canonical storage spelling. */
    static String normalize(String name) {
        PermissionLevel level = parse(name);
        return level == null ? null : serialize(level);
    }

    /** Canonical storage spelling, e.g. {@code "gamemasters"}. */
    static String serialize(PermissionLevel level) {
        return level.getSerializedName();
    }

    /**
     * Whether a command demanding {@code required} can be handed out at all. {@code null} (an
     * unknown requirement) and {@link #BASELINE} are not grantable.
     */
    static boolean isGrantable(PermissionLevel required) {
        if (required == null) {
            return false;
        }
        for (PermissionLevel level : GRANTABLE) {
            if (level == required) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code granted} is strong enough to run something that vanilla requires {@code required} for. */
    static boolean covers(PermissionLevel granted, PermissionLevel required) {
        return granted.isEqualOrHigherThan(required);
    }

    static PermissionLevel stronger(PermissionLevel a, PermissionLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isEqualOrHigherThan(b) ? a : b;
    }
}
