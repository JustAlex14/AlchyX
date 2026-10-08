package com.lealex.alchyx.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

/**
 * A whirl made of flat pictures turning one over the other around the same middle: a wormhole's throat with its
 * spiral arms, a whirlpool, a storm seen from above. Each {@link Layer} is a square texture (its own file, not a
 * block texture) whose middle is the whirl's middle; layers turning at different speeds give it depth.
 */
public final class Vortex {
    private Vortex() {}

    /**
     * @param texture a square picture, e.g. {@code mymod:textures/misc/arms.png}, see-through where the layers
     *                under it should show
     * @param turn    degrees per tick (negative: the other way; 0: still)
     * @param size    its width, in whirl widths (1 = as wide as the whirl; more = only its middle shows)
     */
    public record Layer(Identifier texture, float turn, float size) {}

    /** Where a point (a, b) from the whirl's middle falls in a layer's picture: {u, v}. */
    public static float[] uv(Layer layer, float time, float radius, float a, float b) {
        double angle = Math.toRadians((time * layer.turn()) % 360.0);
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float span = 2 * radius * layer.size();
        return new float[] {0.5F + (a * cos - b * sin) / span, 0.5F - (a * sin + b * cos) / span};
    }

    /**
     * What an unstable whirl does to one of its layers at one instant: the time it is drawn at (it skips), its size
     * (it twitches), and how far it slips off the middle (in whirl radii).
     */
    public record Shake(float time, float scale, float a, float b) {}

    private static float hash(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        return ((n ^ (n >>> 16)) & 0xFFFF) / 65535.0F;
    }

    /**
     * An unstable whirl: most of the time each layer only quivers, but every so often one of them, by itself, tears
     * loose for a few ticks: it jumps to another angle, swells or shrinks, slips sideways, then snaps back. Each
     * layer has its own fits, so the whirl seems about to come apart.
     */
    public static Shake shake(float time, int layer) {
        int fit = (int) (time / 5);                       // fits last 5 ticks
        float quiver = 1 + 0.02F * (float) Math.sin(time * 0.9F + layer * 2.1F);
        if (hash(fit, layer * 31 + 7) > 0.13F) return new Shake(time, quiver, 0, 0);
        int step = (int) (time / 2);                      // within a fit it changes every other tick
        return new Shake(time + (hash(step, layer + 11) - 0.5F) * 90,
                0.8F + 0.45F * hash(step, layer + 23),
                (hash(step, layer + 37) - 0.5F) * 0.26F, (hash(step, layer + 41) - 0.5F) * 0.26F);
    }

    /** As the full form, steady, the two sides almost touching. */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, List<Layer> layers, float time, float radius,
                              float halfA, float halfB, int argb) {
        submit(poseStack, collector, layers, time, radius, halfA, halfB, argb, 0.02F, false);
    }

    /**
     * Draws a whirl on an upright rectangle: in the pose's XY plane, its middle at the origin, seen from both sides.
     *
     * @param time     ticks, with the partial tick (keep it small: game time modulo a day)
     * @param radius   of the whirl; the rectangle may be smaller (it then shows the whirl's middle) or larger
     * @param halfA    half the rectangle's width (along X), {@code halfB} half its height
     * @param argb     tint and opacity of every layer
     * @param gap      how far each side stands from the plane (half a block and a bit puts them on the two faces of
     *                 a block-thick frame)
     * @param unstable whether its layers have fits (see {@link #shake})
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, List<Layer> layers, float time, float radius,
                              float halfA, float halfB, int argb, float gap, boolean unstable) {
        float[][] corners = {{-halfA, -halfB}, {halfA, -halfB}, {halfA, halfB}, {-halfA, halfB}};
        for (int face = 0; face < 2; face++) { // one stack of layers per side, each with its first layer at the back
            float out = face == 0 ? 1 : -1;
            for (int index = 0; index < layers.size(); index++) {
                Layer layer = layers.get(index);
                Shake shake = unstable ? shake(time, index) : new Shake(time, 1, 0, 0);
                float z = out * (gap + 0.002F * index);
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(layer.texture()), (pose, buffer) -> {
                    for (int pass = 0; pass < 2; pass++) { // both windings: seen whatever the culling
                        for (int k = 0; k < 4; k++) {
                            float[] c = corners[pass == 0 ? k : 3 - k];
                            float[] uv = uv(layer, shake.time(), radius * shake.scale(), c[0] * out - shake.a() * radius, c[1] - shake.b() * radius);
                            buffer.addVertex(pose, c[0], c[1], z).setColor(argb).setUv(uv[0], uv[1])
                                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, out);
                        }
                    }
                });
            }
        }
    }
}
