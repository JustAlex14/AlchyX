package com.lealex.alchyx.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;

/**
 * Boxes drawn by hand into a buffer (submitCustomGeometry), for props and bones that are not a baked model: an upright
 * box with one band of a sheet on every face, and a beam (a box along a line in the pose's XY plane). Each face is
 * drawn once with its outward normal: the entity pipelines draw both sides already, and two copies of a face fight
 * for the same pixels (flicker).
 */
public final class Cubes {
    private Cubes() {}

    /** An upright box, every face with the same rectangle of the texture (u0..u1, v0..v1 in 0..1). */
    public static void box(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
                           float u0, float v0, float u1, float v1, int argb, int light) {
        quad(pose, buffer, argb, light, u0, u1, v0, v1, 0, 0, -1, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(pose, buffer, argb, light, u0, u1, v0, v1, 0, 0, 1, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1);
        quad(pose, buffer, argb, light, u0, u1, v0, v1, -1, 0, 0, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1);
        quad(pose, buffer, argb, light, u0, u1, v0, v1, 1, 0, 0, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        quad(pose, buffer, argb, light, u0, u1, v0, v1, 0, -1, 0, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
        quad(pose, buffer, argb, light, u0, u1, v0, v1, 0, 1, 0, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    /**
     * A box along the line a to b in the pose's XY plane: `half` wide in the plane, `depth` out of it (each side), the
     * whole texture on every face. For bones of a flat thing (a wing), a ray, a rod.
     */
    public static void beam(PoseStack.Pose pose, VertexConsumer buffer, float ax, float ay, float bx, float by, float half, float depth,
                            int argb, int light) {
        float dx = bx - ax, dy = by - ay, len = Mth.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-5F) return;
        float ex = dx / len, ey = dy / len, px = -ey, py = ex, nx = px * half, ny = py * half;
        float[][] c = new float[8][];
        int i = 0;
        for (int e = 0; e < 2; e++) {
            float x = e == 0 ? ax : bx, y = e == 0 ? ay : by;
            for (int sn = -1; sn <= 1; sn += 2) {
                for (int sz = -1; sz <= 1; sz += 2) c[i++] = new float[] {x + sn * nx, y + sn * ny, sz * depth};
            }
        }
        // corners: 0 a,-n,-z  1 a,-n,+z  2 a,+n,-z  3 a,+n,+z  4..7 the same at b
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        float[][] normals = {{-ex, -ey, 0}, {ex, ey, 0}, {-px, -py, 0}, {px, py, 0}, {0, 0, -1}, {0, 0, 1}};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int f = 0; f < 6; f++) {
            for (int k = 0; k < 4; k++) {
                float[] p = c[faces[f][k]];
                buffer.addVertex(pose, p[0], p[1], p[2]).setColor(argb).setUv(uv[k][0], uv[k][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normals[f][0], normals[f][1], normals[f][2]);
            }
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int argb, int light, float u0, float u1, float v0, float v1,
                             float nx, float ny, float nz, float... p) {
        float[][] uv = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(pose, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]).setColor(argb).setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        }
    }
}
