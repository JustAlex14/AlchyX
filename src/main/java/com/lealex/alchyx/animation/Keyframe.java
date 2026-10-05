package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One step of a block move: at tick "t" (from the start of the move) the drawn block is "move" blocks along the
 * move's direction, shifted by "offset" (pattern coordinates), turned by "rotation" (degrees around x, y, z, about
 * the block's center) and scaled by "scale". Values are blended linearly between keyframes.
 * "state" (optional) sets state values on the drawn block from that keyframe on, without blending: e.g. a piston
 * head switches to its short arm ({"short": true}) while it is less than half out, like vanilla.
 */
public record Keyframe(float t, float move, Vec3 offset, Vec3 rotation, float scale, Map<String, String> state) {
    public static final Codec<Keyframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("t").forGetter(Keyframe::t),
            Codec.FLOAT.optionalFieldOf("move", 0.0F).forGetter(Keyframe::move),
            Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(Keyframe::offset),
            Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(Keyframe::rotation),
            Codec.FLOAT.optionalFieldOf("scale", 1.0F).forGetter(Keyframe::scale),
            BlockLook.STATE.optionalFieldOf("state", Map.of()).forGetter(Keyframe::state)
    ).apply(instance, Keyframe::new));

    /** The pose {@code time} ticks into the move (held at the last keyframe afterwards). */
    public static Keyframe at(List<Keyframe> frames, float time) {
        if (frames.isEmpty()) return new Keyframe(0, 0, Vec3.ZERO, Vec3.ZERO, 1, Map.of());
        Keyframe previous = frames.getFirst();
        if (time <= previous.t) return previous;
        for (Keyframe next : frames) {
            if (time <= next.t) {
                float span = next.t - previous.t;
                float f = span <= 0 ? 1 : (time - previous.t) / span;
                return new Keyframe(time, lerp(previous.move, next.move, f), previous.offset.lerp(next.offset, f),
                        previous.rotation.lerp(next.rotation, f), lerp(previous.scale, next.scale, f), previous.state);
            }
            previous = next;
        }
        return previous;
    }

    /** State values in force {@code time} ticks into the move: every keyframe's "state" up to then, in order. */
    public static Map<String, String> stateAt(List<Keyframe> frames, float time) {
        Map<String, String> values = new HashMap<>();
        for (Keyframe frame : frames) {
            if (frame.t > time) break;
            values.putAll(frame.state);
        }
        return values;
    }

    /** Ticks the whole move lasts (the last keyframe's time). */
    public static float duration(List<Keyframe> frames) {
        return frames.isEmpty() ? 0 : frames.getLast().t;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }
}
