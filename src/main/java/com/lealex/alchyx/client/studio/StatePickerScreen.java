package com.lealex.alchyx.client.studio;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The state menu the Machine Studio opens for the "change" motion: every property of the block (vanilla or modded:
 * a trial spawner's "trial_spawner_state", a vault's "vault_state", "lit", "facing"...) with a button that goes
 * through its values, or "keep" (as it stands). Done gives the block that state while its motion plays; with every
 * property kept, the block stops changing.
 */
public class StatePickerScreen extends Screen {
    private static final int BACK = 0xE0101018, PANEL = 0xFF1A1A26, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0, FAINT = 0xFF9A93AA;
    private static final int ROW = 22, WIDE = 260;

    private final Screen parent;
    private final BlockState look;
    private final Consumer<Map<String, String>> done;
    private final Map<String, String> picked = new LinkedHashMap<>();
    private final List<Property<?>> properties;
    private int scrolled;

    public StatePickerScreen(Screen parent, BlockState look, Map<String, String> start, Consumer<Map<String, String>> done) {
        super(Component.literal("Pick a state"));
        this.parent = parent;
        this.look = look;
        this.done = done;
        this.properties = new ArrayList<>(look.getProperties());
        for (Property<?> property : properties) {
            String value = start.get(property.getName());
            if (value != null && property.getValue(value).isPresent()) picked.put(property.getName(), value);
        }
    }

    private int rowsShown() {
        return Math.max(1, (height - 90) / ROW);
    }

    @Override
    protected void init() {
        int left = (width - WIDE) / 2, y = 44;
        scrolled = Math.max(0, Math.min(scrolled, properties.size() - rowsShown()));
        for (int i = scrolled; i < properties.size() && i < scrolled + rowsShown(); i++) {
            Property<?> property = properties.get(i);
            String name = property.getName();
            Button button = addRenderableWidget(Button.builder(Component.literal(shown(name)), b -> {
                cycle(property);
                b.setMessage(Component.literal(shown(name)));
            }).bounds(left + WIDE - 120, y, 120, 20).build());
            button.setTooltip(Tooltip.create(Component.literal("Now: " + current(property) + ". Click: next value")));
            y += ROW;
        }
        int bottom = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish(true)).bounds(width / 2 - 154, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Keep all"), b -> {
            picked.clear();
            rebuildWidgets();
        }).bounds(width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(false)).bounds(width / 2 + 54, bottom, 100, 20).build());
    }

    private String shown(String name) {
        String value = picked.get(name);
        return value == null ? "keep" : value;
    }

    private static <T extends Comparable<T>> String current(Property<T> property, BlockState look) {
        return property.getName(look.getValue(property));
    }

    private String current(Property<?> property) {
        return current(property, look);
    }

    private static <T extends Comparable<T>> List<String> values(Property<T> property) {
        List<String> names = new ArrayList<>();
        for (T value : property.getPossibleValues()) names.add(property.getName(value));
        return names;
    }

    /** The next value: keep, then each value in turn, then keep again. */
    private void cycle(Property<?> property) {
        List<String> values = values(property);
        String now = picked.get(property.getName());
        int index = now == null ? -1 : values.indexOf(now);
        if (index + 1 < values.size()) picked.put(property.getName(), values.get(index + 1));
        else picked.remove(property.getName());
    }

    private void finish(boolean keep) {
        if (keep) done.accept(Map.copyOf(picked));
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
        int max = Math.max(0, properties.size() - rowsShown());
        int next = Math.max(0, Math.min(max, scrolled - (int) Math.signum(scrollY)));
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
        int left = (width - WIDE) / 2;
        graphics.item(new net.minecraft.world.item.ItemStack(look.getBlock().asItem()), left, 10);
        graphics.text(font, look.getBlock().getName().getString() + ": the state it changes to", left + 20, 10, ACCENT, false);
        graphics.text(font, "While its motion plays (Start / Stop here). \"keep\": as it stands", left + 20, 22, FAINT, false);
        int y = 44;
        for (int i = scrolled; i < properties.size() && i < scrolled + rowsShown(); i++) {
            Property<?> property = properties.get(i);
            graphics.fill(left - 4, y - 1, left + WIDE + 4, y + 21, PANEL);
            graphics.text(font, property.getName(), left, y + 6, picked.containsKey(property.getName()) ? TEXT : FAINT, false);
            String now = "(" + current(property) + ")";
            graphics.text(font, now, left + WIDE - 126 - font.width(now), y + 6, FAINT, false);
            y += ROW;
        }
        if (properties.size() > rowsShown()) graphics.text(font, "Wheel: more", left, y + 2, FAINT, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}
