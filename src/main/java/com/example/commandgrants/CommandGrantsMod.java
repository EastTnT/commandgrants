package com.example.commandgrants;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Registers the networking payloads, the {@code /commandgrants} command,
 * the client receivers and the server lifecycle hooks that keep the grant table in sync.
 */
public final class CommandGrantsMod implements ModInitializer {
    public static final String MOD_ID = "commandgrants";

    /** Shared logger; named after the mod so log lines are greppable. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Pretty-printing so the world file stays hand-editable. */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * Processes alive on this JVM. Every payload handler looks the player up in here instead of
     * resolving the server from the payload context, because the client and the integrated server
     * share a single JVM.
     */
    public static final List<CommandGrantsServer> SERVERS = new CopyOnWriteArrayList<>();

    @Override
    public void onInitialize() {
        CommandGrantsNetwork.init();
        CommandGrantsServer.registerPermissionNodes();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            CommandGrantsServer manager = CommandGrantsServer.open(server);
            GrantCommand.register(server.getCommands().getDispatcher());
            // The connecting player already received a command tree built before the dispatcher had
            // /commandgrants in it, so push a corrected tree as soon as the server is up.
            manager.pulse();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            CommandGrantsServer manager = CommandGrantsServer.of(server);
            if (manager != null) {
                // The tree this player was sent during login predates their grants, so re-send the
                // ledger and the tree together; the client gets the catalogue for its editor too.
                // The player is passed explicitly because this event fires while they are still being
                // placed, before the player list carries them.
                manager.syncTo(handler.getPlayer());
                server.getCommands().sendCommands(handler.getPlayer());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(CommandGrantsServer::close);

        LOGGER.info("[CommandGrants] initialized");
    }
}
