package com.example.commandgrants;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;

/**
 * A {@link PermissionSet} that wraps a player's real vanilla permissions and additionally honours the
 * commands the host opened for that player.
 *
 * <p>Installed by {@code ServerPlayerMixin} on {@code ServerPlayer.permissions()}, which is the
 * single place {@code ServerPlayer.createCommandSourceStack()} reads permissions from — so one
 * wrapper covers command execution <em>and</em> the {@code Commands.fillUsableCommands} tree walk.
 *
 * <p>It only ever <em>widens</em>: the base set is asked first, so an operator's or a server
 * console's permissions are never reduced, and a player with no grants gets exactly vanilla
 * behaviour.
 */
public final class GrantedPermissionSet implements PermissionSet {
    private final PermissionSet base;
    private final GrantTable grants;

    public GrantedPermissionSet(PermissionSet base, GrantTable grants) {
        this.base = base;
        this.grants = grants == null ? GrantTable.EMPTY : grants;
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return this.base.hasPermission(permission) || this.grants.allows(permission);
    }

    /** The underlying vanilla set, exposed for tests and diagnostics. */
    public PermissionSet base() {
        return this.base;
    }

    public GrantTable grants() {
        return this.grants;
    }
}
