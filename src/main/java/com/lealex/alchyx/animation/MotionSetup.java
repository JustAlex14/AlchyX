package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a player set for one block's {@link Motion} in the Machine Studio, besides the motion itself: when in the work
 * it plays ({@code from} to {@code until}, in percent of the operation; 0 to 100 is all the time) and, for
 * {@link Motion#CHANGE}, the state values the block takes (a trial spawner's "trial_spawner_state": "active").
 */
public record MotionSetup(int from, int until, Map<String, String> state) {
    public static final MotionSetup ALWAYS = new MotionSetup(0, 100, Map.of());
    /** The most state values one block's change sets. */
    public static final int MAX_STATE = 16;

    public static final Codec<MotionSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 100).optionalFieldOf("from", 0).forGetter(MotionSetup::from),
            Codec.intRange(0, 100).optionalFieldOf("until", 100).forGetter(MotionSetup::until),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("state", Map.of()).forGetter(MotionSetup::state)
    ).apply(instance, MotionSetup::new));

    public static final StreamCodec<ByteBuf, MotionSetup> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MotionSetup::from,
            ByteBufCodecs.VAR_INT, MotionSetup::until,
            ByteBufCodecs.map(LinkedHashMap::new, ByteBufCodecs.stringUtf8(64), ByteBufCodecs.stringUtf8(64), MAX_STATE), MotionSetup::state,
            MotionSetup::new);

    public MotionSetup {
        state = Map.copyOf(state);
    }

    /** Whether it plays all the time (no window in the work). */
    public boolean always() {
        return from <= 0 && until >= 100;
    }

    /** Whether it plays at this point (0 to 1) of the work. */
    public boolean plays(float progress) {
        return always() || (progress * 100 >= from && progress * 100 <= until);
    }

    public MotionSetup withTimes(int from, int until) {
        return new MotionSetup(from, until, state);
    }

    public MotionSetup withState(Map<String, String> state) {
        return new MotionSetup(from, until, state);
    }

    /**
     * The same setup kept to what {@code look} can do: times in 0 to 100 and in order, and only state values the
     * block has (a value it can take, by its name); the state only for {@code motion} {@link Motion#CHANGE}.
     */
    public MotionSetup tidy(Motion motion, BlockState look) {
        int a = Math.max(0, Math.min(100, from)), b = Math.max(0, Math.min(100, until));
        Map<String, String> kept = new LinkedHashMap<>();
        if (motion == Motion.CHANGE) {
            for (Map.Entry<String, String> entry : state.entrySet()) {
                if (kept.size() >= MAX_STATE) break;
                Property<?> property = look.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property != null && property.getValue(entry.getValue()).isPresent()) kept.put(entry.getKey(), entry.getValue());
            }
        }
        return new MotionSetup(Math.min(a, b), Math.max(a, b), kept);
    }
}
