package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;

/**
 * The timing part shared by every effect:
 * "when" (see {@link Trigger}, default always), "interval" (ticks between runs for continuous triggers, default 1),
 * "chance" (0 to 1, default 1), "delay" (ticks to wait after the trigger, default 0) and "min_level" (only while
 * each named level the machine publishes is at least that much, e.g. {"corruption": 4}; missing levels count as 0).
 */
public record Timing(Trigger when, int interval, float chance, int delay, java.util.Map<String, Integer> minLevel) {
    public static final MapCodec<Timing> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Trigger.CODEC.optionalFieldOf("when", Trigger.ALWAYS).forGetter(Timing::when),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("interval", 1).forGetter(Timing::interval),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("chance", 1.0F).forGetter(Timing::chance),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("delay", 0).forGetter(Timing::delay),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("min_level", java.util.Map.of()).forGetter(Timing::minLevel)
    ).apply(instance, Timing::new));

    /** True if every "min_level" is reached ({@code levels}: the machine's level by name, 0 if it has none). */
    public boolean levelsMet(java.util.function.ToIntFunction<String> levels) {
        for (java.util.Map.Entry<String, Integer> entry : minLevel.entrySet()) {
            if (levels.applyAsInt(entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }
}
