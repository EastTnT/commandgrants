package com.example.commandgrants.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.commandgrants.CommandGrantsMod;
import com.example.commandgrants.CommandGrantsNetwork;
import com.example.commandgrants.CommandGrantsServer;
import com.example.commandgrants.CommandIndex;
import com.example.commandgrants.GrantAccess;
import com.example.commandgrants.GrantTable;
import com.example.commandgrants.GrantedPermissionSet;
import com.example.commandgrants.client.CommandGrantsClient;
import com.example.commandgrants.client.GrantEditorScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.client.gametest.util.WindowHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * In-game checks for the parts that only exist at runtime: the LAN sharing screen, the command tree
 * the client is sent, and the gate the server parses chat commands against.
 *
 * <p>Everything runs against a real client on a real integrated server with cheats off, which is the
 * configuration this mod was written for. Run it with {@code gradlew runClientGameTest}.
 *
 * <p>Every wait polls a predicate rather than calling a blocking helper: the game only advances while
 * it is ticked, and this thread is not the one ticking it, so a blocking wait never returns.
 */
public final class CommandGrantsClientGameTest implements FabricClientGameTest {
    /** A player who never joins: the ledger is keyed by UUID, so a synthetic one is enough. */
    private static final UUID GUEST = new UUID(0x0C0FFEEL, 0x5EEDL);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> {
                    settings.setAllowCommands(false);
                    settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                })
                .create()) {
            waitFor(context, "the world to load",
                    minecraft -> minecraft.level != null && minecraft.player != null);
            CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: world is up, {}", describe(context));

            assertMultiplayerOptionsOffersTheEditor(context);
            assertGrantEditorOpens(context);
            assertEditorShowsCommands(context);
            assertEditorSurvivesResize(context);
            assertTogglingACommandRowReachesTheLedger(context);
            // Last, because it leaves the editor to check the dispatcher the server actually parses with.
            assertTreeAndGateRespectTheLedger(context);
        }
    }

    /** The button has to be there, and it has to be usable by the host of a cheats-disabled world. */
    private static void assertMultiplayerOptionsOffersTheEditor(ClientGameTestContext context) {
        openMultiplayerOptions(context);
        context.runOnClient(minecraft -> {
            if (!(minecraft.gui.screen() instanceof MultiplayerOptionsScreen screen)) {
                throw new AssertionError("The LAN sharing screen closed itself");
            }
            String label = Component.translatable("commandgrants.editor.open").getString();
            Button button = button(screen, label);
            if (button == null) {
                throw new AssertionError("The LAN sharing screen has no \"" + label + "\" button; it has "
                        + describeButtons(screen));
            }
            if (!button.active) {
                throw new AssertionError("The grant editor button is disabled for the host of a "
                        + "cheats-disabled world");
            }
        });
    }

    private static void assertGrantEditorOpens(ClientGameTestContext context) {
        context.runOnClient(minecraft -> {
            if (!(minecraft.gui.screen() instanceof MultiplayerOptionsScreen screen)) {
                throw new AssertionError("The LAN sharing screen closed itself");
            }
            Button button = button(screen,
                    Component.translatable("commandgrants.editor.open").getString());
            if (button == null) {
                throw new AssertionError("The grant editor button vanished between screens");
            }
            button.onPress(new InputWithModifiers() {
                @Override
                public int input() {
                    return 0;
                }

                @Override
                public int modifiers() {
                    return 0;
                }
            });
        });
        waitForScreen(context, GrantEditorScreen.class);
    }

    /**
     * The editor has to draw command rows, not just open.
     *
     * <p>Until this check existed the suite only ever asked the <em>server</em> what its catalogue held,
     * so a snapshot that never reached the client — or reached it with an empty roster — left every
     * assertion green while the host stared at an empty screen. What the editor would draw is therefore
     * asserted here, on the widgets the screen really owns.
     *
     * <p>Only the page on screen is drawn, and a page holds the rows this window has room for
     * ({@link GrantEditorScreen#rowsPerPage()}) in alphabetical order, so this asserts that the rows
     * come from the client's own catalogue and that the page is full — naming a row like {@code time}
     * would only be checking the alphabet.
     */
    private static void assertEditorShowsCommands(ClientGameTestContext context) {
        context.runOnClient(minecraft -> {
            if (!(minecraft.gui.screen() instanceof GrantEditorScreen editor)) {
                throw new AssertionError("The grant editor is not the open screen");
            }
            List<String> catalogue = CommandGrantsClient.commands().stream()
                    .map(CommandGrantsNetwork.Entry::literal)
                    .toList();
            if (catalogue.isEmpty()) {
                throw new AssertionError("The editor received no command catalogue at all, so it has"
                        + " nothing to offer; it drew " + describeButtons(editor));
            }
            if (CommandGrantsClient.players().isEmpty()) {
                throw new AssertionError("The editor received no roster, so no player can be selected"
                        + " and every checkbox stays disabled; it drew " + describeButtons(editor));
            }
            List<String> rows = checkboxLabels(editor);
            if (rows.isEmpty()) {
                throw new AssertionError("The grant editor drew no command checkboxes at all for a"
                        + " catalogue of " + catalogue.size() + " commands; it drew "
                        + describeButtons(editor));
            }
            List<String> strays = rows.stream().filter(row -> !catalogue.contains(row)).toList();
            if (!strays.isEmpty()) {
                throw new AssertionError("The editor drew rows that are not in its catalogue: " + strays);
            }
            // A page is full for the window it is drawn in — down to the rows that window can hold. An
            // off-by-one in the page arithmetic shows up here as a short first page; a page that ignored
            // the window height shows up as more rows than it has room for.
            int perPage = editor.rowsPerPage();
            if (perPage < 1 || perPage > GrantEditorScreen.MAX_PER_PAGE) {
                throw new AssertionError("The editor sized a page at " + perPage + " rows, outside the 1.."
                        + GrantEditorScreen.MAX_PER_PAGE + " it promises");
            }
            int expected = Math.min(perPage, catalogue.size());
            if (rows.size() != expected) {
                throw new AssertionError("The first page drew " + rows.size() + " rows for a catalogue of "
                        + catalogue.size() + " commands, expected " + expected + ": " + rows);
            }
            assertWidgetsAreLaidOut(editor);
        });
    }

    /**
     * The editor has to re-place itself when the window changes size, not only when it is first built.
     *
     * <p>Placing the widgets once in {@code init} is what every vanilla screen does, but vanilla also
     * does it from {@code repositionElements}, which the screen calls on resize. A screen that only
     * arranges on open looks correct until the window is resized, then collapses to the origin — so the
     * geometry this checks is the same geometry the arrange assertion checks, just at a size the screen
     * did not open with.
     *
     * <p>The window is resized through the game test API's own hook, because that is what a real resize
     * does: it changes the window, recomputes the GUI scale and calls back into the screen. Calling
     * {@code Screen.resize} with a size the window never had instead hands the layout a coordinate space
     * that does not exist, which is a failure of the test rather than of the screen.
     *
     * <p>Sizes are given in the window's own pixels and scaled down, because this API's window is wider
     * than the game's default: a 300x200 window is already two thirds of the GUI-scaled screen, so it
     * has to work out the size the screen will actually take before it can expect a page for it.
     */
    private static void assertEditorSurvivesResize(ClientGameTestContext context) {
        // The window can only be reached from the client thread, so the hook is fetched there and kept.
        WindowHooks[] held = new WindowHooks[1];
        context.runOnClient(minecraft -> held[0] = windowHooks());
        WindowHooks window = held[0];
        // Short enough to drop a row, the size the last assertion saw, and exactly the current height.
        int[][] sizes = {{600, 240}, {600, 480}, {854, 480}};
        try {
            for (int[] size : sizes) {
                context.runOnClient(minecraft -> window.fabric_resize(size[0], size[1]));
                String where = size[0] + "x" + size[1];
                // The resize is delivered through the client's own resize path, which runs a tick later.
                waitFor(context, "the window to become " + where, minecraft ->
                        minecraft.getWindow().getWidth() == size[0]
                                && minecraft.getWindow().getHeight() == size[1]
                                && minecraft.gui.screen() instanceof GrantEditorScreen);
                context.runOnClient(minecraft -> {
                    if (!(minecraft.gui.screen() instanceof GrantEditorScreen editor)) {
                        throw new AssertionError("Resizing to " + where + " lost the grant editor");
                    }
                    int catalogue = CommandGrantsClient.commands().size();
                    List<String> rows = checkboxLabels(editor);
                    // The page follows the window height, so the expectation is read from the screen
                    // rather than remembered: what is being checked is that the rows agree with the page
                    // size the screen is reporting, which is what a resize has to recompute.
                    int expected = Math.min(editor.rowsPerPage(), catalogue);
                    if (rows.size() != expected) {
                        throw new AssertionError("After resizing to " + where + ", where the screen is "
                                + editor.width + "x" + editor.height + ", the editor drew " + rows.size()
                                + " rows for a " + editor.rowsPerPage() + "-row page, expected " + expected
                                + ": " + describeGeometry(editor));
                    }
                    assertWidgetsAreLaidOut(editor);
                });
            }
        } finally {
            // Leave the window as it was found; the screen after this one should not inherit a test size.
            context.runOnClient(minecraft -> window.fabric_resetSize());
        }
    }

    /**
     * Clicking a command row has to reach the ledger, twice in a row and through the real widgets.
     *
     * <p>This is the check the suite was missing when a player reported "the second command I ticked
     * still cannot be used": the assertions all drove {@code GrantAccess} and {@code CommandGrantsServer}
     * directly, so the editor itself — the only surface a host actually touches — was only ever checked
     * for what it <em>drew</em>. Every earlier check passed while the row's own click did nothing.
     *
     * <p>The press is {@code Checkbox#onPress}, which is what the mouse routing calls once it has chosen
     * the widget, so the test drives exactly the code a click does without re-deriving hit rectangles.
     * Two rows are pressed in the same session on purpose: a screen that rebuilds itself from the last
     * snapshot when a toggle is sent can hand the next click to a widget it has already replaced, which
     * makes the first edit stick and the second vanish — the reported symptom precisely.
     */
    private static void assertTogglingACommandRowReachesTheLedger(ClientGameTestContext context) {
        UUID host = context.computeOnClient(minecraft -> minecraft.player.getUUID());
        context.runOnClient(minecraft -> {
            if (!(minecraft.gui.screen() instanceof GrantEditorScreen editor)) {
                throw new AssertionError("The grant editor is not the open screen");
            }
            List<Checkbox> boxes = widgets(editor, Checkbox.class)
                    .filter(box -> box.active && box.visible)
                    .collect(Collectors.toList());
            if (boxes.size() < 2) {
                throw new AssertionError("The editor drew " + boxes.size() + " usable command rows, so a"
                        + " click cannot be tested; it drew " + describeButtons(editor));
            }
            PRESSED.clear();
            int pressed = 0;
            for (Checkbox box : boxes) {
                String literal = literalOf(box);
                if (literal == null) {
                    throw new AssertionError("A command row says \"" + box.getMessage().getString()
                            + "\", which does not name a command in the catalogue: "
                            + CommandGrantsClient.commands());
                }
                // A test that pressed a row the editor already refuses would prove nothing about the
                // rows it accepts: the failure would be reported against a command the mod never
                // claimed to offer. Only rows the snapshot calls grantable take part.
                if (!grantable(literal)) {
                    continue;
                }
                PRESSED.add(literal);
                box.onPress(PRESS);
                if (++pressed == 2) {
                    break;
                }
            }
            if (pressed < 2) {
                throw new AssertionError("Only " + pressed + " of the editor's " + boxes.size()
                        + " command rows are offered for granting, so pressing two of them cannot be"
                        + " tested; the catalogue reads " + CommandGrantsClient.commands());
            }
        });
        // A click is answered, not applied: the ledger changes on the server thread and the snapshot only
        // afterwards, so the assertion waits for the round trip instead of racing it.
        waitFor(context, "the pressed rows to come back granted",
                minecraft -> PRESSED.stream()
                        .allMatch(literal -> CommandGrantsClient.isGranted(host, literal)));

        context.runOnClient(minecraft -> {
            CommandGrantsServer manager = CommandGrantsServer.of(minecraft.getSingleplayerServer());
            if (manager == null) {
                throw new AssertionError("The integrated server has no grant manager");
            }
            for (String literal : PRESSED) {
                if (!manager.table(host).literals().containsKey(literal)) {
                    throw new AssertionError("The client renders \"" + literal + "\" as open but the"
                            + " server's ledger does not hold it: the editor drew a grant the server never"
                            + " accepted. Ledger=" + manager.table(host).literals()
                            + " snapshot=" + grantsOf(host));
                }
            }
            CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: two rows pressed in one session, and"
                    + " the ledger holds both: {}", manager.table(host).literals().keySet());
        });
    }

    /** The command a pressed row names: its label is "name / alias (level)", so the name is the head. */
    private static String literalOf(Checkbox box) {
        String label = box.getMessage().getString();
        int end = label.indexOf(" (");
        String names = end < 0 ? label : label.substring(0, end);
        for (String literal : CommandGrantsClient.commands().stream()
                .map(CommandGrantsNetwork.Entry::literal)
                .toList()) {
            for (String name : names.split(" / ")) {
                if (name.trim().equals(literal)) {
                    return literal;
                }
            }
        }
        return null;
    }

    /** Whether the client's snapshot would let a host tick {@code literal} right now. */
    private static boolean grantable(String literal) {
        return CommandGrantsClient.commands().stream()
                .anyMatch(entry -> entry.literal().equals(literal) && entry.grantable());
    }

    private static String grantsOf(UUID playerId) {
        CommandGrantsNetwork.Player player = CommandGrantsClient.player(playerId);
        return player == null ? "no record" : player.granted().toString();
    }

    /** One press with no modifiers, since that is what a plain mouse click carries. */
    private static final InputWithModifiers PRESS = new InputWithModifiers() {
        @Override
        public int input() {
            return 0;
        }

        @Override
        public int modifiers() {
            return 0;
        }
    };

    /** The literals the last press pass named, so the assertion reads what was really drawn. */
    private static final List<String> PRESSED = new ArrayList<>();

    /**
     * Every widget sits where a layout put it, not where it was constructed.
     *
     * <p>A widget is built at the origin and stays there until its container arranges it, so a screen
     * that fills its layout but never calls {@code arrangeElements} draws the whole interface stacked
     * in one corner — which is exactly what a player sees as "everything is stuck together". Counting
     * widgets cannot catch that; only their coordinates can.
     *
     * <p>The command grid is asserted to be a single column, because rows that share an {@code x} must
     * differ in {@code y} and vice versa: either failure means the grid collapsed.
     */
    private static void assertWidgetsAreLaidOut(GrantEditorScreen editor) {
        List<AbstractWidget> all = widgets(editor).toList();
        long distinctX = all.stream().map(AbstractWidget::getX).distinct().count();
        long distinctY = all.stream().map(AbstractWidget::getY).distinct().count();
        if (distinctX < 2 || distinctY < 2) {
            throw new AssertionError("The grant editor's widgets are all at the origin or in a single"
                    + " line: " + distinctX + " distinct x, " + distinctY + " distinct y for " + all.size()
                    + " widgets — the layout was populated but never arranged; it drew "
                    + describeGeometry(editor));
        }
        List<Checkbox> boxes = widgets(editor, Checkbox.class).toList();
        long boxX = boxes.stream().map(AbstractWidget::getX).distinct().count();
        long boxY = boxes.stream().map(AbstractWidget::getY).distinct().count();
        if (boxX != 1 || boxY != boxes.size()) {
            throw new AssertionError("The command checkboxes are not one column of " + boxes.size()
                    + " rows: " + boxX + " distinct x, " + boxY + " distinct y; it drew "
                    + describeGeometry(editor));
        }
        // Rows stacked in the same place overlap; that is what "everything is stuck together" is.
        for (int i = 1; i < boxes.size(); i++) {
            int previous = boxes.get(i - 1).getY() + boxes.get(i - 1).getHeight();
            if (boxes.get(i).getY() < previous) {
                throw new AssertionError("Command row " + i + " starts at y=" + boxes.get(i).getY()
                        + ", inside row " + (i - 1) + " which ends at y=" + previous + "; it drew "
                        + describeGeometry(editor));
            }
        }
        // The grid must fit between the header and the footer rather than run over them.
        int top = boxes.stream().mapToInt(AbstractWidget::getY).min().orElse(0);
        int bottom = boxes.stream().mapToInt(box -> box.getY() + box.getHeight()).max().orElse(0);
        if (top < 0 || bottom > editor.height) {
            throw new AssertionError("The command grid does not fit the " + editor.height + "px screen:"
                    + " rows run from y=" + top + " to y=" + bottom + ", and a page of "
                    + editor.rowsPerPage() + " rows is taller than the space the header and footer"
                    + " leave; it drew " + describeGeometry(editor));
        }
    }

    /** Each widget with the rectangle it was given, for reading a broken layout off a failure. */
    private static String describeGeometry(Screen screen) {
        return widgets(screen)
                .map(widget -> "\"" + widget.getMessage().getString() + "\"@"
                        + widget.getX() + "," + widget.getY()
                        + " " + widget.getWidth() + "x" + widget.getHeight()
                        + (widget.visible ? "" : " (hidden)"))
                .toList()
                .toString();
    }

    /**
     * The root literals the editor drew a checkbox for, recovered from the label
     * {@code "time (gamemasters)"} the screen builds.
     */
    private static List<String> checkboxLabels(Screen screen) {
        return widgets(screen, Checkbox.class)
                .map(widget -> widget.getMessage().getString())
                .map(label -> label.startsWith("/") ? label.substring(1) : label)
                .filter(label -> label.contains(" ("))
                .map(label -> label.substring(0, label.indexOf(" (")))
                .toList();
    }

    /**
     * Exercises derivation and enforcement directly, because a single client cannot join its own world
     * twice: the trim is a pure function of (player, permission set, ledger), and the parse gate is the
     * same code the packet listener runs, so both can be driven from the host's own server player.
     */
    private static void assertTreeAndGateRespectTheLedger(ClientGameTestContext context) {
        context.runOnClient(minecraft -> {
            // Leave the editor so the rest of the checks do not depend on it staying open.
            minecraft.gui.setScreen(null);

            ServerPlayer probe = minecraft.getSingleplayerServer().getPlayerList().getPlayers().stream()
                    .findFirst().orElseThrow(() -> new AssertionError("No server player"));
            CommandGrantsServer manager = CommandGrantsServer.of(probe);
            if (manager == null) {
                throw new AssertionError("The mod is not managing this server");
            }
            if (!manager.mayEdit(probe)) {
                throw new AssertionError("The world's host is not allowed to edit grants");
            }
            if (minecraft.getSingleplayerServer().getWorldData().isAllowCommands()) {
                throw new AssertionError("Cheats are on in a world the test asked to keep them off");
            }

            CommandIndex index = manager.index();
            assertCatalogueAgrees(index);
            String witness = otherGrantable(index, "time", "gamemode");
            if (witness == null) {
                throw new AssertionError("The catalogue offers nothing but the commands this test names");
            }

            PermissionLevel timeLevel = index.entry("time").required();

            // One real player, one ledger row, and the derivation driven directly. A second client
            // cannot join this world, so the guest is described rather than logged in: everything the
            // mod derives from a player is a pure function of (permission set, ledger), and GrantAccess
            // takes exactly those two, so a synthetic guest exercises the real code paths.
            GrantAccess access = new GrantAccess();
            manager.edit(probe, GUEST, "time", timeLevel, true);
            GrantTable grants = manager.table(GUEST);
            if (!grants.allowsLiteral("time")) {
                throw new AssertionError("Opening /time for the guest did not stick");
            }
            if (grants.allowsLiteral("gamemode")) {
                throw new AssertionError("Opening /time also opened /gamemode");
            }

            // A guest: profile permissions empty, plus whatever the ledger opened. This is the shape of
            // a player who joined with cheats off, and it is what the tree and the gate are fed below.
            PermissionSet guestBase = PermissionSet.NO_PERMISSIONS;
            PermissionSet guestPermissions = new GrantedPermissionSet(guestBase, grants);
            CommandSourceStack guestSource = Commands.createCompilationContext(guestPermissions);

            CommandDispatcher<CommandSourceStack> full =
                    minecraft.getSingleplayerServer().getCommands().getDispatcher();

            // What the host actually holds here. With cheats off the singleplayer owner is not given the
            // level permissions, which is the whole reason this mod exists — so this is logged rather
            // than assumed, and the assertions below build their "player who already had it" case from a
            // synthetic set instead of borrowing the host's.
            PermissionSet realHostBase =
                    minecraft.getSingleplayerServer().getProfilePermissions(probe.nameAndId());
            CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: host holds {}",
                    describePermissions(realHostBase));

            // Step three of the filter, both ways, on a player who is exactly a gamemaster: vanilla's own
            // grant has to survive the trim, and it must not drag anything above it along.
            PermissionSet gamemasterBase = LevelBasedPermissionSet.forLevel(PermissionLevel.GAMEMASTERS);
            CommandNode<CommandSourceStack> gamemasterTree =
                    access.filteredRoot(probe, gamemasterBase, full.getRoot(), grants, index);
            if (gamemasterBase.hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS))) {
                throw new AssertionError("A gamemaster set also answers yes for admins:"
                        + " the level sets are not the nesting this contract assumes");
            }
            assertHasChild(gamemasterTree, "time", true);
            assertHasChild(gamemasterTree, "gamemode", true);
            assertHasChild(gamemasterTree, witness, true);

            // A literal the host could not be trusted to hand out is one they cannot use themselves, and
            // the filter has to leave it alone. Which literal that is depends on the game and on whether
            // dedicated-server commands were registered, so it is looked up rather than written down.
            String beyond = above(index, PermissionLevel.GAMEMASTERS, "time", "gamemode", witness);
            if (beyond == null) {
                CommandGrantsMod.LOGGER.warn("[CommandGrants] gametest: this tree has nothing above"
                        + " gamemasters, so the ceiling half of the trim went unchecked");
            } else {
                assertHasChild(gamemasterTree, beyond, false);
            }

            // The tree a guest receives: the grant, the baseline, and nothing else.
            CommandNode<CommandSourceStack> gatedTree =
                    access.filteredRoot(probe, guestBase, full.getRoot(), grants, index);
            assertHasChild(gatedTree, "time", true);
            assertHasChild(gatedTree, "gamemode", false);
            assertHasChild(gatedTree, witness, false);
            // Vanilla's own free commands are not this mod's to take away.
            assertHasChild(gatedTree, "help", true);
            assertHasChild(gatedTree, "list", true);

            RootCommandNode<CommandSourceStack> tree =
                    access.filteredRoot(probe, guestBase, full.getRoot(), grants, index);
            CommandDispatcher<CommandSourceStack> narrowed =
                    access.narrowDispatcher(probe, guestBase, full, grants, index);
            if (tree == full.getRoot() || narrowed == full) {
                throw new AssertionError("Nothing was filtered for a granted player:"
                        + " tree=" + (tree == full.getRoot() ? "verbatim" : "filtered")
                        + " dispatcher=" + (narrowed == full ? "verbatim" : "narrowed")
                        + " grants=" + grants.literals()
                        + " catalogue=" + (index.entry("time") == null ? "no /time" : "has /time")
                        + "\n  " + describeWitness(index, full.getRoot(), witness));
            }

            // The gate: an unopened command must not resolve. This is the dispatcher the packet
            // listener builds, driven with the same guest source.
            //
            // Driving it through execute rather than parse is the point. Brigadier's parseNodes catches
            // every candidate's failure, so parsing "gamemode creative" against a dispatcher with no
            // /gamemode at all still returns a ParseResults with the input left unread and no exception;
            // the reader alone cannot tell "parsed" from "gave up immediately". The unknown-command error
            // is thrown by execute, and execute is what Commands.performCommand calls on the results of
            // the dispatcher this mod narrowed.
            String refused = refusal(narrowed, guestSource, "gamemode creative");
            if (refused == null) {
                throw new AssertionError("/gamemode resolves for a guest who was only given /time"
                        + "\n  root has gamemode=" + (narrowed.getRoot().getChild("gamemode") != null)
                        + " tree has gamemode=" + (tree.getChild("gamemode") != null)
                        + " sameDispatcher=" + (narrowed == full)
                        + " sameRoot=" + (narrowed.getRoot() == full.getRoot())
                        + "\n  guest=" + describePermissions(guestPermissions)
                        + " grants=" + grants.literals()
                        + "\n  " + describeWitness(index, full.getRoot(), "gamemode"));
            }
            if (refusal(narrowed, guestSource, witness) == null) {
                throw new AssertionError("/" + witness + " resolves for a guest who was only given /time"
                        + "\n  " + describeWitness(index, full.getRoot(), witness)
                        + "\n  trimmed=" + (tree.getChild(witness) != null)
                        + " mirrored=" + (narrowed.getRoot().getChild(witness) != null));
            }
            // ...and the one command the guest does hold has to survive the same treatment.
            String blocked = refusal(narrowed, guestSource, "time set day");
            if (blocked != null) {
                throw new AssertionError("/time no longer resolves for a guest who was given /time: " + blocked
                        + "\n  trimmed=" + (tree.getChild("time") != null)
                        + " mirrored=" + (narrowed.getRoot().getChild("time") != null)
                        + " grants=" + grants.literals());
            }

            // The editor offers one checkbox per root literal, so the host opens whatever name that
            // checkbox carries. A command vanilla spells with more than one root literal therefore has to
            // open every one of them: opening the long name of an alias must not leave the short one shut,
            // because the short one is the name players actually type.
            CommandNode<CommandSourceStack> aliasNode = full.getRoot().getChild("tp");
            if (aliasNode == null) {
                CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: this tree has no /tp alias,"
                        + " so the alias half went unchecked");
            } else if (index.entry("tp") != null) {
                // Two root literals, one command: what makes them one is the redirect the short name
                // carries, and discovery has to have seen it.
                if (!(aliasNode instanceof LiteralCommandNode<CommandSourceStack> aliasLiteral)
                        || aliasLiteral.getRedirect() == null) {
                    throw new AssertionError("/tp is a root literal without a redirect, so the catalogue"
                            + " offers two independent checkboxes for one command: "
                            + describeWitness(index, full.getRoot(), "tp"));
                }
                if (!index.group("tp").contains("teleport") || !index.group("teleport").contains("tp")) {
                    throw new AssertionError("The catalogue did not group /tp with /teleport, so ticking"
                            + " one leaves the other shut: tpGroup=" + index.group("tp")
                            + " teleportGroup=" + index.group("teleport")
                            + " tpEntry=" + index.entry("tp")
                            + " teleportEntry=" + index.entry("teleport"));
                }
                // The name the host actually reads on the row: a row labelled "teleport" is how a host
                // comes to believe they opened the command players type as /tp. Wrapped into the wire
                // shape because the label is drawn from the client's copy of the catalogue.
                CommandIndex.Entry longName = index.entry("teleport");
                String rowLabel = String.join(" / ", GrantEditorScreen.names(
                        new CommandGrantsNetwork.Entry(longName.literal(), longName.required(),
                                longName.offered(), longName.available(), longName.group())));
                if (!rowLabel.equals("teleport / tp")) {
                    throw new AssertionError("The editor's row for the command is labelled \"" + rowLabel
                            + "\", which does not name both roots it opens: tpEntry=" + index.entry("tp")
                            + " teleportEntry=" + index.entry("teleport"));
                }

                // This is the bug the user reported: tick "teleport", type /tp, get "unknown command".
                PermissionLevel aliasLevel = index.entry("teleport").required();
                manager.edit(probe, GUEST, "teleport", aliasLevel, true);
                GrantTable aliasGrants = manager.table(GUEST);
                if (!aliasGrants.allowsLiteral("teleport")) {
                    throw new AssertionError("Opening /teleport for the guest did not stick: "
                            + aliasGrants.literals());
                }
                CommandDispatcher<CommandSourceStack> aliasDispatcher =
                        access.narrowDispatcher(probe, guestBase, full, aliasGrants, index);
                String stillShut = refusal(aliasDispatcher, guestSource, "tp 0 0 0");
                if (stillShut != null) {
                    throw new AssertionError("A guest who was given /teleport cannot use /tp: " + stillShut
                            + "\n  grants=" + aliasGrants.literals()
                            + " tpInTree=" + (access.filteredRoot(probe, guestBase, full.getRoot(),
                                    aliasGrants, index).getChild("tp") != null)
                            + " tpEntry=" + index.entry("tp")
                            + " teleportEntry=" + index.entry("teleport")
                            + " redirect=" + ((LiteralCommandNode<CommandSourceStack>) aliasNode)
                                    .getRedirect()
                            + "\n  " + describeWitness(index, full.getRoot(), "tp"));
                }
                // The other direction has to work too, or half the hosts tick the short name.
                manager.edit(probe, GUEST, "teleport", aliasLevel, false);
                manager.edit(probe, GUEST, "tp", index.entry("tp").required(), true);
                GrantTable shortNameGrants = manager.table(GUEST);
                String longNameShut = refusal(
                        access.narrowDispatcher(probe, guestBase, full, shortNameGrants, index),
                        guestSource, "teleport EastTnT 0 0 0");
                if (longNameShut != null) {
                    throw new AssertionError("A guest who was given /tp cannot use /teleport: "
                            + longNameShut
                            + "\n  grants=" + shortNameGrants.literals()
                            + "\n  " + describeWitness(index, full.getRoot(), "teleport"));
                }
                manager.edit(probe, GUEST, "tp", index.entry("tp").required(), false);
            } else {
                throw new AssertionError("/tp is a root literal but is not in the catalogue, so the host"
                        + " cannot open the name players type: "
                        + describeWitness(index, full.getRoot(), "tp"));
            }

            manager.edit(probe, GUEST, "time", timeLevel, false);
            if (!manager.table(GUEST).isEmpty()) {
                throw new AssertionError("Closing /time left the guest's grant behind");
            }

            // The host, granting to themselves, through the production path rather than the fixture the
            // checks above use: the permission set comes from the manager, the tree and the dispatcher
            // come from the manager. This is the first thing every host tries, and it was broken — with
            // cheats off the singleplayer owner holds no level permissions, and the manager used to skip
            // its own wrapper for them ("the host already holds everything"), so the grant was dropped on
            // the floor: /gamemode never entered the host's own tree and their own parse of it failed.
            UUID hostId = probe.getUUID();
            boolean hadRecord = !manager.table(hostId).isEmpty();
            // Every grantable literal, one at a time, so each has to stand on its own. What is asserted
            // per command is the part that was silently broken: the grant reaching the permission set, the
            // tree and the dispatcher the manager hands out — /gamemode never entered the host's own tree,
            // so their own line never parsed. The commands are not executed here: a fixture that has to
            // invent working arguments for all 78 of them would be testing its own vocabulary rather than
            // the grant, and /gamemode below drives one line end to end instead.
            List<String> onOffer = index.grantable();
            List<String> notApplied = new ArrayList<>();
            for (String literal : onOffer) {
                PermissionLevel level = index.entry(literal).required();
                manager.edit(probe, hostId, literal, level, true);
                PermissionSet own = manager.permissionsFor(probe);
                CommandDispatcher<CommandSourceStack> ownDispatcher =
                        manager.narrowedDispatcher(probe, full);
                RootCommandNode<CommandSourceStack> ownTree = manager.filteredRoot(probe, full.getRoot());
                boolean inTree = ownTree.getChild(literal) != null;
                boolean inDispatcher = ownDispatcher.getRoot().getChild(literal) != null;
                boolean levelled = own.hasPermission(new Permission.HasCommandLevel(level));
                if (!inTree || !inDispatcher || !levelled) {
                    notApplied.add("/" + literal + " (" + level + "): inTree=" + inTree
                            + " inDispatcher=" + inDispatcher
                            + " permissionsHoldIt=" + levelled
                            + " dispatcher=" + (ownDispatcher == full ? "verbatim" : "narrowed")
                            + " permissions=" + describePermissions(own));
                }
                // The other half of the same question: the host's own tick must not drag in a command
                // they never ticked. Closing it again is also the round trip through the ledger.
                if (witness != null && !index.group(literal).contains(witness)
                        && ownDispatcher.getRoot().getChild(witness) != null) {
                    throw new AssertionError("Opening /" + literal + " for themselves also opened /"
                            + witness + ", which the host never ticked");
                }
                manager.edit(probe, hostId, literal, level, false);
                if (!hadRecord && !manager.table(hostId).isEmpty()) {
                    throw new AssertionError("Closing the host's own grant of /" + literal
                            + " left a record behind: " + manager.table(hostId).literals());
                }
            }
            if (!notApplied.isEmpty()) {
                throw new AssertionError("The host ticked " + notApplied.size() + " command(s) for"
                        + " themselves and the grant did not reach them:\n  "
                        + String.join("\n  ", notApplied)
                        + "\n  " + describeWitness(index, full.getRoot(), onOffer.get(0)));
            }

            // And one line, driven the whole way through the dispatcher the manager narrows, by the
            // permission set the manager hands out: the shape of the user's report. /gamemode is named
            // because a host opening it for themselves is exactly what they did; the line is the game's
            // own, not this test's invention.
            PermissionLevel gamemodeLevel = index.entry("gamemode").required();
            manager.edit(probe, hostId, "gamemode", gamemodeLevel, true);
            PermissionSet own = manager.permissionsFor(probe);
            RootCommandNode<CommandSourceStack> ownTree = manager.filteredRoot(probe, full.getRoot());
            CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: the host's own /gamemode grant is"
                    + " held by {}, and their own tree {} it", describePermissions(own),
                    ownTree.getChild("gamemode") != null ? "contains" : "is missing");
            String ownRefusal = refusal(manager.narrowedDispatcher(probe, full),
                    Commands.createCompilationContext(own), "gamemode creative");
            manager.edit(probe, hostId, "gamemode", gamemodeLevel, false);
            if (ownRefusal != null) {
                throw new AssertionError("The host opened /gamemode for themselves and still cannot use"
                        + " it: " + ownRefusal
                        + "\n  permissions=" + describePermissions(own)
                        + "\n  " + describeWitness(index, full.getRoot(), "gamemode"));
            }

            CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: the host opened all {} grantable"
                    + " commands for themselves and every grant reached their own tree", onOffer.size());

            CommandGrantsMod.LOGGER.info("[CommandGrants] gametest: ledger, tree and gate all behaved");
        });
    }

    // ------------------------------------------------------------------ catalogue expectations

    /**
     * The catalogue answers with the level each root literal demands and whether the host may hand it
     * out. The free commands matter as much as the restricted ones: they are what the filter must never
     * take away, because they are not this mod's to give.
     */
    private static void assertCatalogueAgrees(CommandIndex index) {
        for (String needed : new String[] {"time", "gamemode"}) {
            CommandIndex.Entry entry = index.entry(needed);
            if (entry == null) {
                throw new AssertionError("/" + needed + " is not in the command catalogue at all");
            }
            if (!entry.grantable()) {
                throw new AssertionError("/" + needed + " is not offerable: " + entry);
            }
        }
        for (String free : new String[] {"help", "list"}) {
            CommandIndex.Entry entry = index.entry(free);
            if (entry == null) {
                throw new AssertionError("/" + free + " is not in the command catalogue at all");
            }
            if (entry.grantable()) {
                throw new AssertionError("/" + free + " is offered although it is free: " + entry);
            }
        }
    }

    /** A grantable literal other than the ones this test drives by name, or {@code null} if there is none. */
    private static String otherGrantable(CommandIndex index, String... excluded) {
        List<String> skip = List.of(excluded);
        for (String literal : index.grantable()) {
            if (!skip.contains(literal)) {
                return literal;
            }
        }
        return null;
    }

    /**
     * A literal whose effective gate is stronger than {@code ceiling}, or {@code null} if the tree has
     * none. Looked up instead of named because which commands exist depends on the world: the dedicated
     * server commands are only registered when the game is running one.
     */
    private static String above(CommandIndex index, PermissionLevel ceiling, String... excluded) {
        List<String> skip = List.of(excluded);
        for (String literal : index.literals()) {
            CommandIndex.Entry entry = index.entry(literal);
            if (entry != null && !skip.contains(literal) && entry.required().isEqualOrHigherThan(ceiling)
                    && entry.required() != ceiling) {
                return literal;
            }
        }
        return null;
    }

    /**
     * Drives one command line through the same path the server uses, and reports what stopped it.
     *
     * <p>The verdict is taken from the <em>cursor</em>, not from an exception. {@code execute} throws
     * {@link CommandSyntaxException} both when the dispatcher has no path for the line and when the
     * command ran and then objected to its arguments — {@code /tp 0 0 0} against a bare source says
     * "An entity is required to run this command here" — so treating every such exception as a refusal
     * reports a working gate as a broken one. What actually distinguishes the two is whether parsing
     * got to the end of the line: only unread input means no path was found.
     *
     * <p>So a line is refused when the parse cursor is left with text unread, and allowed when the
     * whole line was consumed — whatever the command then did with it (throw, or run against a source
     * that cannot do the work).
     *
     * @return the reason the line was refused, or {@code null} when it got through
     */
    private static String refusal(CommandDispatcher<CommandSourceStack> dispatcher,
                                  CommandSourceStack source, String line) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(line, source);
        var reader = parsed.getReader();
        if (reader.canRead()) {
            return "unconsumed input at " + reader.getCursor() + "/" + reader.getTotalLength()
                    + ": " + exhaust(parsed);
        }
        try {
            dispatcher.execute(parsed);
        } catch (CommandSyntaxException | RuntimeException | Error after) {
            // Past the gate. The command ran and failed on its own terms — a compilation source has no
            // entity to teleport, no level to set an operator on, and so on.
        }
        return null;
    }

    /**
     * The exceptions Brigadier collected while failing to consume the whole line.
     *
     * <p>{@code parse} itself never reports a failure: it returns the best of a list of per-candidate
     * results, and the reasons live on the first one. Reading them back turns "it did not parse" into
     * "it did not parse because …".
     */
    private static String exhaust(ParseResults<CommandSourceStack> parsed) {
        if (parsed.getExceptions().isEmpty()) {
            return "no exception recorded";
        }
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<CommandNode<CommandSourceStack>, CommandSyntaxException> entry
                : parsed.getExceptions().entrySet()) {
            reasons.add(entry.getKey().getName() + ": " + entry.getValue().getMessage());
        }
        return String.join("; ", reasons);
    }

    /** Which of the level permissions a set answers yes to, for pinning down what a real player holds. */
    private static String describePermissions(PermissionSet permissions) {
        StringBuilder text = new StringBuilder();
        for (PermissionLevel level : PermissionLevel.values()) {
            if (level == PermissionLevel.ALL) {
                continue;
            }
            if (permissions.hasPermission(new Permission.HasCommandLevel(level))) {
                text.append(text.isEmpty() ? "" : ",").append(level);
            }
        }
        return text.isEmpty() ? "no level permissions" : text.toString();
    }

    /** Everything the filter looks at for one literal, for when an assertion about it fires. */
    private static String describeWitness(CommandIndex index, RootCommandNode<CommandSourceStack> root,
                                          String literal) {
        CommandIndex.Entry entry = index.entry(literal);
        CommandNode<CommandSourceStack> node = root.getChild(literal);
        Predicate<CommandSourceStack> requirement = node == null ? null : node.getRequirement();
        boolean vanillaGate = requirement == Commands.LEVEL_MODERATORS
                || requirement == Commands.LEVEL_GAMEMASTERS
                || requirement == Commands.LEVEL_ADMINS
                || requirement == Commands.LEVEL_OWNERS;
        return literal
                + " indexed=" + (entry == null ? "absent"
                        : entry.required() + "/offered=" + entry.offered()
                                + "/available=" + entry.available())
                + " rootChild=" + (node == null ? "absent" : node.getClass().getName())
                + " requirement=" + (requirement == null ? "-" : requirement.getClass().getName())
                + " vanillaGate=" + vanillaGate
                + " catalogueHead=" + head(index.literals(), 12);
    }

    private static List<String> head(List<String> values, int count) {
        return values.size() <= count ? values : values.subList(0, count);
    }

    // ------------------------------------------------------------------ screen helpers

    /** Opens the LAN sharing screen the way the pause menu does, without driving the title screen. */
    private static void openMultiplayerOptions(ClientGameTestContext context) {
        waitFor(context, "the pause menu", minecraft -> {
            minecraft.gui.setScreen(new PauseScreen(true));
            return minecraft.gui.screen() instanceof PauseScreen;
        });
        waitFor(context, "the multiplayer options screen", minecraft -> {
            Screen pause = minecraft.gui.screen();
            if (!(pause instanceof PauseScreen)) {
                return false;
            }
            minecraft.gui.setScreen(new MultiplayerOptionsScreen(pause));
            return minecraft.gui.screen() instanceof MultiplayerOptionsScreen;
        });
    }

    private static void waitForScreen(ClientGameTestContext context, Class<? extends Screen> type) {
        waitFor(context, type.getSimpleName(), minecraft -> type.isInstance(minecraft.gui.screen()));
    }

    /** The window, seen as the game test API's resize hook, which its mixin added to the real class. */
    private static WindowHooks windowHooks() {
        Object window = Minecraft.getInstance().getWindow();
        if (window instanceof WindowHooks hooks) {
            return hooks;
        }
        throw new AssertionError("The window is not carrying the game test API's WindowHooks mixin, so"
                + " this test cannot resize the window: " + window.getClass().getName());
    }

    private static Stream<AbstractWidget> widgets(Screen screen) {
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast);
    }

    /** The widgets of {@code screen} that are also a {@code type}, for asking what a layout drew. */
    private static <T extends AbstractWidget> Stream<T> widgets(Screen screen, Class<T> type) {
        return widgets(screen).filter(type::isInstance).map(type::cast);
    }

    /** The button carrying {@code label}, or {@code null} when the screen has none. */
    private static Button button(Screen screen, String label) {
        return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> label.equals(candidate.getMessage().getString()))
                .findFirst()
                .orElse(null);
    }

    private static String describeButtons(Screen screen) {
        return widgets(screen)
                .map(widget -> "\"" + widget.getMessage().getString() + "\""
                        + (widget.active ? "" : " (disabled)"))
                .toList()
                .toString();
    }

    // ------------------------------------------------------------------ waiting

    /**
     * Polls for a client state; fails with {@code what} named when it never arrives.
     *
     * <p>Never calls a blocking helper: the game is ticked by the render thread, and a wait that does
     * not tick it never completes.
     */
    private static void waitFor(ClientGameTestContext context, String what, Predicate<Minecraft> condition) {
        int waited = context.waitFor(condition, 200);
        if (waited < 0) {
            throw new AssertionError("Timed out after 200 ticks waiting for " + what + "; "
                    + describe(context));
        }
    }

    /** The client's current state, for a timeout message worth reading. */
    private static String describe(ClientGameTestContext context) {
        return context.computeOnClient(minecraft -> "screen="
                + (minecraft.gui.screen() == null ? "none"
                        : minecraft.gui.screen().getClass().getSimpleName())
                + " level=" + (minecraft.level == null ? "loading" : "loaded")
                + " player=" + (minecraft.player == null ? "absent" : "present"));
    }

    private static void assertHasChild(CommandNode<CommandSourceStack> node, String literal,
                                       boolean expected) {
        boolean found = node.getChild(literal) != null;
        if (found != expected) {
            throw new AssertionError("/" + literal + (expected ? " is missing from" : " leaked into")
                    + " the command tree sent to the client");
        }
    }
}
