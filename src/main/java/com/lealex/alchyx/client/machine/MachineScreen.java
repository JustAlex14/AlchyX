package com.lealex.alchyx.client.machine;

import com.lealex.alchyx.machine.MachineLayout;
import com.lealex.alchyx.machine.MachineMenu;
import com.lealex.alchyx.machine.MachineSpec;
import com.lealex.alchyx.machine.ScriptedCoreBlockEntity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.text.NumberFormat;

/**
 * The generic GUI of script-defined machines, drawn from plain shapes and vanilla sprites so it fits any slot
 * layout (see {@link MachineLayout}): energy bar, tanks, slots, progress arrow, player inventory.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier ARROW_SPRITE = Identifier.withDefaultNamespace("container/furnace/burn_progress");

    private static final int PANEL = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, SHADOW = 0xFF555555, EDGE = 0xFF000000;
    private static final int SUNKEN_BACK = 0xFF373737, SUNKEN_EDGE = 0xFF8B8B8B;
    private static final int ENERGY_COLOR = 0xFFD8312A, LABEL = 0xFF404040;

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.layout().width, 166);
    }

    @Override
    protected void init() {
        super.init();
        inventoryLabelX = menu.layout().inventoryX + 1;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos, y = topPos, w = imageWidth, h = imageHeight;
        MachineLayout layout = menu.layout();
        MachineSpec spec = menu.spec();

        // Panel with a vanilla-like bevel
        graphics.fill(x, y, x + w, y + h, EDGE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        graphics.fill(x + 1, y + 1, x + w - 2, y + 3, LIGHT);
        graphics.fill(x + 1, y + 1, x + 3, y + h - 2, LIGHT);
        graphics.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, SHADOW);
        graphics.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, SHADOW);

        for (Slot slot : menu.slots) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x + slot.x - 1, y + slot.y - 1, 18, 18);
        }

        int arrowX = x + layout.arrowX, arrowY = y + layout.arrowY;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_SPRITE, arrowX, arrowY, 24, 16, 0.25F);
        int width = Mth.ceil(menu.progress() * 24.0F);
        if (width > 0) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_SPRITE, 24, 16, 0, 0, arrowX, arrowY, width, 16);

        int barY = y + layout.tankY();
        if (layout.energyX >= 0) {
            int bx = x + layout.energyX;
            sunken(graphics, bx, barY, MachineLayout.ENERGY_W, MachineLayout.TANK_H);
            long capacity = Math.max(1, menu.energyCapacity());
            int filled = (int) (MachineLayout.TANK_H * Math.min(1.0, menu.energy() / (double) capacity));
            if (filled > 0) graphics.fill(bx, barY + MachineLayout.TANK_H - filled, bx + MachineLayout.ENERGY_W, barY + MachineLayout.TANK_H, ENERGY_COLOR);
        }

        ScriptedCoreBlockEntity core = menu.core();
        for (int t = 0; t < spec.tanks(); t++) {
            int tx = x + layout.tankX[t];
            sunken(graphics, tx, barY, MachineLayout.TANK_W, MachineLayout.TANK_H);
            if (core == null) continue;
            int amount = core.getFluidAmount(t);
            int filled = Math.round(MachineLayout.TANK_H * Math.min(1F, amount / (float) spec.tankCapacity()));
            if (filled > 0) {
                int color = colorOf(core.getFluid(t));
                graphics.fillGradient(tx, barY + MachineLayout.TANK_H - filled, tx + MachineLayout.TANK_W, barY + MachineLayout.TANK_H, color, darker(color));
            }
        }
    }

    private static void sunken(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, LIGHT);
        graphics.fill(x - 1, y - 1, x + w, y + h, SUNKEN_EDGE);
        graphics.fill(x, y, x + w, y + h, SUNKEN_BACK);
    }

    /** A color for any fluid: water and lava as in vanilla, others from their id (placeholder, no texture lookup). */
    private static int colorOf(FluidResource fluid) {
        if (fluid.getFluid() == Fluids.WATER) return 0xFF3F76E4;
        if (fluid.getFluid() == Fluids.LAVA) return 0xFFE0661A;
        int hash = BuiltInRegistries.FLUID.getKey(fluid.getFluid()).hashCode();
        return 0xFF000000 | (0x40 + (hash & 0x7F)) << 16 | (0x40 + (hash >> 8 & 0x7F)) << 8 | (0x40 + (hash >> 16 & 0x7F));
    }

    private static int darker(int color) {
        int r = (color >> 16 & 0xFF) * 3 / 4, g = (color >> 8 & 0xFF) * 3 / 4, b = (color & 0xFF) * 3 / 4;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        MachineLayout layout = menu.layout();
        MachineSpec spec = menu.spec();
        NumberFormat format = NumberFormat.getIntegerInstance();
        if (layout.energyX >= 0 && isHovering(layout.energyX, layout.tankY(), MachineLayout.ENERGY_W, MachineLayout.TANK_H, mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(Component.literal(format.format(menu.energy()) + " / " + format.format(menu.energyCapacity()) + " FE"), mouseX, mouseY);
            return;
        }
        ScriptedCoreBlockEntity core = menu.core();
        if (core == null) return;
        for (int t = 0; t < spec.tanks(); t++) {
            if (isHovering(layout.tankX[t], layout.tankY(), MachineLayout.TANK_W, MachineLayout.TANK_H, mouseX, mouseY)) {
                FluidResource fluid = core.getFluid(t);
                Component name = fluid.isEmpty() ? Component.literal("Empty") : fluid.getHoverName();
                graphics.setTooltipForNextFrame(Component.empty().append(name).append(": " + format.format(core.getFluidAmount(t))
                        + " / " + format.format(spec.tankCapacity()) + " mB"), mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, LABEL, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL, false);
    }
}
