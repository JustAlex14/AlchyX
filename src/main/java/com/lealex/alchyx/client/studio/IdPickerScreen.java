package com.lealex.alchyx.client.studio;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A searchable list of ids to pick one from (the Machine Studio's sounds, a spawner's mob...): modded ones too, as
 * they come from the game's registries. A click chooses one (and {@code tryOut} plays a sound to hear it), a double
 * click or Done picks it, Cancel (or Escape) leaves things as they were.
 */
public class IdPickerScreen extends Screen {
    private static final int BACK = 0xE0101018, PANEL = 0xFF1A1A26, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0, FAINT = 0xFF9A93AA;
    private static final int WIDE = 300, LINE = 12, TOP = 44;

    private final Screen parent;
    private final String heading;
    private final List<String> ids;
    private final @Nullable Consumer<String> tryOut;
    private final Consumer<String> picked;
    private @Nullable String chosen;
    private String search = "";
    private @Nullable List<String> found;
    private int scrolled;

    public IdPickerScreen(Screen parent, String heading, List<String> ids, @Nullable String current,
                          @Nullable Consumer<String> tryOut, Consumer<String> picked) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.ids = List.copyOf(ids);
        this.tryOut = tryOut;
        this.picked = picked;
        this.chosen = current;
    }

    private int left() { return (width - WIDE) / 2; }
    private int lines() { return Math.max(1, (height - TOP - 36) / LINE); }

    private List<String> found() {
        if (found != null) return found;
        String text = search.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        List<String> list = new ArrayList<>();
        for (String id : ids) if (text.isEmpty() || id.contains(text)) list.add(id);
        found = list;
        return list;
    }

    @Override
    protected void init() {
        EditBox box = addRenderableWidget(new EditBox(font, left(), 22, WIDE, 16, Component.literal("Search")));
        box.setHint(Component.literal("Search: piston, anvil, a mod's name..."));
        box.setMaxLength(64);
        box.setValue(search);
        box.setResponder(text -> {
            search = text;
            found = null;
            scrolled = 0;
        });
        setInitialFocus(box);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish(true)).bounds(width / 2 - 104, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(false)).bounds(width / 2 + 4, height - 26, 100, 20).build());
    }

    private void finish(boolean keep) {
        if (keep && chosen != null) picked.accept(chosen);
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

    private int lineUnder(double x, double y) {
        if (x < left() || x > left() + WIDE || y < TOP) return -1;
        int line = (int) ((y - TOP) / LINE);
        if (line >= lines()) return -1;
        int index = scrolled + line;
        return index < found().size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = lineUnder(event.x(), event.y());
        if (index >= 0 && event.button() == 0) {
            chosen = found().get(index);
            if (tryOut != null) tryOut.accept(chosen);
            if (doubleClick) finish(true);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrolled = Mth.clamp(scrolled - (int) Math.signum(scrollY) * 3, 0, Math.max(0, found().size() - lines()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == 257 || event.key() == 335) && !found().isEmpty()) { // enter: the chosen one, or the first match
            if (chosen == null || !found().contains(chosen)) chosen = found().getFirst();
            finish(true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int left = left();
        graphics.text(font, heading, left, 8, ACCENT, false);
        String now = chosen == null ? "" : chosen;
        graphics.text(font, now, left + WIDE - font.width(now), 8, TEXT, false);
        List<String> list = found();
        graphics.fill(left - 2, TOP - 2, left + WIDE + 2, TOP + lines() * LINE + 2, PANEL);
        int under = lineUnder(mouseX, mouseY);
        for (int line = 0; line < lines() && scrolled + line < list.size(); line++) {
            int index = scrolled + line, y = TOP + line * LINE;
            String id = list.get(index);
            boolean on = id.equals(chosen);
            if (on || index == under) graphics.fill(left - 2, y - 1, left + WIDE + 2, y + LINE - 1, on ? 0xFF3C3550 : 0xFF26263A);
            graphics.text(font, id, left + 2, y + 1, on ? ACCENT : TEXT, false);
        }
        if (list.isEmpty()) graphics.text(font, "Nothing matches", left + 2, TOP + 2, FAINT, false);
        String help = (tryOut != null ? "Click: hear it" : "Click: choose it") + "  -  double click or Done: pick it  -  wheel: scroll";
        graphics.text(font, help, (width - font.width(help)) / 2, height - 38, FAINT, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}
