package com.lealex.alchyx.client;

import com.lealex.alchyx.machine.KindChoice;
import com.lealex.alchyx.menu.ChoiceMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The screen of a machine that offers kinds ({@link KindChoice}, {@link ChoiceMenu}): a GRID of their pictures, nine
 * to a row, each over a small plate that says whether it is on (green, a tick) or off (red, a cross). A click on a
 * picture turns it off or on; its name, its state, anything wrong with it and what is known of its chances are told
 * under the mouse. Five rows show at a time (45 kinds): the mouse wheel scrolls the rest. In the header, a SEARCH
 * box (kinds whose name holds what is typed) and a button that shows all of them, only those on, or only those off.
 * Under the grid, the machine's dial if it has one, what it lacks, and its notes, wrapped to the panel.
 *
 * A mod makes it its own by extending it: {@link #theme()} (its sheets and colours), {@link #icon()}, {@link
 * #problem()}, {@link #details} (more lines under the mouse), and a panel beside the window ({@link #drawSide},
 * {@link #clickSide}: upgrades, say), and registers that class for its own menu type.
 */
public abstract class ChoiceScreen extends AbstractContainerScreen<ChoiceMenu> {
    protected static final int WIDTH = 200, TOP = 20, COLS = 9, GRID_ROWS = 5, CELL_WIDE = 20, CELL_HIGH = 26, GRID_X = (WIDTH - COLS * CELL_WIDE) / 2;
    private static final int LIST_HIGH = GRID_ROWS * CELL_HIGH + 12; // the grid, and a line that says what a click does
    private static final int DIAL_ROW = 22, NOTE_LINES = 5, HEIGHT = TOP + LIST_HIGH + DIAL_ROW + NOTE_LINES * 10 + 6;
    private static final int HEADER = 17, SEARCH_X = WIDTH - 102, SEARCH_WIDE = 58, MODE_X = WIDTH - 40, MODE_WIDE = 32, ESCAPE = 256;
    private static final int ALL = 0, ON = 1, OFF = 2;
    private static final int ON_PLATE = 0xFF1F5C2A, ON_MARK = 0xFF7CE38B, OFF_PLATE = 0xFF6A1E1E, OFF_MARK = 0xFFFF8A80, OFF_EDGE = 0xFF8E2A2A, ON_EDGE = 0xFF2E8540;
    // The marks, five pixels high and of an EVEN width (so each sits in the very middle of its 16-pixel plate):
    // a tick 8 wide, a cross 6 wide, both two pixels thick
    private static final int TICK_WIDE = 8, CROSS_WIDE = 6;
    private static final int[][] TICK = {{0, 2}, {1, 2}, {1, 3}, {2, 3}, {2, 4}, {3, 4}, {3, 3}, {4, 3}, {4, 2}, {5, 2}, {5, 1}, {6, 1}, {6, 0}, {7, 0}};
    private static final int[][] CROSS = {{0, 0}, {1, 0}, {4, 0}, {5, 0}, {1, 1}, {2, 1}, {3, 1}, {4, 1}, {2, 2}, {3, 2}, {1, 3}, {2, 3}, {3, 3}, {4, 3},
            {0, 4}, {1, 4}, {4, 4}, {5, 4}};

    private @Nullable List<Item> shown; // the kinds the widgets were made for
    private int first = 0;              // the row of the filtered grid on the screen's first row (the grid scrolls)
    private String query = "";          // what is typed in the search box
    private int mode = ALL;             // all of them, only those on, only those off
    private boolean refresh, typing;    // the filter changed: the widgets follow on the next tick; the box had the keys
    private @Nullable EditBox search;

    public ChoiceScreen(ChoiceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    // ---- What a mod's own screen gives ----

    /** The window's sheets and colours. */
    protected abstract WindowTheme theme();

    /** The picture at the left of the header (the machine's own block), or empty. */
    protected ItemStack icon() {
        return ItemStack.EMPTY;
    }

    /** What the machine lacks, written in the warning colour above its notes, or empty. */
    protected String problem() {
        return "";
    }

    /** More lines for the tooltip of a kind, after its name, state, warning and chances. */
    protected void details(Item kind, boolean on, List<Component> told) {}

    /** Draws a panel beside the window (its right edge is at leftPos + imageWidth). */
    protected void drawSide(GuiGraphicsExtractor graphics, WindowTheme theme, int mouseX, int mouseY) {}

    /** A click on that panel: the menu button to send, or -1 if the click was not on it. */
    protected int clickSide(MouseButtonEvent event) {
        return -1;
    }

    /** Sends a menu button to the server (a kind's number, a dial step, a mod's own). */
    protected void press(int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    /** The pictures the grid shows (the machine's kinds; a mod's screen may show another set on a page of its own). */
    protected List<Item> kinds() {
        KindChoice choice = menu.choice();
        return choice == null ? List.of() : choice.offered();
    }

    /** Whether a picture shows as on (tick) or off (cross). */
    protected boolean on(Item kind) {
        KindChoice choice = menu.choice();
        return choice != null && choice.chosen(kind);
    }

    /** A click on the picture at this place of kinds(), with this mouse button: by default the left one turns it off or on. */
    protected void clickKind(int index, int button) {
        if (button == 0) press(index);
    }

    /** The line under the grid that says what a click does. */
    protected String hint() {
        KindChoice choice = menu.choice();
        return "Click a picture: " + (choice == null ? "on" : choice.chosenLabel().toLowerCase(Locale.ROOT)) + " or off.";
    }

    /** What the tooltip of a picture says: its name, its state, what is wrong with it, its chances, what a click does. */
    protected void describe(Item kind, boolean on, List<Component> told) {
        KindChoice choice = menu.choice();
        if (choice == null) return;
        String warning = choice.warning(kind);
        told.add(Component.literal(name(kind)));
        told.add(Component.literal(on ? choice.chosenLabel() : "Off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        if (warning != null) told.add(Component.literal(capital(warning)).withStyle(ChatFormatting.GOLD));
        for (String chance : choice.odds(kind)) { // (what is the rift's own is told in its colour)
            told.add(Component.literal(capital(chance)).withStyle(chance.startsWith("Rift") ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
        }
        details(kind, on, told);
        told.add(Component.literal(on ? "Click to turn it off" : "Click to turn it on").withStyle(ChatFormatting.DARK_GRAY));
    }

    protected static String capital(String words) {
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    protected static String name(Item kind) {
        return new ItemStack(kind).getHoverName().getString();
    }

    /** The kinds that pass the search and the on / off filter, as their numbers in the workstation's own list. */
    private List<Integer> visible() {
        // (worked out again when the pictures themselves change: a mod's page switch, a new list from the server;
        // numbers kept from another list would point past its end)
        List<Item> kinds = kinds();
        if (passing == null || passingFor != kinds) {
            passingFor = kinds;
            passing = filtered();
        }
        return passing;
    }

    private @Nullable List<Item> passingFor;

    // Worked out once a tick and when the search or the filter changes, not at every use in a frame
    private @Nullable List<Integer> passing;
    private final java.util.Map<Item, String> names = new java.util.HashMap<>(); // lower case, for the search

    private List<Integer> filtered() {
        KindChoice choice = menu.choice();
        List<Item> kinds = kinds();
        List<Integer> passing = new ArrayList<>(kinds.size());
        String wanted = query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < kinds.size(); i++) {
            if (!wanted.isEmpty() && !names.computeIfAbsent(kinds.get(i), kind -> name(kind).toLowerCase(Locale.ROOT)).contains(wanted)) continue;
            boolean is = on(kinds.get(i));
            if ((mode == ON && !is) || (mode == OFF && is)) continue;
            passing.add(i);
        }
        return passing;
    }

    private static int lastRow(int kinds) {
        return Math.max(0, (kinds + COLS - 1) / COLS - GRID_ROWS);
    }

    /** The place in the filtered list of the picture under the mouse, or -1. */
    private int under(double mouseX, double mouseY, int kinds) {
        double x = mouseX - leftPos - GRID_X, y = mouseY - topPos - TOP;
        if (x < 0 || y < 0 || x >= COLS * CELL_WIDE || y >= GRID_ROWS * CELL_HIGH) return -1;
        int at = (first + (int) y / CELL_HIGH) * COLS + (int) x / CELL_WIDE;
        return at < kinds ? at : -1;
    }

    // A click on the panel beside the window (a mod's own); or on a kind's picture: off, or on again
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int side = clickSide(event);
        if (side >= 0) {
            press(side);
            return true;
        }
        List<Integer> passing = visible();
        int at = under(event.x(), event.y(), passing.size());
        if (at >= 0 && (event.button() == 0 || event.button() == 1)) {
            clickKind(passing.get(at), event.button());
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void init() {
        super.init();
        titleLabelX = 24; // after the workstation's post, drawn in the header
        KindChoice choice = menu.choice();
        shown = kinds();
        first = Math.clamp(first, 0, lastRow(visible().size()));
        String label = choice == null ? "On" : choice.chosenLabel();

        search = new EditBox(font, leftPos + SEARCH_X, topPos + 5, SEARCH_WIDE, 11, Component.literal("Search"));
        search.setMaxLength(24);
        search.setHint(Component.literal("Search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(text -> {
            if (text.equals(query)) return;
            query = text;
            passing = null;
            first = 0;
        });
        addRenderableWidget(search);
        if (typing) { // the widgets were made again while it was being typed in: it keeps the keys
            setFocused(search);
            search.setFocused(true);
        }
        addRenderableWidget(Button.builder(Component.literal(mode == ALL ? "All" : mode == ON ? label : "Off"), button -> {
                    mode = (mode + 1) % 3;
                    passing = null;
                    first = 0;
                    refresh = true;
                })
                .bounds(leftPos + MODE_X, topPos + 5, MODE_WIDE, 11) // (inside the header: the band is 5 wide)
                .tooltip(Tooltip.create(Component.literal(mode == ALL ? "Showing all. Click: only those that are on." : mode == ON
                        ? "Showing only those that are on. Click: only those that are off." : "Showing only those that are off. Click: all.")))
                .build());

        // The dial, when the workstation has one: two buttons on each side of its words
        if (choice != null && choice.dial() != null) {
            int y = topPos + TOP + LIST_HIGH + 2;
            int[] at = {8, 34, WIDTH - 58, WIDTH - 32};
            for (int i = 0; i < ChoiceMenu.DIAL_STEPS.length; i++) {
                int step = ChoiceMenu.DIAL_STEPS[i], button = ChoiceMenu.DIAL + i;
                addRenderableWidget(Button.builder(Component.literal(step > 0 ? "+" + step : Integer.toString(step)), pressed -> press(button))
                        .bounds(leftPos + at[i], y, 24, 18).build());
            }
        }
    }

    // The filter's button changed its words, or the workstation got or lost its dial: the widgets follow. (The grid
    // itself is drawn from the workstation every frame: nothing to make again when a kind is turned on or off.)
    @Override
    protected void containerTick() {
        super.containerTick();
        passing = null; // (what is on or off may have changed)
        first = Math.clamp(first, 0, lastRow(visible().size()));
        if (refresh || kinds() != shown) {
            refresh = false;
            typing = search != null && search.isFocused();
            rebuildWidgets();
            typing = false;
        }
    }

    // The wheel moves the grid by one row
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        first = Math.clamp(first - (int) Math.signum(scrollY), 0, lastRow(visible().size()));
        return true;
    }

    // While the search box has the keys, they are its own (the inventory key would close the screen)
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search != null && search.isFocused() && event.key() != ESCAPE) {
            search.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    private static void mark(GuiGraphicsExtractor graphics, int x, int y, boolean on) {
        graphics.fill(x, y, x + 16, y + 7, on ? ON_PLATE : OFF_PLATE);
        int left = x + (16 - (on ? TICK_WIDE : CROSS_WIDE)) / 2;
        for (int[] pixel : on ? TICK : CROSS) {
            graphics.fill(left + pixel[0], y + 1 + pixel[1], left + pixel[0] + 1, y + 2 + pixel[1], on ? ON_MARK : OFF_MARK);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        WindowTheme theme = theme();
        KindChoice choice = menu.choice();
        int x = leftPos, y = topPos;
        theme.back(graphics, x, y, imageWidth, imageHeight, HEADER, icon());
        drawSide(graphics, theme, mouseX, mouseY);
        List<Item> kinds = kinds();
        List<Integer> passing = visible();
        // (its notes are written in a well of their own, under the grid and the dial)
        theme.well(graphics, x + 7, y + TOP + LIST_HIGH + (choice != null && choice.dial() != null ? DIAL_ROW : 0), x + imageWidth - 7, y + imageHeight - 7);
        int hovered = under(mouseX, mouseY, passing.size());
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int column = 0; column < COLS; column++) {
                int at = (first + row) * COLS + column, cx = x + GRID_X + column * CELL_WIDE, cy = y + TOP + row * CELL_HIGH;
                theme.well(graphics, cx, cy, cx + CELL_WIDE, cy + CELL_HIGH - 1);
                if (at >= passing.size() || choice == null) continue;
                Item kind = kinds.get(passing.get(at));
                boolean on = on(kind);
                // A frame in its state's colour (brighter under the mouse), the picture, the plate with its mark
                int edge = at == hovered ? 0xFFFFFFFF : on ? ON_EDGE : OFF_EDGE;
                // (a cell is 20 wide: frame, a pixel of room, the 16 of the picture and of the plate, a pixel, frame)
                graphics.fill(cx, cy, cx + CELL_WIDE, cy + 1, edge);
                graphics.fill(cx, cy + CELL_HIGH - 2, cx + CELL_WIDE, cy + CELL_HIGH - 1, edge);
                graphics.fill(cx, cy, cx + 1, cy + CELL_HIGH - 1, edge);
                graphics.fill(cx + CELL_WIDE - 1, cy, cx + CELL_WIDE, cy + CELL_HIGH - 1, edge);
                graphics.item(new ItemStack(kind), cx + 2, cy + 1);
                mark(graphics, cx + 2, cy + 17, on);
                if (kinds == choice.offered() && choice.warning(kind) != null) graphics.fill(cx + 15, cy + 2, cx + 17, cy + 4, theme.warn()); // something to read about it
            }
        }
        int rows = (passing.size() + COLS - 1) / COLS;
        if (rows > GRID_ROWS) { // more kinds than the grid holds: a bar on the right shows where it is
            int track = GRID_ROWS * CELL_HIGH - 1, bar = Math.max(8, track * GRID_ROWS / rows), at = (track - bar) * first / (rows - GRID_ROWS);
            graphics.fill(x + imageWidth - 6, y + TOP, x + imageWidth - 4, y + TOP + track, theme.frame());
            graphics.fill(x + imageWidth - 6, y + TOP + at, x + imageWidth - 4, y + TOP + at + bar, theme.accent());
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        WindowTheme theme = theme();
        graphics.text(font, Lines.clip(font, title.getString(), SEARCH_X - titleLabelX - 4), titleLabelX, titleLabelY, theme.accent(), false);
        KindChoice choice = menu.choice();
        if (choice == null) return;
        List<Item> kinds = kinds();
        List<Integer> passing = visible();
        graphics.text(font, Lines.clip(font, passing.isEmpty() && !kinds.isEmpty() ? "Nothing matches the filter." : hint(), WIDTH - 2 * GRID_X),
                GRID_X, TOP + GRID_ROWS * CELL_HIGH + 2, theme.faint(), false);
        // Under the mouse: what describe() says of it
        int hovered = under(mouseX, mouseY, passing.size());
        if (hovered >= 0) {
            Item kind = kinds.get(passing.get(hovered));
            List<Component> told = new ArrayList<>(7);
            describe(kind, on(kind), told);
            graphics.setTooltipForNextFrame(font, told, Optional.empty(), mouseX, mouseY);
        }
        String dial = choice.dial();
        if (dial != null) {
            String words = Lines.clip(font, dial, WIDTH - 124); // between the two pairs of buttons
            graphics.text(font, words, (WIDTH - font.width(words)) / 2, TOP + LIST_HIGH + 7, theme.accent(), false);
        }
        // Under the grid: what the workstation lacks, if anything, then its notes, all wrapped to the panel
        int y = TOP + LIST_HIGH + 2 + (dial == null ? 0 : DIAL_ROW), lines = 0;
        if (!problem().isEmpty()) {
            for (String line : Lines.wrap(font, problem(), WIDTH - 16)) {
                if (lines++ < NOTE_LINES) graphics.text(font, line, 8, y + (lines - 1) * 10, theme.warn(), false);
            }
        }
        for (String note : choice.notes()) {
            for (String line : Lines.wrap(font, note, WIDTH - 16)) {
                if (lines++ < NOTE_LINES) graphics.text(font, line, 8, y + (lines - 1) * 10, theme.faint(), false);
            }
        }
    }
}
