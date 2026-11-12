package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.animation.Effect;
import com.lealex.alchyx.animation.StudioMark;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The item menu the Machine Studio opens for an item anchor: every item in the game (modded ones too, as they're all
 * in the item registry), with the machine's displays (input, output, result) first, and a search box over them.
 * Click an item to add it to the anchor's list (it shows them in turn through the work), click one in the list to
 * take it out; Done keeps the list, Cancel (or Escape) leaves it as it was.
 */
public class ItemPickerScreen extends Screen {
    private static final int CELL = 20, MARGIN = 12, TOP = 8;
    private static final int BACK = 0xE0101018, PANEL = 0xFF1A1A26, EDGE = 0xFF3C3550, ACCENT = 0xFFD9A7FF,
            FAINT = 0xFF9A93AA, WARN = 0xFFFFB866;

    private final Screen parent;
    private final Consumer<List<String>> done;
    private final @Nullable Consumer<String> single; // pick one: it goes back at once
    private final List<String> chosen;
    private @Nullable EditBox searchBox;
    private String search = "";
    private @Nullable List<String> found;
    private int scrolled;

    public ItemPickerScreen(Screen parent, List<String> start, Consumer<List<String>> done) {
        super(Component.literal("Pick items"));
        this.parent = parent;
        this.done = done;
        this.chosen = new ArrayList<>(start);
        this.single = null;
    }

    /** Picks one entry: a click on it hands it to {@code picked} and goes back. */
    public ItemPickerScreen(Screen parent, Consumer<String> picked) {
        this(parent, picked, false);
    }

    /** Picks one item; {@code blocksOnly}: only blocks (their items), no displays (the studio's Build tab). */
    public ItemPickerScreen(Screen parent, Consumer<String> picked, boolean blocksOnly) {
        super(Component.literal(blocksOnly ? "Pick a block" : "Pick an item"));
        this.parent = parent;
        this.done = list -> {};
        this.chosen = new ArrayList<>();
        this.single = picked;
        this.blocksOnly = blocksOnly;
    }

    private boolean blocksOnly;

    // ---- Layout ----

    private int columns() { return Mth.clamp((width - MARGIN * 2) / CELL, 4, 18); }
    private int left() { return (width - columns() * CELL) / 2; }
    private int listTop() { return TOP + 40; }
    private int gridTop() { return single != null ? listTop() : listTop() + CELL + 16; }
    private int gridRows() { return Math.max(1, (height - 34 - gridTop()) / CELL); }

