package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a machine holds, shown while the player looks at it (its core or any block of its structure): its name and the
 * bars it lists ({@link MultiblockCoreBlockEntity#lookMeters}), each filled in its colour with the amount, on a dark
 * backing right of the crosshair. For every AlchyX machine of every mod (moved from AlchyForbidden at the author's
 * request); script machines show their tanks and energy. Hidden with the HUD and while a screen is open.
 */
public final class LookMeters {
    private LookMeters() {}

    private static final int BAR_W = 60, BAR_H = 4;
    /** The core last found for a looked-at block (the structure test runs only when the looked-at block changes). */
    private static @Nullable BlockPos lastPos;
    private static @Nullable MultiblockCoreBlockEntity lastCore;

    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, AlchyX.id("look_meters"), LookMeters::draw);
    }

    private static @Nullable MultiblockCoreBlockEntity coreAt(Minecraft minecraft, BlockPos pos) {
        if (pos.equals(lastPos) && (lastCore == null || !lastCore.isRemoved())) return lastCore;
        lastPos = pos.immutable();
        lastCore = null;
        if (minecraft.level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core) {
            lastCore = core;
        } else {
            for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
                if (core.getLevel() == minecraft.level && core.isFormed() && core.getBlockPos().distSqr(pos) < 24 * 24 && core.structureContains(pos)) {
                    lastCore = core;
                    break;
                }
            }
        }
        return lastCore;
    }

    /** The amount, then the label as given (its own style kept: a mod may pass an icon glyph). */
    private static Component text(MultiblockCoreBlockEntity.LookMeter meter) {
        Component amount = Component.literal(String.valueOf(meter.amount()));
        return meter.label() == null ? amount : amount.copy().append(" ").append(meter.label());
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null) return;
        if (com.lealex.alchyx.client.scene.SceneWalk.active() || com.lealex.alchyx.client.scene.CameraShots.any()) return; // a scene or a camera shot is playing (a walk, a chant, a boss entrance, the void pull): no HUD over it
        HitResult hit = minecraft.hitResult;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return;
        MultiblockCoreBlockEntity core = coreAt(minecraft, block.getBlockPos());
        if (core == null) return;
        List<MultiblockCoreBlockEntity.LookMeter> meters = new ArrayList<>();
        core.lookMeters(meters);
        if (meters.isEmpty()) return;
        var font = minecraft.font;
        int x = graphics.guiWidth() / 2 + 12, y = graphics.guiHeight() / 2 - 6 - meters.size() * 5;
        Component name = core.getBlockState().getBlock().getName();
        int width = font.width(name);
        for (var meter : meters) width = Math.max(width, BAR_W + 4 + font.width(text(meter)));
        graphics.fill(x - 3, y - 3, x + width + 4, y + 11 + meters.size() * 9, 0x900B0A10);
        graphics.text(font, name, x, y, 0xFFE6DDF2, true);
        int by = y + 12;
        for (var meter : meters) {
            int color = 0xFF000000 | lift(meter.argb());
            graphics.fill(x - 1, by - 1, x + BAR_W + 1, by + BAR_H + 1, 0xFF0B0A10);
            graphics.fill(x, by, x + BAR_W, by + BAR_H, 0xFF26232E);
            int filled = (int) Math.round(BAR_W * Math.min(1.0, meter.amount() / (double) Math.max(1, meter.cap())));
            graphics.fill(x, by, x + filled, by + BAR_H, color);
            graphics.text(font, text(meter), x + BAR_W + 4, by - 2, color, true);
            by += 9;
        }
    }

    /** A colour lifted a little for the dark backing. */
    private static int lift(int color) {
        int r = color >> 16 & 0xFF, g = color >> 8 & 0xFF, b = color & 0xFF;
        return (r + (255 - r) / 4) << 16 | (g + (255 - g) / 4) << 8 | (b + (255 - b) / 4);
    }
}
