package com.example.commandgrants.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.example.commandgrants.CommandGrantsNetwork;

/**
 * The grant editor, reached from the pause menu's multiplayer options.
 *
 * <p>One player is edited at a time, chosen with the cycler in the header, and one command is toggled
 * per checkbox. The catalogue is paged rather than scrolled: a vanilla server exposes a few dozen
 * grantable commands at most, and paging keeps every entry reachable at the smallest GUI scale.
 *
 * <p>The screen owns no authoritative state. Every toggle is a payload, the server validates it, and the
 * snapshot it answers with is what the next rebuild renders — so a rejected edit simply does not appear,
 * and there is no local copy that can drift.
 */
@Environment(EnvType.CLIENT)
public final class GrantEditorScreen extends Screen {
    /**
     * The most command rows a page will hold. Public so tests can assert a full page without guessing.
     *
     * <p>It is a ceiling, not a size: {@link #rowsPerPage()} lowers it when the window is too short for
     * a full page, because a grid that does not fit is drawn on top of the footer.
     */
    public static final int MAX_PER_PAGE = 8;

    /**
     * The header, the footer, the status line, the player cycler and the page controls together take
     * roughly this much vertical room, at any window size: each is a fixed-height widget.
     */
    private static final int FURNITURE_HEIGHT = 148;

    /** One command row: a checkbox plus the grid's spacing. */
    private static final int ROW_HEIGHT = 22;

    /** A window narrow enough to hide rows would leave the editor useless, so it never drops below this. */
    private static final int MIN_PER_PAGE = 3;

    /** Wide enough for a command name plus its level, and still readable at GUI scale 1. */
    private static final int OPTION_WIDTH = 230;

    /** Offered presets, as ids the server understands; "custom" is a state, never a choice. */
    private static final List<String> PRESETS = List.of("none", "moderator", "gamemaster");

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    private UUID selectedPlayer;
    private int page;
    private String preset = PRESETS.get(0);

    /** Set whenever the server's snapshot changed, so {@link #tick()} can rebuild against it. */
    private boolean needsRebuild;

    /** The page size the current widgets were built for, so a resize can tell it has to re-page. */
    private int perPage;

    public GrantEditorScreen(Screen parent) {
        super(Component.translatable("commandgrants.editor.title"));
        this.parent = parent;
    }

    public static GrantEditorScreen open(Screen parent) {
        GrantEditorScreen screen = new GrantEditorScreen(parent);
        Minecraft.getInstance().setScreenAndShow(screen);
        return screen;
    }

    /**
     * The player the rows currently write to, or {@code null} before anything was selected.
     *
     * <p>Public so a test can tell "the row wrote to somebody else" apart from "the row wrote to nobody",
     * which look identical from the snapshot alone.
     */
    public UUID selectedPlayer() {
        return this.selectedPlayer;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        this.page = 0;
        build();
    }

    /** Builds the whole screen from the current snapshot; safe to call repeatedly. */
    private void build() {
        this.needsRebuild = false;
        List<CommandGrantsNetwork.Player> players = editablePlayers();
        if (this.selectedPlayer == null
                || players.stream().noneMatch(player -> player.playerId().equals(this.selectedPlayer))) {
            this.selectedPlayer = players.isEmpty() ? null : players.get(0).playerId();
        }
        List<CommandGrantsNetwork.Entry> commands = CommandGrantsClient.commands();
        int perPage = rowsPerPage();
        this.perPage = perPage;
        int pageCount = Math.max(1, (commands.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pageCount - 1);

        this.layout.removeChildren();
        this.layout.addTitleHeader(this.title, this.font);
        LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(6));
        content.defaultCellSetting().alignHorizontallyCenter();

        content.addChild(new StringWidget(statusLine(), this.font));
        content.addChild(playerCycler(players));
        if (players.isEmpty()) {
            content.addChild(new StringWidget(Component.translatable("commandgrants.editor.noPlayers"), this.font));
        } else if (commands.isEmpty()) {
            content.addChild(new StringWidget(Component.translatable("commandgrants.editor.noCommands"), this.font));
        } else {
            content.addChild(commandGrid(commands));
            content.addChild(pageControls(pageCount));
        }

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(presetCycler(players));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(80).build());

