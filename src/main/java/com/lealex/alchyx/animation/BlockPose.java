package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How a player reshaped one block of a machine in the Machine Studio's Shape tab, for its look only (the block stays
 * where it is for everything else): moved by {@code offset} (blocks), turned by {@code rotation} (degrees around x,
 * y and z, around its middle), sized by {@code scale} (around its middle; small blocks make a mini machine), or
 * {@code hidden}. It plays all the time, with the block's motion if it has one.
 */
public record BlockPose(Vec3 offset, Vec3 rotation, float scale, boolean hidden) {
    public static final BlockPose NONE = new BlockPose(Vec3.ZERO, Vec3.ZERO, 1.0F, false);
    /** How far a block may be moved (blocks, each way), and its smallest and largest size. */
    public static final double MAX_OFFSET = 16;
    public static final float MIN_SCALE = 0.05F, MAX_SCALE = 4.0F;

    public static final Codec<BlockPose> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(BlockPose::offset),
            Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(BlockPose::rotation),
            Codec.FLOAT.optionalFieldOf("scale", 1.0F).forGetter(BlockPose::scale),
            Codec.BOOL.optionalFieldOf("hidden", false).forGetter(BlockPose::hidden)
    ).apply(instance, BlockPose::new));

    public static final StreamCodec<ByteBuf, BlockPose> STREAM_CODEC = StreamCodec.composite(
            Vec3.STREAM_CODEC, BlockPose::offset, Vec3.STREAM_CODEC, BlockPose::rotation, ByteBufCodecs.FLOAT, BlockPose::scale, ByteBufCodecs.BOOL, BlockPose::hidden,
            BlockPose::new);

    public boolean isNone() {
        return equals(NONE) || tidy().equals(NONE);
    }

    /** The same kept within bounds: offsets within {@link #MAX_OFFSET}, angles in -180 to 180, a size that shows. */
    public BlockPose tidy() {
        Vec3 o = new Vec3(clamp(offset.x), clamp(offset.y), clamp(offset.z));
        Vec3 r = new Vec3(Mth.wrapDegrees(rotation.x), Mth.wrapDegrees(rotation.y), Mth.wrapDegrees(rotation.z));
        float s = Float.isFinite(scale) ? Mth.clamp(scale, MIN_SCALE, MAX_SCALE) : 1.0F;
        return new BlockPose(round(o), round(r), Math.round(s * 1000) / 1000.0F, hidden);
    }

    private static double clamp(double v) {
        return Double.isFinite(v) ? Mth.clamp(v, -MAX_OFFSET, MAX_OFFSET) : 0;
    }

    private static Vec3 round(Vec3 v) {
        return new Vec3(Math.round(v.x * 1000) / 1000.0, Math.round(v.y * 1000) / 1000.0, Math.round(v.z * 1000) / 1000.0);
    }

    public BlockPose withOffset(Vec3 offset) {
        return new BlockPose(offset, rotation, scale, hidden);
    }

    public BlockPose withRotation(Vec3 rotation) {
        return new BlockPose(offset, rotation, scale, hidden);
    }

    public BlockPose withScale(float scale) {
        return new BlockPose(offset, rotation, scale, hidden);
    }

    public BlockPose withHidden(boolean hidden) {
        return new BlockPose(offset, rotation, scale, hidden);
    }

    /** The pose turned by {@code rotation} (a machine turned in the world): its offset and its tilts. */
    public BlockPose turned(Rotation rotation) {
        return new BlockPose(turn(offset, rotation), turn(this.rotation, rotation), scale, hidden);
    }

    /** A vector turned like BlockPos.rotate. */
    public static Vec3 turn(Vec3 v, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new Vec3(-v.z, v.y, v.x);
            case CLOCKWISE_180 -> new Vec3(-v.x, v.y, -v.z);
            case COUNTERCLOCKWISE_90 -> new Vec3(v.z, v.y, -v.x);
            default -> v;
        };
    }

    /** {@code frames} with this pose on top: moved, turned and sized (all to nothing when hidden). */
    public List<Keyframe> apply(List<Keyframe> frames, Vec3 offsetInFrame) {
        List<Keyframe> out = new ArrayList<>();
        for (Keyframe frame : frames) {
            out.add(new Keyframe(frame.t(), frame.move(), frame.offset().add(offsetInFrame), frame.rotation().add(rotation),
                    hidden ? 0.0F : frame.scale() * scale, frame.state()));
        }
        return out;
    }

    /** A still pose, as keyframes that hold for a second (played again and again). */
    public List<Keyframe> still(Vec3 offsetInFrame) {
        return apply(List.of(new Keyframe(0, 0, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of()),
                new Keyframe(20, 0, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of())), offsetInFrame);
    }
}
