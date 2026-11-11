package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.animation.StudioMark;
import com.lealex.alchyx.animation.StudioMark.ItemShow;
import com.lealex.alchyx.animation.StudioMark.Style;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The item list of one item anchor (Machine Studio): each item on its own row, with when it shows (from / until, in
 * percent of the work, with a little bar of the work under it) and how it moves while it shows (spins, stays still,
 * bobs, or travels to the anchor's other spot through its time). Items can overlap in time (two at once), be moved
 * up and down, taken out, or added from the item menu. Done keeps the list, Cancel leaves it as it was.
 */
public class ItemListScreen extends Screen {
    private static final int BACK = 0xE0101018, PANEL = 0xFF1A1A26, EDGE = 0xFF3C3550, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0,
            FAINT = 0xFF9A93AA, GOOD = 0xFF8BE39B;
    private static final int WIDE = 336, ROW = 30, TOP = 36, STEP = 5;

    private final Screen parent;
    private final boolean canTravel;
    private final BiConsumer<List<String>, List<ItemShow>> done;
    private final List<String> items;
    private final List<ItemShow> shows;
    private int scrolled;

    public ItemListScreen(Screen parent, StudioMark mark, BiConsumer<List<String>, List<ItemShow>> done) {
        super(Component.literal("Items of this anchor"));
        this.parent = parent;
        this.canTravel = mark.to().isPresent();
        this.done = done;
        this.items = new ArrayList<>(mark.items());
        this.shows = new ArrayList<>();
        for (int i = 0; i < mark.items().size(); i++) shows.add(mark.show(i));
    }

    private int left() { return (width - WIDE) / 2; }
    private int rowsShown() { return Math.max(1, (height - TOP - 40) / ROW); }

    @Override
    protected void init() {
        scrolled = Math.max(0, Math.min(scrolled, items.size() - rowsShown()));
        int left = left();
        for (int row = 0; row < rowsShown() && scrolled + row < items.size(); row++) {
            int i = scrolled + row, y = TOP + row * ROW;
            int x = left + 112;
            // From: - value +
            small("-", x, y, "Shows earlier", () -> shows.set(i, shows.get(i).withFrom(Math.max(0, shows.get(i).from() - STEP))));
            small("+", x + 44, y, "Shows later", () -> {
                ItemShow show = shows.get(i);
                int from = Math.min(100, show.from() + STEP);
                shows.set(i, new ItemShow(from, Math.max(from, show.until()), show.style()));
            });
            x += 64;
            // Until: - value +
            small("-", x, y, "Goes away earlier", () -> {
                ItemShow show = shows.get(i);
                int until = Math.max(0, show.until() - STEP);
                shows.set(i, new ItemShow(Math.min(show.from(), until), until, show.style()));
            });
            small("+", x + 44, y, "Goes away later", () -> shows.set(i, shows.get(i).withUntil(Math.min(100, shows.get(i).until() + STEP))));
            x += 66;
            Style style = shows.get(i).style();
            Button styleButton = addRenderableWidget(Button.builder(Component.literal(styleName(style)), b -> {
                shows.set(i, shows.get(i).withStyle(nextStyle(shows.get(i).style())));
                rebuildWidgets();
            }).bounds(x, y, 46, 18).build());
            styleButton.setTooltip(Tooltip.create(Component.literal(switch (style) {
                case SPIN -> "Spins in place. Click: next way of moving";
                case STILL -> "Stays still. Click: next way of moving";
                case BOB -> "Bobs up and down. Click: next way of moving";
                case TRAVEL -> canTravel ? "Travels to the anchor's other spot through its time. Click: next way of moving"
                        : "Travels once the anchor has a spot to go to (Travel in the studio); spins until then";
            })));
            x += 50;
            small("^", x, y, "Move it up", () -> swap(i, i - 1)).active = i > 0;
            small("v", x + 16, y, "Move it down", () -> swap(i, i + 1)).active = i < items.size() - 1;
            small("x", x + 32, y, "Take it out", () -> {
                items.remove(i);
                shows.remove(i);
            });
        }
        int bottom = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Add item..."), b -> minecraft.setScreen(new ItemPickerScreen(this, this::add)))
                .bounds(width / 2 - 166, bottom, 80, 20).build()).active = items.size() < StudioMark.MAX_ITEMS;
        Button even = addRenderableWidget(Button.builder(Component.literal("One by one"), b -> {
            List<ItemShow> share = StudioMark.evenShows(items.size(), canTravel);
            for (int i = 0; i < shows.size(); i++) shows.set(i, new ItemShow(share.get(i).from(), share.get(i).until(), shows.get(i).style()));
            rebuildWidgets();
        }).bounds(width / 2 - 82, bottom, 80, 20).build());
        even.setTooltip(Tooltip.create(Component.literal("They share the work: one after another, in this order")));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish(true)).bounds(width / 2 + 2, bottom, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(false)).bounds(width / 2 + 86, bottom, 80, 20).build());
    }

    private Button small(String label, int x, int y, String tip, Runnable change) {
        Button button = addRenderableWidget(Button.builder(Component.literal(label), b -> {
            change.run();
            rebuildWidgets();
        }).bounds(x, y, 14, 18).build());
        button.setTooltip(Tooltip.create(Component.literal(tip)));
        return button;
    }

    private static String styleName(Style style) {
        return switch (style) {
            case SPIN -> "Spin";
            case STILL -> "Still";
            case BOB -> "Bob";
            case TRAVEL -> "Travel";
        };
    }

    private static Style nextStyle(Style style) {
        Style[] all = Style.values();
        return all[(style.ordinal() + 1) % all.length];
    }

    private void swap(int a, int b) {
        if (a < 0 || b < 0 || a >= items.size() || b >= items.size()) return;
        String item = items.get(a);
        items.set(a, items.get(b));
        items.set(b, item);
        ItemShow show = shows.get(a);
        shows.set(a, shows.get(b));
        shows.set(b, show);
    }

    /** A new item comes after the last one: in the second half of the time left after the last one starts. */
    private void add(String entry) {
        if (items.size() >= StudioMark.MAX_ITEMS) return;
        Style style = canTravel ? Style.TRAVEL : Style.SPIN;
        if (shows.isEmpty()) {
            shows.add(new ItemShow(0, 100, style));
        } else {
            ItemShow last = shows.getLast();
            int from = Math.min(100, last.from() + Math.max(1, (100 - last.from()) / 2));
            if (last.until() > from) shows.set(shows.size() - 1, last.withUntil(from));
            shows.add(new ItemShow(from, 100, last.style()));
        }
        items.add(entry);
        scrolled = Math.max(0, items.size() - rowsShown());
    }

    private void finish(boolean keep) {
        if (keep) done.accept(List.copyOf(items), List.copyOf(shows));
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

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int next = Math.max(0, Math.min(Math.max(0, items.size() - rowsShown()), scrolled - (int) Math.signum(scrollY)));
        if (next != scrolled) {
            scrolled = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int left = left();
        graphics.text(font, "Items of this anchor", left, 8, ACCENT, false);
        graphics.text(font, "Each shows from / until (% of the work), and moves its own way", left, 19, FAINT, false);
        graphics.text(font, "From", left + 128, TOP - 10, FAINT, false);
        graphics.text(font, "Until", left + 190, TOP - 10, FAINT, false);
        if (items.isEmpty()) graphics.text(font, "No items: it floats the input. Add item... to start", left, TOP + 6, FAINT, false);
        for (int row = 0; row < rowsShown() && scrolled + row < items.size(); row++) {
            int i = scrolled + row, y = TOP + row * ROW;
            ItemShow show = shows.get(i);
            graphics.fill(left - 4, y - 2, left + WIDE + 4, y + ROW - 4, PANEL);
            String entry = items.get(i);
            ItemPickerScreen.drawEntry(graphics, font, entry, left, y + 1);
            String name = ItemPickerScreen.entryName(entry).getString();
            if (!entry.contains(":")) name = entry; // a display: its short name
            while (font.width(name) > 86 && name.length() > 1) name = name.substring(0, name.length() - 1);
            graphics.text(font, name, left + 20, y + 5, TEXT, false);
            String from = show.from() + "%", until = show.until() + "%";
            graphics.text(font, from, left + 112 + 29 - font.width(from) / 2, y + 5, TEXT, false);
            graphics.text(font, until, left + 176 + 29 - font.width(until) / 2, y + 5, TEXT, false);
            // Its time in the work
            int barLeft = left + 20, barRight = left + WIDE - 4, span = barRight - barLeft;
            graphics.fill(barLeft, y + 21, barRight, y + 23, EDGE);
            graphics.fill(barLeft + span * show.from() / 100, y + 21, Math.max(barLeft + span * show.from() / 100 + 1, barLeft + span * show.until() / 100),
                    y + 23, show.style() == Style.TRAVEL ? 0xFF66B3FF : GOOD);
        }
        if (items.size() > rowsShown()) graphics.text(font, "Wheel: more", left, TOP + rowsShown() * ROW, FAINT, false);
        String count = items.size() + " / " + StudioMark.MAX_ITEMS;
        graphics.text(font, count, left + WIDE - font.width(count), 8, FAINT, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}
