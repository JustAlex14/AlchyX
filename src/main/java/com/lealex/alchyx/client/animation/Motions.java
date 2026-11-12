package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.animation.Effect;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.animation.MotionSetup;
import com.lealex.alchyx.animation.ResolvedAnimation;
import com.lealex.alchyx.animation.StudioMark;
import com.lealex.alchyx.animation.Trigger;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.FreeBuild;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The animation a machine plays (client): its file's effects (unless the studio turned them off), plus what its
 * players made with the Machine Studio: a block move or change for each motion a part was given, and the particles,
 * figures, items, sounds and mod effects at its anchors. On a free-build machine they are measured in the box its
 * player recorded (written as it stands in the world, so no turning); on a machine with a fixed shape they are turned
 * back into the pattern's own frame, so the file's effects and the studio's play together. Built again only when the
 * file, the shape or the studio's work change, so the engine keeps its runtime meanwhile.
 */
public final class Motions {
    private Motions() {}

    private record Cached(@Nullable ResolvedAnimation file, @Nullable MultiblockPattern shape, Rotation rotation, int version,
                          @Nullable ResolvedAnimation resolved) {}

    private static final Map<MultiblockCoreBlockEntity, Cached> CACHE = new WeakHashMap<>();

    /** What the studio made for a machine: anchors (offsets in the pattern's own frame) and the effects using them. */
    public record Layer(Map<String, BlockPos> anchors, List<Effect> effects) {}

    /** What this core plays: {@code file}, with what its players made in the studio. */
    static @Nullable ResolvedAnimation resolve(MultiblockCoreBlockEntity core, @Nullable ResolvedAnimation file) {
        MultiblockPattern recorded = core.recordedShape();
        boolean studio = !core.motions().isEmpty() || !core.marks().isEmpty() || core.hidesFileAnimation();
        if (recorded == null && !studio) return file;
        MultiblockPattern shape = recorded != null ? recorded : core.pattern();
        if (shape == null) return file;
        Rotation rotation = recorded != null ? Rotation.NONE : core.rotation();
        Cached cached = CACHE.get(core);
        if (cached != null && cached.file() == file && cached.shape() == shape && cached.rotation() == rotation
                && cached.version() == core.motionsVersion()) {
            return cached.resolved();
        }
        Layer layer = layer(core, rotation);
        List<Effect> effects = new ArrayList<>(file == null || (recorded == null && core.hidesFileAnimation()) ? List.of() : file.effects());
        effects.addAll(layer.effects());
        Map<String, BlockPos> anchors = new HashMap<>(file == null ? Map.of() : file.anchors());
        anchors.putAll(layer.anchors());
        ResolvedAnimation resolved;
        if (effects.isEmpty()) {
            resolved = null;
        } else if (recorded != null) {
            resolved = new ResolvedAnimation(core.patternId(), Optional.empty(), anchors, recorded.minOffset(), recorded.maxOffset(), List.copyOf(effects));
        } else {
            resolved = new ResolvedAnimation(core.patternId(), file != null ? file.front() : shape.front(), anchors,
                    file != null ? file.boxMin() : shape.minOffset(), file != null ? file.boxMax() : shape.maxOffset(), List.copyOf(effects));
        }
        CACHE.put(core, new Cached(file, shape, rotation, core.motionsVersion(), resolved));
        return resolved;
    }

    /**
     * The studio's work on this core as effects: {@code rotation} is how the machine is turned in the world (its
     * anchors are turned back by it, and so are figures' headings).
     */
    public static Layer layer(MultiblockCoreBlockEntity core, Rotation rotation) {
        FreeBuild rules = core.studioRules();
        return layer(core.getLevel(), core.getBlockPos(), core.motions(), core.setups(), core.marks(),
                rules == null ? Trigger.WORKING : rules.motionWhen(), rotation);
    }

    /** The same from these motions, setups and anchors (offsets from the core at {@code corePos}, as in the world). */
    public static Layer layer(net.minecraft.world.level.@Nullable Level level, BlockPos corePos, Map<BlockPos, Motion> motions,
                              Map<BlockPos, MotionSetup> setups, java.util.Collection<StudioMark> marks, Trigger when, Rotation rotation) {
        return layer(level, corePos, motions, setups, marks, Map.of(), when, rotation);
    }

