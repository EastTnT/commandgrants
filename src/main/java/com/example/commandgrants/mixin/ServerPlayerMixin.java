package com.example.commandgrants.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.commandgrants.CommandGrantsServer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Widens the single permission lookup vanilla performs for a player.
 *
 * <p>{@code ServerPlayer.permissions()} is the one place a player's permissions are resolved, and
 * {@code ServerPlayer.createCommandSourceStack()} copies its result straight into the
 * {@code CommandSourceStack} that command execution and the dispatcher's {@code canUse} walk read.
 * Widening it here therefore covers both halves of the feature with one hook, and cannot be bypassed
 * by a caller that builds its own source stack from {@code this.permissions()}.
 *
 * <p>The wide set only ever <em>adds</em>: {@link GrantedPermissionSet} asks the vanilla set first, so
 * an operator is never reduced, and a player with no grant record gets the untouched vanilla instance
 * back. What it adds is bounded by what the host opened — see {@code GrantTable#allows}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "permissions", at = @At("RETURN"), cancellable = true)
    private void commandgrants$widenPermissions(CallbackInfoReturnable<PermissionSet> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        // The manager answers null whenever there is nothing to add, which leaves vanilla's own
        // instance in place — the common case, and the one that must stay allocation-free.
        PermissionSet widened = CommandGrantsServer.widenPermissions(self, cir.getReturnValue());
        if (widened != null) {
            cir.setReturnValue(widened);
        }
    }
}
