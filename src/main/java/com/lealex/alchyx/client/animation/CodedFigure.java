package com.lealex.alchyx.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A figure drawn by code instead of an entity renderer (for poses or looks an entity can't do), used by animation
 * files with {@code "figure": "<id>"}. Register it with {@link Figures#register} during client setup.
 */
@FunctionalInterface
public interface CodedFigure {

    /**
     * What the figure may depend on.
     *
     * @param display      the machine display named by the effect's "display" (empty if none)
     * @param working      whether the machine works
     * @param time         the time in ticks, with the partial tick
     * @param light        the light where it stands
     * @param owner        what it belongs to (a machine core, a miniature): a figure that remembers something from
     *                     one frame to the next (a book opening) keeps it per owner, in a weak map
     * @param at           where it stands in the world (in a miniature: where the miniature is)
     * @param yaw          the heading the pose was turned by (see {@link #submit})
     * @param partialTicks the partial tick alone
     */
    record Context(ItemStack display, boolean working, float time, int light, Object owner, Vec3 at, float yaw,
                   float partialTicks) {}

    /**
     * Draws the figure. The pose is at its feet and already turned like an entity's body (it faces the effect's
     * heading after the usual entity flip: {@code scale(-1, -1, 1)} then {@code translate(0, -1.501, 0)} for a
     * standard entity model). Scale and hover are applied already. A figure that isn't a body (a book, an orb) and
     * wants the plain block frame back turns by {@code context.yaw() - 180} degrees around Y first.
     *
     * @param camera for parts that face the viewer
     */
    void submit(Context context, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera);
}
