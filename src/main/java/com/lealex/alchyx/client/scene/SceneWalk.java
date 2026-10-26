package com.lealex.alchyx.client.scene;

import com.lealex.alchyx.scene.WalkScenes;
import com.lealex.alchyx.util.StepPath;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scripted walks, the player's side (scene/WalkScenes sends the order): the keys are taken (a still input in place of
 * the player's), the camera goes to third person, a path to the spot is found (StepPath, through the machine's box
 * only when there is no way round) and straightened, then followed exactly: the player is moved along it each tick
 * past collisions (the server lets them), at their own movement speed (Speed, Slowness...), turning smoothly (the
 * view is eased every frame: the local player's view takes its yaw raw, so turning on ticks shook the camera), the
 * legs' stride counted here once a tick (the game's own would count a still tick too, and the arms shook). No path:
 * a dark blink (ScreenFade) puts them on the spot. There: the scene's client listener runs ({@link #onArrive}), the
 * server is told, the player is held on the spot facing the given way, and the scene's code steers what follows
 * ({@link #aim}, ScreenFade, BodyPoses...) until it calls {@link #release}. It is let go by itself if the player
 * dies, changes world or ends up far from it.
 */
public final class SceneWalk {
    private SceneWalk() {}

    /** The walk's pace at the player's base movement speed (0.1): a calm walk, a little under the game's own. */
    private static final double SPEED = 0.15;
    private static final int PATH_REACH = 20, PATH_NODES = 4000, THROUGH_COST = 40, BLINK = 8, LEASH = 24;

    private static WalkScenes.@Nullable WalkPayload order;
    private static @Nullable Level level;
    private static boolean arrived, noPath, blinking;
    private static int arrivedTicks, next;
    private static List<StepPath.Step> path = List.of();
    private static Vec3 spot = Vec3.ZERO, segmentFrom = Vec3.ZERO;
    private static float stride, stridePrev, strideSpeed;
    private static float aimYaw = Float.NaN, aimPitch = Float.NaN;
    private static @Nullable ClientInput saved;
    /** The walk's camera shot (CameraShots): third person behind the player until release. */
    public static final Identifier SHOT = Identifier.fromNamespaceAndPath("alchyx", "scene_walk");
    private static final Map<Identifier, Runnable> ARRIVALS = new HashMap<>();

    /** The scene's client code to run when the local player gets there (before the server hears of it). */
    public static void onArrive(Identifier scene, Runnable listener) {
        ARRIVALS.put(scene, listener);
    }

    public static boolean active() {
        return order != null;
    }

    public static boolean active(Identifier scene) {
        return order != null && order.scene().equals(scene);
    }

    public static boolean arrived() {
        return order != null && arrived;
    }

    /** Ticks since the player got there. */
    public static int arrivedTicks() {
        return arrivedTicks;
    }

    public static @Nullable BlockPos anchor() {
        return order == null ? null : order.anchor();
    }

    public static Vec3 spot() {
        return spot;
    }

    /** The scene's own numbers, as the server sent them. */
    public static List<Integer> extra() {
        return order == null ? List.of() : order.extra();
    }

    /** Where the player should look while held (eased there every frame); NaN leaves that axis alone. */
    public static void aim(float yaw, float pitch) {
        aimYaw = yaw;
        aimPitch = pitch;
    }

    /** Runs a walk order (WalkScenes' client hook). */
    public static void start(WalkScenes.WalkPayload payload) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (order != null) release();
        order = payload;
        level = player.level();
        spot = new Vec3(payload.x(), payload.y(), payload.z());
        arrived = blinking = false;
        arrivedTicks = next = 0;
        BlockPos from = BlockPos.containing(player.getX(), player.getY() + 0.01, player.getZ());
        BlockPos min = payload.min(), max = payload.max();
        List<StepPath.Step> found = StepPath.find(level, from, BlockPos.containing(spot), PATH_REACH, PATH_NODES,
                at -> at.getX() >= min.getX() && at.getX() <= max.getX() && at.getY() >= min.getY() && at.getY() <= max.getY()
                        && at.getZ() >= min.getZ() && at.getZ() <= max.getZ(), THROUGH_COST);
        noPath = found == null;
        path = found != null ? StepPath.straighten(level, player.position(), found) : List.of();
        segmentFrom = player.position();
        aimYaw = aimPitch = Float.NaN;
        CameraShots.take(SHOT, CameraType.THIRD_PERSON_BACK, null);
        saved = player.input;
        player.input = new StillInput();
    }

    /** Ends the scene on this side: the keys and the camera back. */
    public static void release() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && saved != null && player.input instanceof StillInput) player.input = saved;
        saved = null;
        CameraShots.release(SHOT);
        order = null;
        level = null;
        path = List.of();
        aimYaw = aimPitch = Float.NaN;
    }

    private static void arrive(LocalPlayer player) {
        if (order == null) return;
        arrived = true;
        arrivedTicks = 0;
        aimYaw = order.faceYaw();
        Runnable listener = ARRIVALS.get(order.scene());
        if (listener != null) listener.run();
        ClientPacketDistributor.sendToServer(new WalkScenes.ArrivePayload(order.scene(), order.anchor()));
    }

    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        if (order == null || !(event.getEntity() instanceof LocalPlayer player)) return;
        if (!player.isAlive() || player.level() != level || player.distanceToSqr(Vec3.atCenterOf(order.anchor())) > LEASH * LEASH) {
            release();
            return;
        }
        player.setDeltaMovement(Vec3.ZERO); // moved along the path after the tick (onPlayerTickPost)
        player.resetFallDistance();
        if (arrived) {
            arrivedTicks++;
            return;
        }
        if (noPath) { // no way there: a dark blink, the player put on the spot at its darkest
            if (!blinking) {
                blinking = true;
                ScreenFade.blink(BLINK);
            } else if (ScreenFade.blinkPeak()) {
                player.setPos(spot.x, spot.y, spot.z);
                arrive(player);
            }
            return;
        }
        Vec3 target = target();
        double dx = target.x - player.getX(), dz = target.z - player.getZ();
        if (dx * dx + dz * dz > 0.01) aimYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90F; // turning toward it
    }

    /** The cell the walk heads for now (its middle, feet height), the spot itself at the end. */
    private static Vec3 target() {
        if (next >= path.size() - 1) return spot;
        return StepPath.centre(path.get(next).at());
    }

    /** Per tick, at the player's own movement speed. */
    private static double speed(LocalPlayer player) {
        return Mth.clamp(SPEED * player.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1, 0.02, 0.8);
    }

    /** After the tick: the walk moves the player along the path (feet height following the cells, a small hop up a step); held after. */
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (order == null || !(event.getEntity() instanceof LocalPlayer player)) return;
        if (arrived) {
            if (player.distanceToSqr(spot.x, player.getY(), spot.z) > 0.04) player.setPos(spot.x, player.getY(), spot.z);
            return;
        }
        if (noPath) return;
        double left = speed(player), moved = 0;
        Vec3 at = player.position();
        while (left > 1.0E-4) {
            Vec3 target = target();
            double dx = target.x - at.x, dz = target.z - at.z, dist = Math.sqrt(dx * dx + dz * dz);
            double legX = target.x - segmentFrom.x, legZ = target.z - segmentFrom.z, leg = Math.sqrt(legX * legX + legZ * legZ);
            double step = Math.min(left, dist);
            double nx = dist > 1.0E-6 ? at.x + dx / dist * step : target.x, nz = dist > 1.0E-6 ? at.z + dz / dist * step : target.z;
            double done = leg > 1.0E-6 ? Mth.clamp(1 - Math.sqrt((target.x - nx) * (target.x - nx) + (target.z - nz) * (target.z - nz)) / leg, 0, 1) : 1;
            double hop = target.y > segmentFrom.y + 0.1 ? Math.sin(done * Math.PI) * 0.3 : 0;
            at = new Vec3(nx, Mth.lerp(done, segmentFrom.y, target.y) + hop, nz);
            left -= step;
            moved += step;
            if (dist - step > 1.0E-4) break;
            if (next >= path.size() - 1) { // there
                player.setPos(target.x, target.y, target.z);
                arrive(player);
                return;
            }
            segmentFrom = target; // on to the next leg
            next++;
        }
        player.setPos(at.x, at.y, at.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.setOnGround(true);
        stridePrev = stride;
        strideSpeed = (float) Math.min(moved * 4, 1);
        stride += strideSpeed;
    }

    /** The walk's legs for the local player's render state (BodyPoses calls it): its own stride, eased between ticks. */
    static void stride(AvatarRenderState state, float partial) {
        if (order == null || arrived || noPath) return;
        state.walkAnimationPos = Mth.lerp(partial, stridePrev, stride);
        state.walkAnimationSpeed = strideSpeed;
    }

    /** Every frame: the view eased toward where the walk or the scene turns it, the body with it. */
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (order == null || player == null || (Float.isNaN(aimYaw) && Float.isNaN(aimPitch))) return;
        float dt = event.getPartialTick().getGameTimeDeltaTicks();
        if (dt <= 0F) return; // paused
        float k = 1F - (float) Math.pow(0.8, dt); // about a fifth of the way per tick, spread over the frames
        if (!Float.isNaN(aimYaw)) {
            float yaw = player.getYRot() + Mth.wrapDegrees(aimYaw - player.getYRot()) * k;
            player.setYRot(yaw);
            player.yRotO = yaw;
            player.setYHeadRot(yaw);
            player.yHeadRotO = yaw;
            player.yBodyRot = yaw;
            player.yBodyRotO = yaw;
        }
        if (!Float.isNaN(aimPitch)) {
            float pitch = Mth.lerp(k, player.getXRot(), aimPitch);
            player.setXRot(pitch);
            player.xRotO = pitch;
        }
    }

    /** No keys: the player goes only where the walk sends them. */
    private static final class StillInput extends ClientInput {
        @Override
        public void tick() {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
        }
    }
}
