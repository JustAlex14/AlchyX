package com.lealex.alchyx.compat.patchouli;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Vec3i;
import org.joml.Vector3f;
import vazkii.patchouli.api.IMultiblock;
import vazkii.patchouli.client.book.page.PageMultiblock;

import java.lang.reflect.Field;

/**
 * Replaces Patchouli's {@code patchouli:multiblock} page (for every book): the 26.1 port renders every preview at a
 * fixed 20 px per block around the origin, so anything wider than ~4 blocks is cropped ("too zoomed"). This page
 * scales the camera to fit the structure in the 99 px window and centres it vertically; everything else (Visualize,
 * text, name) is Patchouli's own. If a Patchouli update renames the fields, it silently falls back to vanilla behaviour.
 */
public class FittedMultiblockPage extends PageMultiblock {
    private static final float MAX_SCALE = 20, FIT = 88, PITCH = 30, WINDOW = 99;
    private static final Field CAMERA = field("camera"), MULTIBLOCK = field("multiblockObj");

    private static Field field(String name) {
        try {
            Field f = PageMultiblock.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float pticks) {
        fit();
        super.extractRenderState(graphics, mouseX, mouseY, pticks);
    }

    private void fit() {
        if (CAMERA == null || MULTIBLOCK == null) return;
        try {
            Camera camera = (Camera) CAMERA.get(this);
            IMultiblock multiblock = (IMultiblock) MULTIBLOCK.get(this);
            if (camera == null || multiblock == null) return;
            Vec3i size = multiblock.getSize();
            double diag = Math.sqrt(size.getX() * size.getX() + size.getZ() * size.getZ());
            double rad = Math.toRadians(PITCH);
            double height = size.getY() * Math.cos(rad) + diag * Math.sin(rad);
            float scale = (float) Math.min(MAX_SCALE, Math.min(FIT / diag, FIT / height));
            camera.setScale(scale);
            camera.setPitch(PITCH);
            // Blocks are drawn from y = 1 (the view offset puts the bottom layer there) to y = size + 1: aim the
            // camera at the middle of that column (the yaw spins around it, so it doesn't change the result).
            // The picture-in-picture origin is the BOTTOM centre of the window, not its centre, so the structure's
            // middle is lifted by half the window (view -y is up on screen).
            Vector3f origin = camera.blockTransform().transform(new Vector3f(0, size.getY() / 2f + 1, 0));
            origin.y += WINDOW / 2f / scale;
            camera.setSceneOrigin(origin);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }
}
