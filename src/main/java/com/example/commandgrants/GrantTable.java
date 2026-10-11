package com.example.commandgrants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * The runtime face of one player's grant record: which root commands the host opened, and the level
 * each of them was opened at.
 *
 * <p>It answers two questions, and they are deliberately different:
 *
 * <ul>
 *   <li>{@link #allowsLiteral} — "is this root command open?" — the question
 *       {@link GrantAccess#canEnter} asks before letting a command into a tree or a parse. It is
 *       per command and must be, because one level covers many commands ({@code /tp} and
 *       {@code /gamemode} are both {@code GAMEMASTERS}) and a host's intent is per command.
 *   <li>{@link #allows} — "does the opened set satisfy this {@code Permission}?" — the question
 *       {@link GrantedPermissionSet} asks, on behalf of every <em>sub</em>command and every other
 *       system that consults a command level. Here a level is the right granularity: vanilla puts the
 *       same {@code Permission} on {@code /time} and on {@code /time set}, so the only way an opened
 *       {@code /time} can work is if the level behind it is granted.
 * </ul>
 *
 * <p>{@link #allows} never claims more than the host opened: the permission's own declared level has
 * to be covered by an opened command, so a player holding {@code /tp} at {@code GAMEMASTERS} does not
 * thereby reach {@code ADMINS}.
 */
public final class GrantTable {
    /** No grants at all; shared so the common case allocates nothing per permission query. */
    public static final GrantTable EMPTY = new GrantTable(Map.of());

    private final Map<String, PermissionLevel> byLiteral;

    public GrantTable(Map<String, PermissionLevel> byLiteral) {
        this.byLiteral = Map.copyOf(byLiteral);
    }

    /** Builds the runtime table for {@code grant}, dropping literals the live tree no longer has. */
    public static GrantTable of(PlayerGrant grant, CommandRequirements required) {
        if (grant == null || grant.isEmpty()) {
            return EMPTY;
        }
        Map<String, PermissionLevel> known = new LinkedHashMap<>();
        if (required == null) {
            return EMPTY;
        }
        for (Map.Entry<String, PermissionLevel> entry : grant.granted().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && required.contains(entry.getKey())) {
                known.put(entry.getKey(), entry.getValue());
            }
        }
        return known.isEmpty() ? EMPTY : new GrantTable(known);
    }

    /** The table for a stored player, or {@link #EMPTY} when the ledger holds no record for them. */
    static GrantTable forPlayer(UUID playerId, Map<UUID, PlayerGrant> byPlayer, CommandRequirements required) {
        if (playerId == null) {
            return EMPTY;
        }
        return of(byPlayer.get(playerId), required);
    }

    public boolean isEmpty() {
        return byLiteral.isEmpty();
    }

    /** Whether the whole root command {@code literal} has been opened for this player. */
    public boolean allowsLiteral(String literal) {
        return literal != null && byLiteral.containsKey(literal);
    }

    /**
     * Whether the opened commands satisfy {@code permission}.
     *
     * <p>True only for a {@code HasCommandLevel} permission whose own level an opened command reaches.
     * Everything else — chat, whitelist, entity selectors — is left to the player's real permission
     * set, because this mod only ever adds command access.
     */
    public boolean allows(Permission permission) {
        if (byLiteral.isEmpty() || !(permission instanceof Permission.HasCommandLevel levelCheck)) {
            return false;
        }
        PermissionLevel needed = levelCheck.level();
        for (PermissionLevel opened : byLiteral.values()) {
            if (opened.isEqualOrHigherThan(needed)) {
                return true;
            }
        }
        return false;
    }

    /** The strongest level any opened command reaches, or {@code null} when nothing is open. */
    public PermissionLevel ceiling() {
        PermissionLevel strongest = null;
        for (PermissionLevel opened : byLiteral.values()) {
            strongest = GrantLevels.stronger(strongest, opened);
        }
        return strongest;
    }

    /** Root literals this player currently has opened, for diagnostics. */
    public Map<String, PermissionLevel> literals() {
        return byLiteral;
    }

    /** Convenience for command feedback: a stable, human-sortable listing. */
    public String describe() {
        if (byLiteral.isEmpty()) {
            return "-";
        }
        return String.join(", ", byLiteral.keySet());
    }
}
