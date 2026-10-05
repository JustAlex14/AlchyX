package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A rift hanging in the air, always facing the camera: the corruption rifts' tear (textures/misc/corruption_rift_*,
 * 4 variants x 8 opening stages), drawn in three layers (broken edge, the End's starfield through the tear, glowing
 * lips). For formation animations: a core tearing reality open, a block coming back through it. Client only.
 */
public final class RiftDecal {
    private RiftDecal() {}

    private static final Identifier RIM = AlchyX.id("textures/misc/corruption_rift_rim.png");
    private static final Identifier GLOW = AlchyX.id("textures/misc/corruption_rift_glow.png");
    private static final Identifier VOID = AlchyX.id("textures/misc/corruption_rift_void.png");
    public static final int VARIANTS = 4, STAGES = 8;

    /**
     * Draws a rift centered on the pose's origin, {@code size} blocks wide, {@code open} from 0 (a closed crack) to
     * 1 (torn open), its lips glowing {@code color} (RGB). Nothing when open <= 0.
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
                              float size, int variant, float open, int color) {
        if (open <= 0) return;
        float stages = Mth.clamp(open, 0, 1) * (STAGES - 1);
        int stage = Math.min((int) stages, STAGES - 1);
        float blend = stage < STAGES - 1 ? stages - stage : 0;
        int v = Math.floorMod(variant, VARIANTS);
        float fade = Mth.clamp(open * 4, 0, 1); // a closed crack fades in and out
        poseStack.pushPose();
        poseStack.mulPose(camera.orientation);
        poseStack.scale(size, size, size);
        int rim = (int) (255 * fade) << 24 | 0xFFFFFF, glow = (int) (255 * fade) << 24 | (color & 0xFFFFFF);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(RIM), (pose, buffer) -> {
            tile(pose, buffer, v, stage, alpha(rim, 1 - blend));
            if (blend > 0.01F) tile(pose, buffer, v, stage + 1, alpha(rim, blend));
        });
        if (fade >= 1) {
            int[] runs = voidRuns()[v * STAGES + (blend >= 0.5F ? stage + 1 : stage)];
            collector.submitCustomGeometry(poseStack, RenderTypes.endPortal(), (pose, buffer) -> {
                for (int i = 0; i < runs.length; i += 3) {
                    float a0 = runs[i + 1] / 16F, a1 = runs[i + 2] / 16F, b0 = 1 - (runs[i] + 1) / 16F, b1 = 1 - runs[i] / 16F;
                    float[][] q = {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}};
                    for (int pass = 0; pass < 2; pass++) {
                        for (int k = 0; k < 4; k++) {
                            float[] c = q[pass == 0 ? k : 3 - k];
                            buffer.addVertex(pose, c[0] - 0.5F, c[1] - 0.5F, 0.001F);
                        }
                    }
                }
            });
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(GLOW), (pose, buffer) -> {
            tile(pose, buffer, v, stage, alpha(glow, 1 - blend));
            if (blend > 0.01F) tile(pose, buffer, v, stage + 1, alpha(glow, blend));
        });
        poseStack.popPose();
    }

    private static int alpha(int color, float share) {
        return Math.round((color >>> 24) * share) << 24 | (color & 0xFFFFFF);
    }

    /** One tile of the atlas on the unit square around the origin, both windings. */
    private static void tile(PoseStack.Pose pose, VertexConsumer buffer, int variant, int stage, int color) {
        float u0 = variant / (float) VARIANTS, u1 = (variant + 1) / (float) VARIANTS;
        float v0 = stage / (float) STAGES, v1 = (stage + 1) / (float) STAGES;
        float[][] q = {{-0.5F, -0.5F, u0, v1}, {0.5F, -0.5F, u1, v1}, {0.5F, 0.5F, u1, v0}, {-0.5F, 0.5F, u0, v0}};
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 4; k++) {
                float[] c = q[pass == 0 ? k : 3 - k];
                buffer.addVertex(pose, c[0], c[1], 0).setColor(color).setUv(c[2], c[3])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
            }
        }
    }

    private static int[][] voidRuns;

    /** Per variant * STAGES + stage, the torn-open pixels as row runs {y, x0, x1}; read once. */
    private static int[][] voidRuns() {
        if (voidRuns != null) return voidRuns;
        int[][] runs = new int[VARIANTS * STAGES][0];
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(VOID); NativeImage image = NativeImage.read(in)) {
            for (int tile = 0; tile < VARIANTS * STAGES; tile++) {
                int v = tile / STAGES, st = tile % STAGES;
                List<Integer> out = new ArrayList<>();
                for (int y = 0; y < 16; y++) {
                    int start = -1;
                    for (int x = 0; x <= 16; x++) {
                        boolean open = x < 16 && (image.getPixel(v * 16 + x, st * 16 + y) >>> 24) > 127;
                        if (open && start < 0) start = x;
                        if (!open && start >= 0) {
                            out.add(y);
                            out.add(start);
                            out.add(x);
                            start = -1;
                        }
                    }
                }
                runs[tile] = out.stream().mapToInt(Integer::intValue).toArray();
            }
        } catch (Exception e) {
            AlchyX.LOGGER.warn("Couldn't read {}: rifts drawn without their void", VOID, e);
        }
        voidRuns = runs;
        return runs;
    }
}
