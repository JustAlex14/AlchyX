package com.lealex.alchyx.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/**
 * A flat square of a texture lying in the pose's XZ plane, its middle at the origin, seen from above and below, full
 * bright: a ritual circle on a floor, a ring tumbling round a block (turn the pose first). Feed it to
 * submitCustomGeometry with a translucent emissive render type of the circle's texture.
 */
public final class FlatDisc {
    private FlatDisc() {}

    /** Half side {@code r}, tinted {@code color} (ARGB). */
    public static void quad(PoseStack.Pose pose, VertexConsumer buffer, float r, int color) {
        float[][] corners = {{-r, -r, 0, 0}, {-r, r, 0, 1}, {r, r, 1, 1}, {r, -r, 1, 0}};
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 4; k++) {
                float[] c = corners[pass == 0 ? k : 3 - k];
                buffer.addVertex(pose, c[0], 0, c[1]).setColor(color).setUv(c[2], c[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, pass == 0 ? 1 : -1, 0);
            }
        }
    }
}
