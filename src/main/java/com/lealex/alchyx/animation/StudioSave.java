package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

/**
 * What a player made in the Machine Studio, as an exported animation file keeps it beside its effects (its
 * "alchyx_studio" object), so Import can bring it back into the studio to change it: the motions (and their timing),
 * the anchors, and whether the machine's own animation is off. Offsets are from the core in the pattern's own frame
 * (like the file's anchors), so it fits a machine turned any way.
 */
public record StudioSave(List<Moving> motions, List<StudioMark> marks, boolean hideFile, List<Posed> poses) {

    public StudioSave(List<Moving> motions, List<StudioMark> marks, boolean hideFile) {
        this(motions, marks, hideFile, List.of());
    }

    /** One block the Shape tab reshaped: its offset, and how (moved, turned, sized, hidden). */
    public record Posed(BlockPos offset, BlockPose pose) {
        public static final Codec<Posed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("offset").forGetter(Posed::offset),
                BlockPose.CODEC.fieldOf("pose").forGetter(Posed::pose)
        ).apply(instance, Posed::new));
    }

    /** One block's motion: its offset, the motion, and when it plays (a change's steps). */
    public record Moving(BlockPos offset, Motion motion, MotionSetup setup) {
        public static final Codec<Moving> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("offset").forGetter(Moving::offset),
                Motion.CODEC.fieldOf("motion").forGetter(Moving::motion),
                MotionSetup.CODEC.optionalFieldOf("setup", MotionSetup.ALWAYS).forGetter(Moving::setup)
        ).apply(instance, Moving::new));
    }

    public static final Codec<StudioSave> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Moving.CODEC.listOf().optionalFieldOf("motions", List.of()).forGetter(StudioSave::motions),
            StudioMark.CODEC.listOf().optionalFieldOf("marks", List.of()).forGetter(StudioSave::marks),
            Codec.BOOL.optionalFieldOf("hide_file", false).forGetter(StudioSave::hideFile),
            Posed.CODEC.listOf().optionalFieldOf("poses", List.of()).forGetter(StudioSave::poses)
    ).apply(instance, StudioSave::new));

    /** The same turned by {@code rotation} (offsets, travel spots, and the headings of figures and mod effects). */
    public StudioSave turned(Rotation rotation) {
        if (rotation == Rotation.NONE) return this;
        int quarters = switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
        List<Moving> movingTurned = new ArrayList<>();
        for (Moving moving : motions) movingTurned.add(new Moving(moving.offset().rotate(rotation), moving.motion(), moving.setup()));
        List<StudioMark> marksTurned = new ArrayList<>();
        for (StudioMark mark : marks) {
            boolean heading = mark.kind() == StudioMark.Kind.FIGURE || mark.kind() == StudioMark.Kind.EFFECT;
            marksTurned.add(new StudioMark(mark.offset().rotate(rotation), mark.kind(), mark.what(),
                    heading ? Math.floorMod(mark.yaw() + quarters * 90, 360) : mark.yaw(), mark.to().map(to -> to.rotate(rotation)),
                    mark.times(), mark.items(), mark.shows()));
        }
        List<Posed> posesTurned = new ArrayList<>();
        for (Posed posed : poses) posesTurned.add(new Posed(posed.offset().rotate(rotation), posed.pose().turned(rotation)));
        return new StudioSave(movingTurned, marksTurned, hideFile, posesTurned);
    }
}