        // Every widget must be added after the layout is populated but before it is arranged, and
        // visitWidgets is the only way to reach the children a layout owns.
        this.layout.visitWidgets(this::addRenderableWidget);
        arrange();
    }

    /**
     * Places the header, the content and the footer inside the screen.
     *
     * <p>A layout does not place anything by itself: its children sit at the origin with their own
     * sizes until someone asks it to arrange them. Vanilla screens do this in
     * {@code repositionElements}, which the screen calls once after {@code init} and again on every
     * resize; skipping it is what leaves every widget stacked in the top-left corner.
     */
    private void arrange() {
        this.layout.arrangeElements();
    }

    @Override
    protected void repositionElements() {
        // A resize is not only a placement problem: the page size follows the window height, so a window
        // that grew or shrank has to be re-paged rather than merely re-arranged. Re-arranging a page that
        // no longer fits is exactly the "rows drawn over the footer" bug this screen already had once.
        if (this.perPage != rowsPerPage()) {
            rebuildWidgets();
            return;
        }
        arrange();
    }

    /** One checkbox per command, labelled with the level the command demands. */
    private GridLayout commandGrid(List<CommandGrantsNetwork.Entry> commands) {
        GridLayout grid = new GridLayout().spacing(4);
        GridLayout.RowHelper rows = grid.createRowHelper(1);
        int perPage = rowsPerPage();
        int start = this.page * perPage;
        int end = Math.min(commands.size(), start + perPage);
        for (CommandGrantsNetwork.Entry entry : commands.subList(start, end)) {
            // One argument only: addChild(child, n) makes the child span n CELLS, so passing a pixel
            // width there told the grid every row was 230 cells tall and inflated it to thousands of
            // pixels. The row's pixel width belongs to the checkbox itself, via maxWidth.
            rows.addChild(commandCheckbox(entry));
        }
        return grid;
    }

    /**
     * How many command rows a page holds at this window size; tests read it to size their expectations.
     *
     * <p>Everything the page does not hold is fixed height, so the answer follows from the screen
     * height alone. A window shorter than {@link #MIN_PER_PAGE} rows' worth still draws that many and
     * lets the grid run off the bottom, which is the least bad way to lose a window this small.
     */
    public int rowsPerPage() {
        int room = this.height - FURNITURE_HEIGHT;
        return Math.max(MIN_PER_PAGE, Math.min(MAX_PER_PAGE, room / ROW_HEIGHT));
    }

    private Checkbox commandCheckbox(CommandGrantsNetwork.Entry entry) {
        UUID playerId = this.selectedPlayer;
        List<String> names = names(entry);
        // Any name being open means the command is open, because they are all one command: the server
        // treats them as one and, for the same reason, this row has to as well.
        boolean open = playerId != null && names.stream()
                .anyMatch(name -> CommandGrantsClient.isGranted(playerId, name));
        return Checkbox.builder(commandLabel(entry), this.font)
                .selected(open)
                .maxWidth(OPTION_WIDTH)
                // The level is fixed by the server's catalogue, so opening a grant always hands out
                // exactly what the command demands; only on/off belongs to the editor.
                .onValueChange((checkbox, wanted) -> {
                    if (playerId == null || !entry.grantable()) {
                        return;
                    }
                    for (String name : names) {
                        CommandGrantsClient.toggle(playerId, name, wanted);
                    }
                    this.needsRebuild = true;
                })
                .build();
    }

    /**
     * Every name the row's command answers to, its own first: {@code [teleport, tp]}.
     *
     * <p>Public so tests can assert what a row says it opens without re-deriving the grouping.
     */
    public static List<String> names(CommandGrantsNetwork.Entry entry) {
        List<String> names = new ArrayList<>();
        names.add(entry.literal());
        names.addAll(entry.aliases());
        return names;
    }

    /** "gamemasters required" while a command cannot be offered, because that is what limits the list. */
    private Component statusLine() {
        if (CommandGrantsClient.disabled()) {
            return Component.translatable("commandgrants.editor.disabled");
        }
        return Component.translatable("commandgrants.editor.enabled");
    }

    private CycleButton<CommandGrantsNetwork.Player> playerCycler(List<CommandGrantsNetwork.Player> players) {
        List<CommandGrantsNetwork.Player> values =
                players.isEmpty() ? List.of(CommandGrantsNetwork.Player.EMPTY) : players;
        // The cycler needs a starting value, and the builder takes it by value rather than by
        // supplier, so the selected player has to be resolved to its record first.
        CommandGrantsNetwork.Player current = values.stream()
                .filter(player -> player.playerId().equals(this.selectedPlayer))
                .findFirst()
                .orElse(values.get(0));
        CycleButton<CommandGrantsNetwork.Player> cycler = CycleButton
                .builder(player -> Component.literal(player.name()), current)
                .withValues(values)
                .create(Component.translatable("commandgrants.editor.player"), (button, value) -> {
                    this.selectedPlayer = value.playerId();
                    this.needsRebuild = true;
                });
        cycler.active = !players.isEmpty();
        cycler.setWidth(OPTION_WIDTH);
        return cycler;
    }

    private CycleButton<String> presetCycler(List<CommandGrantsNetwork.Player> players) {
        CycleButton<String> cycler = CycleButton.<String>builder(
                        id -> Component.translatable("commandgrants.editor.preset." + id),
                        this.preset)
                .withValues(PRESETS)
                .create(Component.translatable("commandgrants.editor.applyPreset"), (button, id) -> {
                    this.preset = id;
                    UUID playerId = this.selectedPlayer;
                    if (playerId != null && !players.isEmpty()) {
                        CommandGrantsClient.preset(playerId, id);
                        this.needsRebuild = true;
                    }
                });
        cycler.active = !players.isEmpty();
        cycler.setWidth(150);
        return cycler;
    }

    private LinearLayout pageControls(int pageCount) {
        LinearLayout controls = LinearLayout.horizontal().spacing(8);

        Button previous = Button.builder(Component.translatable("commandgrants.editor.previous"), button -> {
            this.page = Math.max(0, this.page - 1);
            this.needsRebuild = true;
        }).width(60).build();
        previous.active = this.page > 0;

        Button next = Button.builder(Component.translatable("commandgrants.editor.next"), button -> {
            this.page = Math.min(pageCount - 1, this.page + 1);
            this.needsRebuild = true;
        }).width(60).build();
        next.active = this.page < pageCount - 1;

        controls.addChild(previous);
        controls.addChild(new StringWidget(Component.translatable("commandgrants.editor.page",
                this.page + 1, pageCount), this.font));
        controls.addChild(next);
        return controls;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The players a grant may be aimed at: everyone the server sent a record for, alphabetically.
     *
     * <p>Only stored records are listed — the editor never invents a player, because a grant needs a
     * UUID that survives a reconnect, and the server is the only side that knows one.
     */
    private static List<CommandGrantsNetwork.Player> editablePlayers() {
        List<CommandGrantsNetwork.Player> players = new ArrayList<>(CommandGrantsClient.players());
        players.removeIf(player -> player.playerId().equals(CommandGrantsNetwork.Player.EMPTY.playerId()));
        players.sort(Comparator.comparing(CommandGrantsNetwork.Player::name, String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    /**
     * "tp (gamemasters)"; the level is in the label because it decides what a grant is worth.
     *
     * <p>A command with two names shows both, because the host types one of them: the row that says
     * only "teleport" is the reason a host can believe they opened {@code /tp} and be wrong.
     */
    private static Component commandLabel(CommandGrantsNetwork.Entry entry) {
        Component label = Component.literal(String.join(" / ", names(entry)))
                .append(Component.literal(" (" + entry.required().getSerializedName() + ")"));
        return entry.grantable() ? label : label.copy().withStyle(style -> style.withColor(0x808080));
    }

    private void rebuild() {
        this.clearWidgets();
        build();
    }

    /** Called from the payload thread hop when a new snapshot arrived; rebuilds on the next tick. */
    public void markStale() {
        this.needsRebuild = true;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void tick() {
        if (this.needsRebuild) {
            rebuild();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
