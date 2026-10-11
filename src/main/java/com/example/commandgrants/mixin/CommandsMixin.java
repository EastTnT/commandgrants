package com.example.commandgrants.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import com.example.commandgrants.CommandGrantsPacketContext;

/**
 * Records who the command tree being built is for.
 *
 * <p>{@code sendCommands} is the only place the recipient is still known: it builds a
 * {@code ClientboundCommandsPacket} and hands it to the connection without ever naming the player. The
 * packet therefore cannot discover its own recipient, and {@code ClientboundCommandsPacketMixin} —
 * which trims the tree per player — needs exactly that. This mixin publishes the player for the
 * duration of the call, on the calling thread, and clears it again so a later tree built for anything
 * other than a player is passed through untouched.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "sendCommands", at = @At("HEAD"))
    private void commandgrants$setRecipient(ServerPlayer player, CallbackInfo ci) {
        CommandGrantsPacketContext.set(player);
    }

    @Inject(method = "sendCommands", at = @At("RETURN"))
    private void commandgrants$clearRecipient(ServerPlayer player, CallbackInfo ci) {
        CommandGrantsPacketContext.clear();
    }
}
