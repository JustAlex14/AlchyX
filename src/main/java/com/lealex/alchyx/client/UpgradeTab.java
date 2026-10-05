package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.menu.UpgradeMenu;
import com.lealex.alchyx.menu.UpgradeSlot;
import com.lealex.alchyx.upgrade.UpgradeType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Draws a machine's upgrade tab: a corrupted obsidian plate right of the window (textures/gui/upgrade_tab.png,
 * 8 animated frames per size, 1 to 5 slots), the upgrades' faint emblems in empty slots, and their names on hover.
 * In the screen: {@code draw} before the window's background (the window covers the tab's left edge),
 * {@code hints} after it, and {@code contains} in hasClickedOutside so clicks on the tab don't drop the carried item.
 */
public final class UpgradeTab {
    private UpgradeTab() {}

    public static final int X = 175, Y = 5, WIDTH = 29;
    private static final Identifier TEXTURE = AlchyX.id("textures/gui/upgrade_tab.png");
    private static final int TEXTURE_W = 256, TEXTURE_H = 512, FRAMES = 8, FRAME_MS = 150;
    /** Where each size's frames start in the texture (index = slot count). */
    private static final int[] V = {0, 0, 30, 78, 144, 228};

    public static int height(int slots) {
        return 12 + 18 * slots;
    }

    private static int slots(AbstractContainerMenu menu) {
        return menu instanceof UpgradeMenu upgrades ? Math.min(5, upgrades.upgradeSlotCount()) : 0;
    }

    public static void draw(GuiGraphicsExtractor graphics, AbstractContainerMenu menu, int left, int top) {
        int slots = slots(menu);
        if (slots <= 0) return;
        int frame = (int) (Util.getMillis() / FRAME_MS % FRAMES);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, left + X, top + Y, frame * WIDTH, V[slots], WIDTH, height(slots),
                TEXTURE_W, TEXTURE_H);
    }

    /** Empty slots show their type's emblem (faint) and its name on hover. */
    public static void hints(GuiGraphicsExtractor graphics, AbstractContainerMenu menu, int left, int top, int mouseX, int mouseY) {
        for (Slot slot : menu.slots) {
            if (!(slot instanceof UpgradeSlot upgrade) || slot.hasItem()) continue;
            UpgradeType type = upgrade.type();
            int x = left + slot.x, y = top + slot.y;
            graphics.blit(RenderPipelines.GUI_TEXTURED, type.emblem().texture(), x, y, type.emblem().u(), type.emblem().v(), 16, 16, 256, 256);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                graphics.setTooltipForNextFrame(Component.translatableWithFallback(type.translationKey(), type.id().getPath()), mouseX, mouseY);
            }
        }
    }

    /** Is the mouse on the tab? */
    public static boolean contains(AbstractContainerMenu menu, double mouseX, double mouseY, int left, int top) {
        int slots = slots(menu);
        if (slots <= 0) return false;
        double x = mouseX - left - X, y = mouseY - top - Y;
        return x >= 0 && y >= 0 && x < WIDTH && y < height(slots);
    }

    /** The tab's area on screen, for JEI exclusion zones and the like. */
    public static Rect2i area(AbstractContainerMenu menu, int left, int top) {
        return new Rect2i(left + X, top + Y, WIDTH, height(Math.max(1, slots(menu))));
    }
}
