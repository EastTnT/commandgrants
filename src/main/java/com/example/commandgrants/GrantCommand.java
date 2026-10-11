package com.example.commandgrants;

import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /commandgrants} — the scriptable face of the same ledger the LAN menu edits.
 *
 * <p>Everything here is a thin wrapper over {@link CommandGrantsServer}. The command exists so a
 * dedicated server can be administered from its console or by an operator without a GUI, and so the
 * LAN menu is not the only way to change a grant.
 *
 * <p>The whole command is gated at {@code OWNERS}, and every subcommand re-checks
 * {@link CommandGrantsServer#mayEdit} against the acting player. Two gates, because a level gate alone
 * would let any owner use the command even on an integrated server, where only the host should.
 *
 * <p>Commands are named rather than completed, because the whole point of the command is to work from a
 * console and for players who are not connected: {@code /commandgrants open Alex tp} opens {@code /tp}
 * for Alex, whether or not Alex is online. The price is that names have to be spelled correctly and the
 * command depends on the catalogue, which is why {@code /commandgrants list} prints it.
 */
public final class GrantCommand {
    /** Root literal, kept short enough to be typed and unique enough not to collide. */
    public static final String ROOT = "commandgrants";

    private static final SuggestionProvider<CommandSourceStack> COMMAND_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(manager(context) == null
                    ? List.of()
                    : manager(context).index().grantable(), builder);

    private static final SuggestionProvider<CommandSourceStack> PRESET_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    List.of("none", "moderator", "gamemaster"), builder);

    /** Online players only: a grant for someone else needs them connected for {@code EntityArgument}. */
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    context.getSource().getServer() == null
                            ? List.of()
                            : context.getSource().getServer().getPlayerList().getPlayers().stream()
                                    .map(player -> player.getName().getString())
                                    .toList(),
                    builder);

    private GrantCommand() {
    }

    private static CommandGrantsServer manager(CommandContext<CommandSourceStack> context) {
        return CommandGrantsServer.of(context.getSource().getServer());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.literal("list")
                        .executes(GrantCommand::list))
                .then(Commands.literal("catalog")
                        .executes(GrantCommand::catalog))
                .then(Commands.literal("open")
                        .then(Commands.argument("player", EntityArgument.player())
                                .suggests(ONLINE_PLAYERS)
                                .then(Commands.argument("command", StringArgumentType.word())
                                        .suggests(COMMAND_NAMES)
                                        .executes(context -> change(context, true)))))
                .then(Commands.literal("close")
                        .then(Commands.argument("player", EntityArgument.player())
                                .suggests(ONLINE_PLAYERS)
                                .then(Commands.argument("command", StringArgumentType.word())
                                        .suggests(COMMAND_NAMES)
                                        .executes(context -> change(context, false)))))
                .then(Commands.literal("preset")
                        .then(Commands.argument("player", EntityArgument.player())
                                .suggests(ONLINE_PLAYERS)
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(PRESET_NAMES)
                                        .executes(GrantCommand::preset))))
                .then(Commands.literal("enable")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(GrantCommand::setEnabled)))
                .then(Commands.literal("clear")
                        .executes(GrantCommand::clear)));
    }

    private static UUID targetId(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        return player == null ? null : player.getUUID();
    }

    private static String nameArgument(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "preset");
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer actor = context.getSource().getPlayerOrException();
        CommandGrantsServer manager = requireEditable(context, actor);
        if (manager == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable(manager.storage().disabled()
                ? "commandgrants.feedback.state.disabled"
                : "commandgrants.feedback.state.enabled"), false);
        // Records come straight off the ledger, so offline players are listed too.
        for (PlayerGrant grant : manager.storage().players(manager.index())) {
            context.getSource().sendSuccess(() -> Component.translatable("commandgrants.feedback.record",
                    grant.name(), manager.table(grant.playerId()).describe()), false);
        }
        return 1;
    }

    private static int catalog(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer actor = context.getSource().getPlayerOrException();
        CommandGrantsServer manager = requireEditable(context, actor);
        if (manager == null) {
            return 0;
        }
        int shown = 0;
        for (CommandIndex.Entry entry : manager.index().entries().values()) {
            if (!entry.grantable()) {
                continue;
            }
            shown++;
            context.getSource().sendSuccess(() -> Component.translatable("commandgrants.feedback.catalogEntry",
                    entry.literal(), entry.required().getSerializedName()), false);
        }
        int total = shown;
        context.getSource().sendSuccess(() -> Component.translatable("commandgrants.feedback.catalog", total),
                false);
        return 1;
    }

    private static int change(CommandContext<CommandSourceStack> context, boolean open)
            throws CommandSyntaxException {
        ServerPlayer actor = context.getSource().getPlayerOrException();
        CommandGrantsServer manager = requireEditable(context, actor);
        if (manager == null) {
            return 0;
        }
        String literal = StringArgumentType.getString(context, "command");
        CommandIndex.Entry entry = manager.index().entry(literal);
        if (entry == null) {
            context.getSource().sendFailure(Component.translatable("commandgrants.failure.unknown", literal));
            return 0;
        }
        if (open && !entry.grantable()) {
            context.getSource()
                    .sendFailure(Component.translatable("commandgrants.failure.notGrantable", literal));
            return 0;
        }
        manager.edit(actor, targetId(context), literal, entry.required(), open);
        return 1;
    }

    private static int preset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer actor = context.getSource().getPlayerOrException();
        CommandGrantsServer manager = requireEditable(context, actor);
        if (manager == null) {
            return 0;
        }
        GrantPresets.Kind kind = GrantPresets.Kind.byId(nameArgument(context));
        if (kind == GrantPresets.Kind.CUSTOM) {
            context.getSource().sendFailure(Component.translatable("commandgrants.failure.unknownPreset"));
            return 0;
        }
        manager.applyPreset(actor, targetId(context), kind);
        return 1;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer actor = context.getSource().getPlayerOrException();
        CommandGrantsServer manager = requireEditable(context, actor);
        if (manager == null) {
            return 0;
        }
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        manager.setDisabled(!enabled);
        context.getSource().sendSuccess(() -> Component.translatable(enabled
                ? "commandgrants.feedback.state.enabled"
                : "commandgrants.feedback.state.disabled"), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer actor = context.getSource().getPlayerOrException();
        CommandGrantsServer manager = requireEditable(context, actor);
        if (manager == null) {
            return 0;
        }
        manager.clear();
        context.getSource().sendSuccess(() -> Component.translatable("commandgrants.feedback.cleared"), false);
        return 1;
    }

    /**
     * The manager, or {@code null} after reporting why not.
     *
     * <p>A helper rather than a Brigadier requirement so the failure message can say which of the two
     * reasons applied.
     */
    private static CommandGrantsServer requireEditable(CommandContext<CommandSourceStack> context,
                                                       ServerPlayer actor) {
        CommandGrantsServer manager = manager(context);
        if (manager == null) {
            context.getSource().sendFailure(Component.translatable("commandgrants.failure.unavailable"));
            return null;
        }
        if (!manager.mayEdit(actor)) {
            context.getSource().sendFailure(Component.translatable("commandgrants.failure.notHost"));
            return null;
        }
        return manager;
    }
}
