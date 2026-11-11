package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.animation.BlockLook;
import com.lealex.alchyx.animation.MotionSetup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The state menu the Machine Studio opens for the "change" motion, for one of its steps (the one showing at the
 * work bar's head, or a new one there): every property of the block (vanilla or modded: a trial spawner's
 * "trial_spawner_state", a vault's "vault_state", "lit", a cauldron's "level"...) with a button that goes through its
 * values, or "keep" (as it stands). A cauldron also gets "Holds": nothing, water, lava or powder snow. Set step keeps
 * it, New step here adds it as another step from the work bar's head, Remove step takes the step out.
 */
public class StatePickerScreen extends Screen {
    private static final int BACK = 0xE0101018, PANEL = 0xFF1A1A26, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0, FAINT = 0xFF9A93AA;
    private static final int ROW = 22, WIDE = 260;

    /** What was done: the step set, a new step added at the work bar's head, or the step taken out. */
    public enum Action { SET, ADD, REMOVE }

    private final Screen parent;
    private final BlockState look;
    private final String where;
    private final boolean editing;
    private final BiConsumer<Action, Map<String, String>> done;
    private final Map<String, String> picked = new LinkedHashMap<>();
    private final List<Block> swaps;
    private List<Property<?>> properties = List.of();
    private int scrolled;

    /**
     * @param where   which step this is, for the heading ("Step 2 of 3, from 1.5 s")
     * @param editing whether it's a step there already (else Set step adds it)
     */
    public StatePickerScreen(Screen parent, BlockState look, Map<String, String> start, String where, boolean editing,
                             BiConsumer<Action, Map<String, String>> done) {
        super(Component.literal("Pick a state"));
        this.parent = parent;
        this.look = look;
        this.where = where;
        this.editing = editing;
        this.done = done;
        this.swaps = MotionSetup.swaps(look);
        Block target = MotionSetup.target(look, start);
        if (target != look.getBlock()) picked.put("block", BuiltInRegistries.BLOCK.getKey(target).toString());
        properties = new ArrayList<>(target.getStateDefinition().getProperties());
        for (Property<?> property : properties) {
            String value = start.get(property.getName());
            if (value != null && property.getValue(value).isPresent()) picked.put(property.getName(), value);
        }
    }

    /** The block the step makes (a cauldron may become another), as it would stand before its own values. */
    private BlockState base() {
        Block target = MotionSetup.target(look, picked);
        return target == look.getBlock() ? look : BlockLook.apply(look, Map.of("block", BuiltInRegistries.BLOCK.getKey(target).toString()));
    }

    private int rows() {
        return properties.size() + (swaps.isEmpty() ? 0 : 1);
    }

    private int rowsShown() {
        return Math.max(1, (height - 90) / ROW);
    }

