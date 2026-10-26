package com.lealex.alchyx.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

/**
 * A round wall standing on the pose's XZ plane round its origin, seen from inside and out, full bright: an arena's
 * edge, a barrier of light. The texture runs round it (u, sliding with {@code u0}) and from the foot (v 1) to the top
 * (v 0). Feed it to submitCustomGeometry with a translucent emissive render type of the wall's texture.
 */
public final class RingWall {
    private RingWall() {}

    /**
     * @param radius   the wall's distance from the middle
     * @param height   how tall it stands (0 draws nothing)
     * @param u0       where the texture starts round it (slide it with time to make it flow)
     * @param repeat   how many times the texture goes round
     * @param segments flat pieces the circle is made of
     */
    public static void band(PoseStack.Pose pose, VertexConsumer buffer, float radius, float height, float u0, float repeat,
            int segments, int color) {
        if (height <= 0) return;
        for (int i = 0; i < segments; i++) {
            float a0 = i * Mth.TWO_PI / segments, a1 = (i + 1) * Mth.TWO_PI / segments;
            float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius, x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
            float s0 = u0 + i * repeat / segments, s1 = u0 + (i + 1) * repeat / segments;
            float nx = Mth.cos((a0 + a1) / 2), nz = Mth.sin((a0 + a1) / 2);
            vertex(pose, buffer, x0, 0, z0, s0, 1, color, -nx, -nz); // seen from inside
            vertex(pose, buffer, x0, height, z0, s0, 0, color, -nx, -nz);
            vertex(pose, buffer, x1, height, z1, s1, 0, color, -nx, -nz);
            vertex(pose, buffer, x1, 0, z1, s1, 1, color, -nx, -nz);
            vertex(pose, buffer, x1, 0, z1, s1, 1, color, nx, nz); // and from outside
            vertex(pose, buffer, x1, height, z1, s1, 0, color, nx, nz);
            vertex(pose, buffer, x0, height, z0, s0, 0, color, nx, nz);
            vertex(pose, buffer, x0, 0, z0, s0, 1, color, nx, nz);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, float u, float v, int color,
            float nx, float nz) {
        buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, nx, 0, nz);
    }
}
