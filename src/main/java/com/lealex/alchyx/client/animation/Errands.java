package com.lealex.alchyx.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * For coded figures that run errands around their machine (a farmhand going from square to square): where the figure
 * is and which way it faces at a moment of an errand, how to put it there, and something held in its hands.
 *
 * The machine decides the errands and syncs one at a time as plain numbers (where from, where to, when it started,
 * how long the way and the work take). Everything else is worked out here from the clock, every frame, so nothing is
 * sent while the figure moves. Only the motion is here: the figure's model, look and gestures are its mod's.
 */
public final class Errands {
    private Errands() {}

    /**
     * One errand, in blocks counted from where the animation file puts the figure (usually the core): it leaves
     * (fromX, fromZ) at game time {@code start}, takes {@code travel} ticks to reach (toX, toZ), works there for
     * {@code work} ticks, then waits there. Once there it turns to face (faceX, faceZ), what it works on, when
     * that is another spot than the one it stands on (its work is a step in front of it).
     */
    public record Errand(float fromX, float fromZ, float toX, float toZ, float faceX, float faceZ, long start, int travel, int work) {}

    /**
     * The figure at one moment.
     *
     * @param x       where it is, in blocks from the errand's origin
     * @param heading the way it faces (degrees, entity convention: 0 looks toward +z), turning a little each tick
     * @param glide   0 at rest, 1 in the middle of the way: rises and falls smoothly, for a pose that leans into it
     * @param work    0 to 1 through the work, -1 when it is not working (on the way, or done)
     */
    public record Moment(float x, float z, float heading, float glide, float work) {}

    // What is remembered per owner between frames
    private static final class Memory {
        float heading;
        double time = Double.NaN;
        @Nullable Item held;
        @Nullable ItemStackRenderState heldState;
        long heldMade;
    }

    private static final Map<Object, Memory> MEMORY = new WeakHashMap<>();

    /**
     * Where the errand has the figure at {@code now} (game time with the partial tick).
     *
     * @param owner       what the figure belongs to (its heading is remembered per owner, weakly)
     * @param turnPerTick how many degrees it may turn each tick toward where it is going
     */
    public static Moment at(Object owner, Errand errand, double now, float turnPerTick) {
        float since = (float) (now - errand.start());
        float way = errand.travel() <= 0 ? 1.0F : Mth.clamp(since / errand.travel(), 0.0F, 1.0F);
        float eased = way * way * (3.0F - 2.0F * way); // sets off and arrives gently
        float x = Mth.lerp(eased, errand.fromX(), errand.toX()), z = Mth.lerp(eased, errand.fromZ(), errand.toZ());
        float work = since >= errand.travel() && since < errand.travel() + errand.work() && errand.work() > 0
                ? (since - errand.travel()) / errand.work() : -1.0F;

        Memory memory = MEMORY.computeIfAbsent(owner, key -> new Memory());
        float dx = errand.toX() - errand.fromX(), dz = errand.toZ() - errand.fromZ();
        float faceX = errand.faceX() - errand.toX(), faceZ = errand.faceZ() - errand.toZ();
        if ((faceX != 0.0F || faceZ != 0.0F) && (way >= 0.85F || (dx == 0.0F && dz == 0.0F))) { // nearly there: toward its work
            dx = faceX;
            dz = faceZ;
        }
        if (dx != 0.0F || dz != 0.0F) { // an errand on the spot, with nothing else to face, keeps the heading it had
            float wanted = (float) Math.toDegrees(Math.atan2(-dx, dz));
            if (Double.isNaN(memory.time)) {
                memory.heading = wanted;
            } else {
                float most = (float) Math.clamp(now - memory.time, 0.0, 5.0) * turnPerTick;
                memory.heading += Mth.clamp(Mth.wrapDegrees(wanted - memory.heading), -most, most);
            }
        }
        memory.time = now;
        return new Moment(x, z, memory.heading, Mth.sin((float) Math.PI * way), work);
    }

    /**
     * Moves a coded figure's pose from where the animation file put it to this moment of its errand, facing its
     * heading. Afterwards the pose is as {@link CodedFigure#submit} describes it (a standard entity model still
     * needs its flip), and in it the figure's front is toward -z.
     */
    public static void place(PoseStack poseStack, CodedFigure.Context context, Moment moment) {
        poseStack.mulPose(Axis.YP.rotationDegrees(context.yaw() - 180.0F)); // back to the plain block frame
        poseStack.translate(moment.x(), 0.0F, moment.z());
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - moment.heading()));
    }

    /**
     * Draws an item in the current pose (after {@link #place}: x to its side, y up from its feet, -z in front of
     * it), full bright. Its render state is kept per owner and made again when the item changes, and every few
     * seconds (after a resource reload an old one would still point at the old models).
     *
     * @param spin degrees turned around the vertical
     */
    public static void held(PoseStack poseStack, SubmitNodeCollector collector, Object owner, Level level, Item item,
                            float x, float y, float z, float spin, float scale) {
        if (scale <= 0.0F) return;
        Memory memory = MEMORY.computeIfAbsent(owner, key -> new Memory());
        long time = level.getGameTime();
        if (memory.heldState == null || memory.held != item || time - memory.heldMade > 100 || time < memory.heldMade) {
            memory.held = item;
            memory.heldMade = time;
            memory.heldState = new ItemStackRenderState();
            Minecraft.getInstance().getItemModelResolver().updateForTopItem(memory.heldState, new ItemStack(item),
                    ItemDisplayContext.GROUND, level, null, System.identityHashCode(owner));
        }
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.scale(scale, scale, scale);
        memory.heldState.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}