    @Override
    protected void init() {
        int left = (width - WIDE) / 2, y = 44;
        scrolled = Math.max(0, Math.min(scrolled, rows() - rowsShown()));
        for (int row = scrolled; row < rows() && row < scrolled + rowsShown(); row++) {
            if (!swaps.isEmpty() && row == 0) {
                Button holds = addRenderableWidget(Button.builder(Component.literal(holdsName()), b -> {
                    cycleHolds();
                    rebuildWidgets();
                }).bounds(left + WIDE - 120, y, 120, 20).build());
                holds.setTooltip(Tooltip.create(Component.literal("What the cauldron holds in this step. Click: next")));
            } else {
                Property<?> property = properties.get(row - (swaps.isEmpty() ? 0 : 1));
                String name = property.getName();
                Button button = addRenderableWidget(Button.builder(Component.literal(shown(name)), b -> {
                    cycle(property);
                    b.setMessage(Component.literal(shown(name)));
                }).bounds(left + WIDE - 120, y, 120, 20).build());
                button.setTooltip(Tooltip.create(Component.literal("Now: " + current(property) + ". Click: next value")));
            }
            y += ROW;
        }
        int bottom = height - 28, button = 80;
        int x = width / 2 - (button * 4 + 12) / 2;
        Button set = addRenderableWidget(Button.builder(Component.literal(editing ? "Set step" : "Add step"),
                b -> finish(editing ? Action.SET : Action.ADD)).bounds(x, bottom, button, 20).build());
        set.setTooltip(Tooltip.create(Component.literal(editing ? "This step takes these values" : "A step from the work bar's head")));
        Button add = addRenderableWidget(Button.builder(Component.literal("New step here"), b -> finish(Action.ADD))
                .bounds(x + button + 4, bottom, button, 20).build());
        add.setTooltip(Tooltip.create(Component.literal("Keep this step, and add these values as another one from the work bar's head")));
        add.active = editing;
        Button remove = addRenderableWidget(Button.builder(Component.literal("Remove step"), b -> finish(Action.REMOVE))
                .bounds(x + 2 * (button + 4), bottom, button, 20).build());
        remove.active = editing;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(null)).bounds(x + 3 * (button + 4), bottom, button, 20).build());
    }

    private String holdsName() {
        String id = picked.get("block");
        if (id == null) return "keep";
        return switch (id) {
            case "minecraft:cauldron" -> "nothing";
            case "minecraft:water_cauldron" -> "water";
            case "minecraft:lava_cauldron" -> "lava";
            case "minecraft:powder_snow_cauldron" -> "powder snow";
            default -> id;
        };
    }

    /** Keep, then each block the cauldron may become (other than its own); the properties follow the block. */
    private void cycleHolds() {
        List<String> ids = new ArrayList<>();
        for (Block block : swaps) if (block != look.getBlock()) ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        String now = picked.get("block");
        int index = now == null ? -1 : ids.indexOf(now);
        if (index + 1 < ids.size()) picked.put("block", ids.get(index + 1));
        else picked.remove("block");
        Block target = MotionSetup.target(look, picked);
        properties = new ArrayList<>(target.getStateDefinition().getProperties());
        picked.keySet().removeIf(name -> !name.equals("block") && target.getStateDefinition().getProperty(name) == null);
    }

    private String shown(String name) {
        String value = picked.get(name);
        return value == null ? "keep" : value;
    }

    private static <T extends Comparable<T>> String current(Property<T> property, BlockState look) {
        return look.hasProperty(property) ? property.getName(look.getValue(property)) : "?";
    }

    private String current(Property<?> property) {
        return current(property, base());
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

    private void finish(Action action) {
        if (action != null) done.accept(action, Map.copyOf(picked));
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        finish(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, rows() - rowsShown());
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
        graphics.text(font, look.getBlock().getName().getString() + ": " + where, left + 20, 10, ACCENT, false);
        graphics.text(font, "It looks so from this keyframe to the next. \"keep\": as it stands", left + 20, 22, FAINT, false);
        int y = 44;
        for (int row = scrolled; row < rows() && row < scrolled + rowsShown(); row++) {
            graphics.fill(left - 4, y - 1, left + WIDE + 4, y + 21, PANEL);
            if (!swaps.isEmpty() && row == 0) {
                graphics.text(font, "Holds", left, y + 6, picked.containsKey("block") ? TEXT : FAINT, false);
            } else {
                Property<?> property = properties.get(row - (swaps.isEmpty() ? 0 : 1));
                graphics.text(font, property.getName(), left, y + 6, picked.containsKey(property.getName()) ? TEXT : FAINT, false);
                String now = "(" + current(property) + ")";
                graphics.text(font, now, left + WIDE - 126 - font.width(now), y + 6, FAINT, false);
            }
            y += ROW;
        }
        if (rows() > rowsShown()) graphics.text(font, "Wheel: more", left, y + 2, FAINT, false);
        if (rows() == 0) graphics.text(font, "Nothing to change on this block", left, 46, FAINT, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}