    @Override
    protected void init() {
        int wide = Math.min(240, columns() * CELL);
        searchBox = addRenderableWidget(new EditBox(font, (width - wide) / 2, TOP + 14, wide, 16, Component.literal("Search")));
        searchBox.setHint(Component.literal("Search: stone, input, a mod's name..."));
        searchBox.setMaxLength(64);
        searchBox.setValue(search);
        searchBox.setResponder(text -> {
            search = text;
            found = null;
            scrolled = 0;
        });
        setInitialFocus(searchBox);
        int y = height - 26;
        if (single == null) addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish(true)).bounds(width / 2 - 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(false))
                .bounds(single == null ? width / 2 + 4 : width / 2 - 50, y, 100, 20).build());
    }

    private void finish(boolean keep) {
        if (keep) done.accept(List.copyOf(chosen));
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        finish(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- What it lists ----

    /** The entries for the search: the machine's displays first, then every item whose id or name holds it. */
    private List<String> found() {
        if (found != null) return found;
        String text = search.trim().toLowerCase(Locale.ROOT);
        List<String> entries = new ArrayList<>();
        for (String display : blocksOnly ? List.<String>of() : StudioMark.DISPLAYS) {
            if (text.isEmpty() || display.contains(text) || "display".contains(text)) entries.add(display);
        }
        String asId = text.replace(' ', '_');
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == Items.AIR || (blocksOnly && !(item instanceof net.minecraft.world.item.BlockItem))) continue;
            if (text.isEmpty() || id.toString().contains(asId)
                    || new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT).contains(text)) {
                entries.add(id.toString());
            }
        }
        found = entries;
        return entries;
    }

    private void add(String entry) {
        if (single != null) {
            single.accept(entry);
            minecraft.setScreen(parent);
            return;
        }
        if (chosen.size() < StudioMark.MAX_ITEMS) chosen.add(entry);
    }

    private int gridUnder(double x, double y) {
        int left = left(), top = gridTop();
        if (x < left || y < top) return -1;
        int column = (int) ((x - left) / CELL), row = (int) ((y - top) / CELL);
        if (column >= columns() || row >= gridRows()) return -1;
        int index = (scrolled + row) * columns() + column;
        return index < found().size() ? index : -1;
    }

    private int listUnder(double x, double y) {
        if (single != null) return -1;
        int left = left(), top = listTop();
        if (x < left || y < top || y >= top + CELL) return -1;
        int index = (int) ((x - left) / CELL);
        return index < chosen.size() ? index : -1;
    }

    // ---- Input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int entry = gridUnder(event.x(), event.y());
            if (entry >= 0) {
                add(found().get(entry));
                return true;
            }
            int listed = listUnder(event.x(), event.y());
            if (listed >= 0) {
                chosen.remove(listed);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int rows = (found().size() + columns() - 1) / columns();
        scrolled = Mth.clamp(scrolled - (int) Math.signum(scrollY) * 2, 0, Math.max(0, rows - gridRows()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == 257 || event.key() == 335) && searchBox != null && searchBox.isFocused()) { // enter: add the first match
            if (!found().isEmpty()) add(found().getFirst());
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- Drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int left = left(), right = left + columns() * CELL;
        graphics.text(font, single != null ? "Pick an item" : "Items for this anchor", left, TOP + 2, ACCENT, false);
        String count = chosen.size() + " / " + StudioMark.MAX_ITEMS;
        if (single == null) graphics.text(font, count, right - font.width(count), TOP + 2, chosen.size() >= StudioMark.MAX_ITEMS ? WARN : FAINT, false);

        // The list: shown in turn through the work
        int listTop = listTop();
        if (single == null) {
        graphics.fill(left - 2, listTop - 2, right + 2, listTop + CELL, PANEL);
        if (chosen.isEmpty()) {
            graphics.text(font, "Nothing yet: it floats what the machine shows as input", left + 2, listTop + 6, FAINT, false);
        }
        for (int i = 0; i < chosen.size(); i++) {
            int x = left + i * CELL + 1;
            drawEntry(graphics, font, chosen.get(i), x, listTop + 1);
        }
        }

        // The grid
        List<String> entries = found();
        int gridTop = gridTop(), rows = gridRows(), columns = columns();
        graphics.fill(left - 2, gridTop - 2, right + 2, gridTop + rows * CELL + 2, PANEL);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int index = (scrolled + row) * columns + column;
                if (index >= entries.size()) break;
                drawEntry(graphics, font, entries.get(index), left + column * CELL + 2, gridTop + row * CELL + 2);
            }
        }
        int allRows = (entries.size() + columns - 1) / columns;
        if (allRows > rows) { // where the grid is scrolled to
            int track = rows * CELL, bar = Math.max(8, track * rows / allRows);
            int at = (track - bar) * scrolled / Math.max(1, allRows - rows);
            graphics.fill(right + 4, gridTop, right + 6, gridTop + track, EDGE);
            graphics.fill(right + 4, gridTop + at, right + 6, gridTop + at + bar, ACCENT);
        }
        if (entries.isEmpty()) graphics.text(font, "Nothing matches", left + 4, gridTop + 6, FAINT, false);
        String help = single != null ? "Click: pick it  -  Enter: the first match"
                : "Click: add it  -  click one in the list: take it out  -  Enter: add the first match";
        graphics.text(font, help, (width - font.width(help)) / 2, gridTop + rows * CELL + 5, FAINT, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
        int entry = gridUnder(mouseX, mouseY), listed = listUnder(mouseX, mouseY);
        if (entry >= 0) {
            String id = entries.get(entry);
            List<Component> lines = new ArrayList<>(List.of(entryName(id)));
            if (id.contains(":")) lines.add(Component.literal(id).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal(single != null ? "Click: pick it" : chosen.size() >= StudioMark.MAX_ITEMS ? "The list is full" : "Click: add it")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.setTooltipForNextFrame(font, lines, Optional.empty(), mouseX, mouseY);
        } else if (listed >= 0) {
            graphics.setTooltipForNextFrame(font, List.of(entryName(chosen.get(listed)),
                    Component.literal("Click: take it out").withStyle(ChatFormatting.DARK_GRAY)), Optional.empty(), mouseX, mouseY);
        }
    }

    // ---- Entries (shared with the studio) ----

    /** What an entry shows: an item, or for a display a stand-in (a chest for input, a barrel for output, a hopper for result). */
    static ItemStack icon(String entry) {
        if (!entry.contains(":")) {
            return new ItemStack(switch (entry) {
                case "input" -> Items.CHEST;
                case "output" -> Items.BARREL;
                default -> Items.HOPPER;
            });
        }
        return Effect.FloatingItem.entryStack(entry, name -> ItemStack.EMPTY);
    }

    static Component entryName(String entry) {
        if (!entry.contains(":")) {
            return Component.literal(Character.toUpperCase(entry.charAt(0)) + entry.substring(1) + ": what the machine's display shows");
        }
        return icon(entry).getHoverName();
    }

    static void drawEntry(GuiGraphicsExtractor graphics, Font font, String entry, int x, int y) {
        graphics.item(icon(entry), x, y);
        if (!entry.contains(":")) { // a display: its name over the stand-in
            String tag = entry.equals("input") ? "IN" : entry.equals("output") ? "OUT" : "RES";
            graphics.text(font, tag, x + 17 - font.width(tag), y + 9, WARN, true);
        }
    }
}
