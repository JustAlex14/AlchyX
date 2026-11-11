package com.lealex.alchyx.client.studio;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * How the Machine Studio's view is turned, in one place, so the picture ({@link StudioRenderer}) and the mouse
 * ({@link #pick}) always agree. The picture puts its middle at the origin with y down and depth toward the
 * viewer flipped; this turns the build upright, then by {@code pitch} (looking down when negative) and {@code yaw}.
 */
final class StudioView {
    private StudioView() {}

    private static Matrix4f matrix(float yaw, float pitch) {
        return new Matrix4f().rotateZ((float) Math.PI).rotateX(pitch).rotateY(yaw);
    }

    static void turn(PoseStack poseStack, float yaw, float pitch) {
        poseStack.mulPose(matrix(yaw, pitch));
    }

    /**
     * The cell under the mouse, of those given: the one nearest the viewer whose block the ray meets.
     *
     * @param dx     the mouse's distance from the view's middle, gui pixels, right
     * @param dy     the same, down
     * @param scale  gui pixels per block
     * @param center the point of the build at the view's middle
     * @param cells  the cells to try (in the same units as center: one per block, a cell spans pos to pos + 1)
     */
    static @Nullable BlockPos pick(double dx, double dy, float scale, float yaw, float pitch, Vec3 center, Collection<BlockPos> cells) {
        Matrix4f back = matrix(yaw, pitch).invert();
        // The view's depth: the picture flips it, so the nearest points have the smallest turned z
        Vector3f near = back.transformPosition(new Vector3f((float) (dx / scale), (float) (dy / scale), -1000));
        Vector3f far = back.transformPosition(new Vector3f((float) (dx / scale), (float) (dy / scale), 1000));
        double ox = near.x + center.x, oy = near.y + center.y, oz = near.z + center.z;
        double rx = far.x - near.x, ry = far.y - near.y, rz = far.z - near.z;
        BlockPos best = null;
        double bestT = Double.MAX_VALUE;
        for (BlockPos cell : cells) {
            double t = hit(ox, oy, oz, rx, ry, rz, cell);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = cell;
            }
        }
        return best;
    }

    /** Where along the ray it enters the cell (0 to 1), or -1 if it misses. */
    private static double hit(double ox, double oy, double oz, double rx, double ry, double rz, BlockPos cell) {
        double enter = 0, leave = 1;
        double[] o = {ox, oy, oz}, r = {rx, ry, rz};
        int[] lo = {cell.getX(), cell.getY(), cell.getZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(r[axis]) < 1e-9) {
                if (o[axis] < lo[axis] || o[axis] > lo[axis] + 1) return -1;
                continue;
            }
            double a = (lo[axis] - o[axis]) / r[axis], b = (lo[axis] + 1 - o[axis]) / r[axis];
            enter = Math.max(enter, Math.min(a, b));
            leave = Math.min(leave, Math.max(a, b));
            if (enter > leave) return -1;
        }
        return enter;
    }
}
