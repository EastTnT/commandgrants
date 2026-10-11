package com.example.commandgrants.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.mojang.brigadier.tree.RootCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;

import com.example.commandgrants.CommandGrantsPacketContext;
import com.example.commandgrants.CommandGrantsServer;

/**
 * Trims the command tree to the root commands the host opened for the receiving player.
 *
 * <p>Vanilla builds this tree with {@code child.canUse(source)} in {@code Commands.sendCommands}, and
 * permission is widened for granted commands by level — but one level covers many commands
 * ({@code /tp} and {@code /gamemode} both demand {@code COMMANDS_GAMEMASTER}), so a level cannot
 * express "this one but not that one". The trimmed tree is what the client rebuilds its dispatcher
 * from, so a command left out neither completes nor parses on the client.
 *
 * <p>The recipient is not part of the packet — vanilla hands it to the connection right after building
 * it and never records who it was for — so it is taken from {@link CommandGrantsPacketContext}, which
 * the {@code Commands} mixin fills in around {@code sendCommands}. Without it (a tree built for
 * anything other than a player) the root is passed through untouched.
 *
 * <p>This governs what the player can <em>see</em>. What the server <em>accepts</em> is decided
 * separately, because the server parses against the full dispatcher; see
 * {@code ServerGamePacketListenerImplMixin}.
 */
@Mixin(ClientboundCommandsPacket.class)
public abstract class ClientboundCommandsPacketMixin {
    /**
     * Replaces the root before the packet serialises it.
     *
     * <p>At {@code HEAD}, because the constructor is {@code void}: the packet flattens the tree into
     * its {@code entries} field part-way through, so a {@code RETURN} hook would rewrite an argument
     * nothing reads again. Only the public {@code (RootCommandNode, NodeInspector)} constructor has a
     * tree argument; the private decode constructor the client uses does not, so this matches once.
     */
    @SuppressWarnings("unchecked")
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 1)
    private static RootCommandNode<?> commandgrants$filterTree(RootCommandNode<?> root) {
        ServerPlayer player = CommandGrantsPacketContext.recipient();
        if (player == null || root == null) {
            return root;
        }
        CommandGrantsServer manager = CommandGrantsServer.of(player);
        if (manager == null) {
            return root;
        }
        // The packet is generic over the source type, but every tree it carries is built from the
        // server's CommandSourceStack dispatcher, so this is the only instantiation that occurs.
        return manager.filteredRoot(player, (RootCommandNode<CommandSourceStack>) root);
    }
}
