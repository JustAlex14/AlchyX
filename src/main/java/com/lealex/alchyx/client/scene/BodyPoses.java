package com.lealex.alchyx.client.scene;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.client.HumanoidPoses;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whole-body poses for players in scenes (lying on a table, sitting, kneeling...), driven by any mod. A mod registers
 * a {@link Source}; every frame each player's render state asks the sources (all of them, so each can keep its own
 * render data right) and the first {@link BodyPose} given is drawn:
 * <ul>
 * <li>the body: the model is moved so its hips are at a world point, turned to a yaw, tilted back about the hips
 *     (0 standing, pi/2 lying on its back) and rolled about the way it faces; wrapped round the whole model draw
 *     (RenderLivingEvent Pre / Post), so layers and worn things follow;</li>
 * <li>the limbs: set after the game's own posing (AlchyX HumanoidPoses), for the player model and its armour.</li>
 * </ul>
 * The player itself stays where it is (often beside the thing it lies on); only the picture moves. The walk to a scene
 * (client/scene/SceneWalk) draws its own stride here too.
 */
public final class BodyPoses {
    private BodyPoses() {}

    /** Indices into {@link BodyPose#limbs()}: right arm x / z (outward +), left arm x / z, legs x, the legs' spread, head x / y. */
    public static final int R_ARM_X = 0, R_ARM_Z = 1, L_ARM_X = 2, L_ARM_Z = 3, R_LEG_X = 4, L_LEG_X = 5, SPREAD = 6, HEAD_X = 7, HEAD_Y = 8, LIMBS = 9;
    /** The player model's hip height above its feet, in blocks (12 of 32 pixels at the player's 0.9375 scale). */
    public static final float HIP = 0.703F;

    /**
     * A body this frame: its hips at (x, y, z) in the world, facing `yaw` (degrees, as an entity's), tilted back by
     * `tilt` (radians) about the hips, rolled by `roll` (radians) about the way it faces; limbs (LIMBS values, or
     * null to leave the game's own).
     */
    public record BodyPose(double x, double y, double z, float yaw, float tilt, float roll, float @Nullable [] limbs) {}

    public interface Source {
        /** This player's pose this frame, or null. Called for every player drawn, every frame. */
        @Nullable BodyPose pose(Player player, AvatarRenderState state, float partialTick);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    private static final ContextKey<float[]> TRANSFORM = new ContextKey<>(Identifier.fromNamespaceAndPath(AlchyX.MODID, "body_pose"));
    private static final ContextKey<float[]> LIMB_DATA = new ContextKey<>(Identifier.fromNamespaceAndPath(AlchyX.MODID, "body_limbs"));
    private static final float[] NONE = new float[0];

    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** Whether this state's body is posed by a scene this frame (layers that should hide then: wings, capes...). */
    public static boolean posed(AvatarRenderState state) {
        float[] move = state.getRenderData(TRANSFORM);
        return move != null && move.length == 7;
    }

    /** From the avatar state modifier (registered by AlchyX). */
    public static void apply(Player player, AvatarRenderState state) {
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (player == Minecraft.getInstance().player) SceneWalk.stride(state, partial);
        BodyPose pose = null;
        for (Source source : SOURCES) {
            BodyPose given = source.pose(player, state, partial);
            if (pose == null) pose = given;
        }
        if (pose == null) {
            state.setRenderData(TRANSFORM, NONE);
            state.setRenderData(LIMB_DATA, NONE);
            return;
        }
        float fx = -Mth.sin(pose.yaw() * Mth.DEG_TO_RAD), fz = Mth.cos(pose.yaw() * Mth.DEG_TO_RAD); // the way it faces
        state.setRenderData(TRANSFORM, new float[] {(float) (pose.x() - state.x), (float) (pose.y() - state.y), (float) (pose.z() - state.z),
                pose.tilt(), fx, fz, pose.roll()});
        state.setRenderData(LIMB_DATA, pose.limbs() != null && pose.limbs().length == LIMBS ? pose.limbs() : NONE);
        state.bodyRot = pose.yaw();
        state.yRot = 0F;
        state.xRot = 0F;
        state.walkAnimationSpeed = 0F;
        state.pose = Pose.STANDING;
    }

    public static void onRenderPre(RenderLivingEvent.Pre<?, ?, ?> event) {
        if (event.isCanceled() || !(event.getRenderState() instanceof AvatarRenderState state) || !posed(state)) return;
        float[] m = state.getRenderData(TRANSFORM);
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(m[0], m[1], m[2]); // to the hips
        // tipping back about the axis (facing x up) lays the head the opposite way to the facing; roll about the facing
        if (m[6] != 0F) poseStack.mulPose(new Quaternionf().rotateAxis(m[6], -m[4], 0F, -m[5]));
        if (m[3] != 0F) poseStack.mulPose(new Quaternionf().rotateAxis(m[3], -m[5], 0F, m[4]));
        poseStack.translate(0F, -HIP, 0F); // the model's origin is its feet
    }

    public static void onRenderPost(RenderLivingEvent.Post<?, ?, ?> event) {
        if (event.getRenderState() instanceof AvatarRenderState state && posed(state)) event.getPoseStack().popPose();
    }

    /** The HumanoidPoses hook (registered by AlchyX): the limbs of a posed body. */
    static void limbs(HumanoidModel<?> model, HumanoidRenderState state) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        float[] l = avatar.getRenderData(LIMB_DATA);
        if (l == null || l.length != LIMBS) return;
        model.rightArm.xRot = l[R_ARM_X];
        model.rightArm.zRot = l[R_ARM_Z];
        model.leftArm.xRot = l[L_ARM_X];
        model.leftArm.zRot = -l[L_ARM_Z];
        model.rightArm.yRot = 0F;
        model.leftArm.yRot = 0F;
        model.rightLeg.xRot = l[R_LEG_X];
        model.leftLeg.xRot = l[L_LEG_X];
        model.rightLeg.yRot = l[SPREAD];
        model.leftLeg.yRot = -l[SPREAD];
        model.rightLeg.zRot = 0F;
        model.leftLeg.zRot = 0F;
        model.head.xRot = l[HEAD_X];
        model.head.yRot = l[HEAD_Y];
    }

    /** Registers the hook with HumanoidPoses (AlchyXClient). */
    public static void init() {
        HumanoidPoses.register(BodyPoses::limbs);
    }
}