    /**
     * The same, with the blocks the Shape tab reshaped as effects too (for a file: on the machine itself the engine
     * draws them reshaped all the time): a moving block moves reshaped, a still one stands reshaped all the time.
     */
    public static Layer layer(net.minecraft.world.level.@Nullable Level level, BlockPos corePos, Map<BlockPos, Motion> motions,
                              Map<BlockPos, MotionSetup> setups, java.util.Collection<StudioMark> marks,
                              Map<BlockPos, com.lealex.alchyx.animation.BlockPose> poses, Trigger when, Rotation rotation) {
        Rotation back = MultiblockPattern.inverse(rotation);
        int turn = quarter(back) * 90;
        List<Effect> effects = new ArrayList<>();
        Map<String, BlockPos> anchors = new java.util.LinkedHashMap<>();
        java.util.Set<BlockPos> posed = new java.util.HashSet<>();
        int n = 0;
        for (Map.Entry<BlockPos, Motion> entry : motions.entrySet()) {
            String anchor = "alchyx_motion_" + n++;
            BlockState look = level == null ? null : ChamberShellBlock.lookAt(level, corePos.offset(entry.getKey()));
            List<Effect> made = look == null ? List.of() : Motion.effects(entry.getValue(), anchor, when, look,
                    setups.getOrDefault(entry.getKey(), MotionSetup.ALWAYS));
            if (made.isEmpty()) continue;
            com.lealex.alchyx.animation.BlockPose pose = poses.get(entry.getKey());
            for (Effect effect : made) {
                if (pose != null && effect instanceof Effect.BlockMove move && move.hide() && move.draw().isEmpty()) {
                    effect = new Effect.BlockMove(move.timing(), move.targets(), move.draw(), move.direction(), move.align(), move.swap(),
                            pose.apply(move.keyframes(), com.lealex.alchyx.animation.BlockPose.turn(pose.offset(), back)), true);
                    posed.add(entry.getKey());
                }
                effects.add(effect);
            }
            anchors.put(anchor, entry.getKey().rotate(back));
        }
        n = 0;
        for (Map.Entry<BlockPos, com.lealex.alchyx.animation.BlockPose> entry : poses.entrySet()) {
            if (posed.contains(entry.getKey())) continue;
            String anchor = "alchyx_pose_" + n++;
            com.lealex.alchyx.animation.BlockPose pose = entry.getValue();
            effects.add(new Effect.BlockMove(new com.lealex.alchyx.animation.Timing(Trigger.ALWAYS, 20, 1.0F, 0, Map.of(),
                    com.lealex.alchyx.animation.Timing.Where.EVERYWHERE), new com.lealex.alchyx.animation.Target(Optional.of(anchor),
                    Optional.empty(), Map.of(), net.minecraft.world.phys.Vec3.ZERO), Optional.empty(), "up", false, Map.of(),
                    pose.still(com.lealex.alchyx.animation.BlockPose.turn(pose.offset(), back)), true));
            anchors.put(anchor, entry.getKey().rotate(back));
        }
        n = 0;
        for (StudioMark mark : marks) {
            String anchor = "alchyx_mark_" + n++;
            String toAnchor = mark.to().isPresent() ? anchor + "_to" : null;
            StudioMark turned = turn == 0 || (mark.kind() != StudioMark.Kind.FIGURE && mark.kind() != StudioMark.Kind.EFFECT)
                    ? mark : mark.withYaw(Math.floorMod(mark.yaw() + turn, 360)); // (a heading in the pattern's frame)
            List<Effect> made = turned.effects(anchor, toAnchor, when);
            if (made.isEmpty()) continue;
            anchors.put(anchor, mark.offset().rotate(back));
            if (toAnchor != null) anchors.put(toAnchor, mark.to().get().rotate(back));
            effects.addAll(made);
        }
        return new Layer(anchors, effects);
    }

    /** Quarter turns clockwise, 0 to 3. */
    private static int quarter(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
    }
}
