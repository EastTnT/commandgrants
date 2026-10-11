package com.example.commandgrants;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;

/**
 * Carries the recipient of a command tree from {@code Commands.sendCommands} to the packet that
 * delivers it.
 *
 * <p>Vanilla builds the {@code ClientboundCommandsPacket} from a tree and hands it to the connection
 * without ever recording who it is for, so the one place the recipient is still known is the
 * {@code sendCommands} call itself. That call happens on the server thread, and a dedicated server has
 * several, so the value is kept per thread; it is cleared at the end of the call so nothing can leak
 * from one player's tree build into another's.
 */
public final class CommandGrantsPacketContext {
    private static final ThreadLocal<ServerPlayer> RECIPIENT = new ThreadLocal<>();

    private CommandGrantsPacketContext() {
    }

    /** Runs {@code body} with {@code player} recorded as the recipient of any tree built inside it. */
    public static void run(ServerPlayer player, Runnable body) {
        ServerPlayer previous = RECIPIENT.get();
        RECIPIENT.set(player);
        try {
            body.run();
        } finally {
            if (previous == null) {
                RECIPIENT.remove();
            } else {
                RECIPIENT.set(previous);
            }
        }
    }

    /** @return the player whose command tree is being built on this thread, or {@code null} */
    @Nullable
    public static ServerPlayer recipient() {
        return RECIPIENT.get();
    }

    /** Records {@code player} as the recipient, for use around a single {@code sendCommands} call. */
    public static void set(ServerPlayer player) {
        RECIPIENT.set(player);
    }

    /** Clears the thread's recipient. */
    public static void clear() {
        RECIPIENT.remove();
    }
}
