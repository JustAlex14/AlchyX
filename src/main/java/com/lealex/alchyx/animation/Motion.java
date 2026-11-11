package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A ready-made motion a player can give one block of a free-build machine with the Structure Recorder (no animation
 * file to write). Each one is a looping block move that hides the block and draws a moving copy of it.
 */
public enum Motion implements StringRepresentable {
    /** Turns around itself, a full turn every 2 seconds. */
    SPIN("spin", "up", List.of(frame(0, 0, Vec3.ZERO, 0), frame(40, 0, Vec3.ZERO, 360))),
    /** Floats up and down. */
    BOB("bob", "up", List.of(frame(0, 0, Vec3.ZERO, 0), frame(20, 0, new Vec3(0, 0.15, 0), 0), frame(40, 0, Vec3.ZERO, 0))),
    /** Strikes out along the way it faces (toward the core if it faces nowhere), like a piston, then back. */
    STRIKE("strike", "facing", List.of(frame(0, 0, Vec3.ZERO, 0), frame(4, 0.4F, Vec3.ZERO, 0), frame(8, 0.4F, Vec3.ZERO, 0),
            frame(20, 0, Vec3.ZERO, 0), frame(30, 0, Vec3.ZERO, 0))),
    /** Swings a quarter turn like a door, waits, and swings back. */
    SWING("swing", "up", List.of(frame(0, 0, Vec3.ZERO, 0), frame(15, 0, Vec3.ZERO, 90), frame(35, 0, Vec3.ZERO, 90),
            frame(50, 0, Vec3.ZERO, 0), frame(60, 0, Vec3.ZERO, 0))),
    /** Trembles in place. */
    SHAKE("shake", "up", List.of(frame(0, 0, Vec3.ZERO, 0), frame(1, 0, new Vec3(0.04, 0, -0.03), 0),
            frame(2, 0, new Vec3(-0.03, 0.02, 0.04), 0), frame(3, 0, new Vec3(0.03, 0, 0.02), 0), frame(4, 0, Vec3.ZERO, 0)));

    public static final Codec<Motion> CODEC = StringRepresentable.fromEnum(Motion::values);

    private final String id;
    private final String direction;
    private final List<Keyframe> keyframes;

    Motion(String id, String direction, List<Keyframe> keyframes) {
        this.id = id;
        this.direction = direction;
        this.keyframes = keyframes;
    }

    private static Keyframe frame(float t, float move, Vec3 offset, float yDegrees) {
        return new Keyframe(t, move, offset, new Vec3(0, yDegrees, 0), 1.0F, Map.of());
    }

    /** The motion's keyframes, one loop. */
    public List<Keyframe> keyframes() {
        return keyframes;
    }

    /** The way it moves along: "up", or "facing" (the block's facing). */
    public String direction() {
        return direction;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** The motion as an animation effect on the block named by this anchor, looping while {@code when} holds. */
    public Effect.BlockMove effect(String anchor, Trigger when) {
        int loop = Math.max(1, (int) Keyframe.duration(keyframes));
        Timing timing = new Timing(when, loop, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE);
        Target target = new Target(Optional.of(anchor), Optional.empty(), Map.of(), Vec3.ZERO);
        return new Effect.BlockMove(timing, target, Optional.empty(), direction, false, Map.of(), keyframes, true);
    }
}
