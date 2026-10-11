package com.example.commandgrants.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;

import com.example.commandgrants.CommandGrantsServer;

/**
 * Makes the server refuse what it never opened, not just hide it.
 *
 * <p>The server parses a chat command against the <em>full</em> dispatcher — the per-player tree in
 * {@code ClientboundCommandsPacket} only ever reaches the client. Without this hook a player could
 * type a command the host never opened and have it run, because widening a permission level to admit
 * one command admits every other command at that level.
 *
 * <p>{@code parseCommand} is the single funnel for both execution and suggestions
 * ({@code handleCustomCommandSuggestions} calls it too), so narrowing the dispatcher here covers what
 * the player can run <em>and</em> what the server will suggest, with one hook. Suggestions answered by
 * the server therefore agree with the tree the client was given.
 *
 * <p>The narrowing is applied to the parse alone: the dispatcher handed in is the live one, and what
 * comes back replaces it for this call only.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    /**
     * {@code ordinal = 0} pins this to the first variable stored in {@code parseCommand}, which is the
     * dispatcher read from the server. There is exactly one such store, but naming the ordinal keeps
     * the hook from drifting onto another one if the method grows.
     */
    @ModifyVariable(method = "parseCommand", at = @At("STORE"), ordinal = 0)
    private CommandDispatcher<CommandSourceStack> commandgrants$narrowDispatcher(
            CommandDispatcher<CommandSourceStack> dispatcher) {
        ServerPlayer self = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (self == null) {
            return dispatcher;
        }
        CommandGrantsServer manager = CommandGrantsServer.of(self);
        if (manager == null) {
            return dispatcher;
        }
        return manager.narrowedDispatcher(self, dispatcher);
    }
}
