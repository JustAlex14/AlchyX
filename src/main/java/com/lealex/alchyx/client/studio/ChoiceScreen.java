package com.lealex.alchyx.client.studio;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** A small window of choices, one button each (the studio's "Mini" button: import a multiblock, or build one). */
public class ChoiceScreen extends Screen {
    private static final int BACK = 0xE0101018, ACCENT = 0xFFD9A7FF, FAINT = 0xFF9A93AA, WIDE = 220;

    /** One choice: its button, what its tooltip says, and what it does (it opens a screen, or the parent comes back). */
    public record Choice(String label, @Nullable String tip, Runnable action) {}

    private final Screen parent;
    private final String heading, line;
    private final List<Choice> choices;

    public ChoiceScreen(Screen parent, String heading, String line, List<Choice> choices) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.line = line;
        this.choices = List.copyOf(choices);
    }

    @Override
    protected void init() {
        int x = (width - WIDE) / 2, y = Math.max(40, height / 2 - (choices.size() + 1) * 11);
        for (Choice choice : choices) {
            Button button = addRenderableWidget(Button.builder(Component.literal(choice.label()), b -> {
                minecraft.setScreen(parent);
                choice.action().run();
            }).bounds(x, y, WIDE, 18).build());
            if (choice.tip() != null) button.setTooltip(Tooltip.create(Component.literal(choice.tip())));
            y += 22;
        }
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x, y + 6, WIDE, 18).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int y = Math.max(40, height / 2 - (choices.size() + 1) * 11);
        graphics.text(font, heading, (width - font.width(heading)) / 2, y - 30, ACCENT, false);
        graphics.text(font, line, (width - font.width(line)) / 2, y - 17, FAINT, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}
