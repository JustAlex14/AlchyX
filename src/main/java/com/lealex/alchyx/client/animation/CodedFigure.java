package com.lealex.alchyx.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

/**
 * A figure drawn by code instead of an entity renderer (for poses or looks an entity can't do), used by animation
 * files with {@code "figure": "<id>"}. Register it with {@link Figures#register} during client setup.
 */
@FunctionalInterface
public interface CodedFigure {

    /**
     * What the figure may depend on: the machine display named by the effect's "display" (empty if none), whether
     * the machine works, the time in ticks (with partial tick) and the light where it stands.
     */
    record Context(ItemStack display, boolean working, float time, int light) {}

    /**
     * Draws the figure. The pose is at its feet and already turned like an entity's body (it faces the effect's
     * heading after the usual entity flip: {@code scale(-1, -1, 1)} then {@code translate(0, -1.501, 0)} for a
     * standard entity model). Scale and hover are applied already.
     */
    void submit(Context context, PoseStack poseStack, SubmitNodeCollector collector);
}
