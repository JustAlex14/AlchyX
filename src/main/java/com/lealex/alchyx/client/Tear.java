package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * A tear that opens from one point: a list of {@link Stage}s, all centred on that point, from the first crack to
 * the gaping hole. How open it is picks the stage: one at a time, never two blended (the lips of the narrower one
 * would show as bright lines across the hole of the wider), so give it enough stages to open smoothly. Through the
 * hole shows the End's starfield, like the corruption rifts ({@link RiftDecal}). On blocks near the player, see
 * {@link NearLook.Rule#tear}; standing in the air (in a gate's frame), {@link #submit}.
 */
public final class Tear {
    private Tear() {}

    /**
     * One stage of a tear.
     *
     * @param picture a square picture (its own file, not a block texture): the cracks and the hole's lips,
     *                see-through everywhere else, the hole included
     * @param hole    a picture of the same shape whose opaque pixels are the hole (the starfield is drawn there);
     *                null for a stage with no hole yet
     */
    public record Stage(Identifier picture, @Nullable Identifier hole) {}

    /** Which stages show at an openness of 0 to 1: {the first one's index, how much of the next is blended in}. */
    public static float[] stage(int count, float open) {
        float at = Mth.clamp(open, 0.0F, 1.0F) * (count - 1);
        int stage = Math.min((int) at, count - 1);
        return new float[] {stage, stage < count - 1 ? at - stage : 0.0F};
    }

    /**
     * How open an unstable tear is at one instant: it shivers all the time and now and then, for a few ticks, jerks
     * wider or snaps half shut.
     */
    public static float unstable(float open, float time) {
        Vortex.Shake shake = Vortex.shake(time, 3);
        return Mth.clamp(open * shake.scale() + 0.03F * Mth.sin(time * 1.3F), 0.0F, 1.0F);
    }

    private static final float[] NO_HOLE = new float[0];
    private static final Map<Identifier, float[]> HOLES = new ConcurrentHashMap<>();

    /**
     * A stage's hole as strips, 4 numbers each: {left, right, top, bottom}, all 0 to 1 across the picture (top = 0).
     * Read once per picture.
     */
    public static float[] hole(Stage stage) {
        if (stage.hole() == null) return NO_HOLE;
        return HOLES.computeIfAbsent(stage.hole(), id -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage image = NativeImage.read(in)) {
                int width = image.getWidth(), height = image.getHeight();
                List<Float> strips = new ArrayList<>();
                for (int y = 0; y < height; y++) {
                    int start = -1;
                    for (int x = 0; x <= width; x++) {
                        boolean open = x < width && (image.getPixel(x, y) >>> 24) > 127;
                        if (open && start < 0) start = x;
                        if (!open && start >= 0) {
                            strips.add(start / (float) width);
                            strips.add(x / (float) width);
                            strips.add(y / (float) height);
                            strips.add((y + 1) / (float) height);
                            start = -1;
                        }
                    }
                }
                float[] out = new float[strips.size()];
                for (int i = 0; i < out.length; i++) out[i] = strips.get(i);
                return out;
            } catch (Exception e) {
                AlchyX.LOGGER.warn("Couldn't read {}: that tear is drawn without its void", id, e);
                return NO_HOLE;
            }
        });
    }

    /** The stage whose hole shows at this openness (the nearer of the two blended ones). */
    public static Stage holeStage(List<Stage> stages, float open) {
        float[] at = stage(stages.size(), open);
        return stages.get(Math.min(stages.size() - 1, (int) at[0] + (at[1] >= 0.5F ? 1 : 0)));
    }

    /**
     * A skin stretched over an opening for a tear to open in, so the tear has something to tear (standing in the air
     * alone, its cracks float on nothing). A rectangle in the tear's plane; the tear's hole is cut out of it, and the
     * tear is clipped to it.
     *
     * @param texture a standalone picture, mostly opaque
     * @param left    the rectangle, from the tear's point: left and bottom are usually negative
     * @param tile    how many blocks the whole picture covers (the rectangle must not be larger)
     * @param argb    its tint; the alpha fades it in
     */
    public record Veil(Identifier texture, float left, float right, float bottom, float top, float tile, int argb) {}

    /** As the full form, with nothing around the tear. */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, List<Stage> stages, float open, float half,
                              float time, boolean unstable) {
        submit(poseStack, collector, stages, open, half, time, unstable, null);
    }

    /**
     * Draws a tear on an upright square: in the pose's XY plane, its point at the origin, seen from both sides (one
     * sheet: from behind it is the same tear, mirrored).
     *
     * @param stages   closed to open
     * @param open     0 to 1
     * @param half     half the square's width
     * @param time     ticks with the partial tick, for an unstable tear
     * @param unstable whether it shivers and jerks (see {@link #unstable})
     * @param veil     what it tears, or null for a tear standing alone
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, List<Stage> stages, float open, float half,
                              float time, boolean unstable, @Nullable Veil veil) {
        if (stages.isEmpty()) return;
        Vortex.Shake shake = unstable ? Vortex.shake(time, 0) : new Vortex.Shake(time, 1, 0, 0);
        float now = unstable ? unstable(open, time) : open;
        float slipA = shake.a() * half, slipB = shake.b() * half;
        Stage stage = holeStage(stages, now);
        float left = veil == null ? -Float.MAX_VALUE : veil.left(), right = veil == null ? Float.MAX_VALUE : veil.right();
        float bottom = veil == null ? -Float.MAX_VALUE : veil.bottom(), top = veil == null ? Float.MAX_VALUE : veil.top();

        // The hole, row by row, kept inside the veil: {x0, x1, y0, y1, row}
        float[] strips = hole(stage);
        List<float[]> holes = new ArrayList<>(strips.length / 4);
        for (int i = 0; i < strips.length; i += 4) {
            float x0 = Math.max(left, (strips[i] * 2 - 1) * half + slipA), x1 = Math.min(right, (strips[i + 1] * 2 - 1) * half + slipA);
            float y1 = Math.min(top, (1 - strips[i + 2] * 2) * half + slipB), y0 = Math.max(bottom, (1 - strips[i + 3] * 2) * half + slipB);
            if (x0 < x1 && y0 < y1) holes.add(new float[] {x0, x1, y0, y1, strips[i + 2]});
        }

        // Nothing: the End's starfield through the hole. The pictures keep clear of it, so no order matters.
        if (!holes.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.endPortal(), (pose, buffer) -> {
                for (float[] h : holes) {
                    float[][] q = {{h[0], h[2]}, {h[1], h[2]}, {h[1], h[3]}, {h[0], h[3]}};
                    for (int pass = 0; pass < 2; pass++) {
                        for (int k = 0; k < 4; k++) {
                            float[] c = q[pass == 0 ? k : 3 - k];
                            buffer.addVertex(pose, c[0], c[1], 0);
                        }
                    }
                }
            });
        }

        if (veil != null && (veil.argb() >>> 24) > 0) {
            // The veil is everything in its rectangle that is not hole: the rows of the hole come top to bottom,
            // left to right
            List<float[]> skin = new ArrayList<>();
            float done = top, cursor = left;
            float[] last = null;
            for (float[] h : holes) {
                if (last == null || h[4] != last[4]) {
                    if (last != null && cursor < right) skin.add(new float[] {cursor, right, last[2], last[3]});
                    if (h[3] < done) skin.add(new float[] {left, right, h[3], done});
                    done = h[2];
                    cursor = left;
                }
                if (h[0] > cursor) skin.add(new float[] {cursor, h[0], h[2], h[3]});
                cursor = h[1];
                last = h;
            }
            if (last != null && cursor < right) skin.add(new float[] {cursor, right, last[2], last[3]});
            if (bottom < done) skin.add(new float[] {left, right, bottom, done});
            int tint = veil.argb();
            float tile = veil.tile();
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(veil.texture()), (pose, buffer) -> {
                for (float[] s : skin) {
                    float[][] q = {{s[0], s[2]}, {s[1], s[2]}, {s[1], s[3]}, {s[0], s[3]}};
                    for (int pass = 0; pass < 2; pass++) {
                        for (int k = 0; k < 4; k++) {
                            float[] c = q[pass == 0 ? k : 3 - k];
                            buffer.addVertex(pose, c[0], c[1], 0).setColor(tint).setUv((c[0] - left) / tile, (top - c[1]) / tile)
                                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
                        }
                    }
                }
            });
        }

        // The picture of the same stage as the hole, alone. Over a veil it is kept inside it, and drawn a hair off
        // each of its sides (two sheets in the same plane would fight)
        float x0 = Math.max(left, slipA - half), x1 = Math.min(right, slipA + half);
        float y0 = Math.max(bottom, slipB - half), y1 = Math.min(top, slipB + half);
        if (x0 >= x1 || y0 >= y1) return;
        float[][] corners = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        float[] depths = veil == null ? new float[] {0} : new float[] {-0.004F, 0.004F};
        int color = 0xFFFFFFFF;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(stage.picture()), (pose, buffer) -> {
            for (float depth : depths) {
                for (int pass = 0; pass < 2; pass++) { // both windings: seen whatever the culling
                    for (int i = 0; i < 4; i++) {
                        float[] c = corners[pass == 0 ? i : 3 - i];
                        buffer.addVertex(pose, c[0], c[1], depth).setColor(color)
                                .setUv((c[0] - slipA + half) / (2 * half), (half - c[1] + slipB) / (2 * half))
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
                    }
                }
            }
        });
    }
}
