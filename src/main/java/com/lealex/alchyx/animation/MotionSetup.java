package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a player set for one block's {@link Motion} in the Machine Studio, besides the motion itself: when in the work
 * it plays ({@code from} to {@code until}, in percent of the operation; 0 to 100 is all the time) and, for
 * {@link Motion#CHANGE}, the looks the block takes, each from its keyframe: {@code steps} (state values, and maybe
 * "block": a cauldron that fills with water) and {@code times} (percent of the work, one per step, in order). Before
 * the first keyframe and after {@code until} the block looks as it is.
 */
public record MotionSetup(int from, int until, List<Map<String, String>> steps, List<Integer> times) {
    public static final MotionSetup ALWAYS = new MotionSetup(0, 100, List.of(), List.of());
    /** The most state values one step sets, and the most steps. */
    public static final int MAX_STATE = 16, MAX_STEPS = 16;

    private static final Codec<Map<String, String>> STATE = Codec.unboundedMap(Codec.STRING, Codec.STRING);

    public static final Codec<MotionSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 100).optionalFieldOf("from", 0).forGetter(MotionSetup::from),
            Codec.intRange(0, 100).optionalFieldOf("until", 100).forGetter(MotionSetup::until),
            STATE.optionalFieldOf("state").forGetter(setup -> Optional.empty()), // (one step, as first saved)
            STATE.listOf().optionalFieldOf("steps", List.of()).forGetter(MotionSetup::steps),
            Codec.INT.listOf().optionalFieldOf("times", List.of()).forGetter(MotionSetup::times)
    ).apply(instance, (from, until, state, steps, times) -> state.isPresent() && steps.isEmpty()
            ? new MotionSetup(from, until, List.of(state.get()), List.of(from)) : new MotionSetup(from, until, steps, times)));

    public static final StreamCodec<ByteBuf, MotionSetup> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MotionSetup::from,
            ByteBufCodecs.VAR_INT, MotionSetup::until,
            ByteBufCodecs.<ByteBuf, String, String, Map<String, String>>map(LinkedHashMap::new, ByteBufCodecs.stringUtf8(64),
                    ByteBufCodecs.stringUtf8(64), MAX_STATE).apply(ByteBufCodecs.list(MAX_STEPS)), MotionSetup::steps,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_STEPS)), MotionSetup::times,
            MotionSetup::new);

    public MotionSetup {
        steps = steps.stream().map(Map::copyOf).toList();
        times = List.copyOf(times);
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
        return new MotionSetup(from, until, steps, times);
    }

    /** When step {@code index} comes (percent of the work): its keyframe, or its even share of the time. */
    public int time(int index) {
        if (times.size() == steps.size()) return times.get(index);
        return Math.round(from + (until - from) * index / (float) Math.max(1, steps.size()));
    }

    /** The step showing at this point (0 to 1) of the work, or -1 (before the first keyframe, or out of its time). */
    public int stepAt(float progress) {
        if (steps.isEmpty() || !plays(progress)) return -1;
        int at = -1;
        for (int i = 0; i < steps.size(); i++) if (progress * 100 >= time(i)) at = i;
        return at;
    }

    /**
     * With step {@code index} set to {@code state}, or a new step at {@code time} if {@code index} is the count (or a
     * step already comes at that very time: that one is set instead).
     */
    public MotionSetup withStep(int index, Map<String, String> state, int time) {
        List<Map<String, String>> newSteps = new ArrayList<>(steps);
        List<Integer> newTimes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) newTimes.add(time(i));
        if (index >= newSteps.size() && newTimes.contains(time)) index = newTimes.indexOf(time);
        if (index >= newSteps.size()) {
            newSteps.add(state);
            newTimes.add(time);
        } else {
            newSteps.set(index, state);
        }
        MotionSetup done = sorted(newSteps, newTimes);
        return time >= until ? done.withTimes(done.from(), 100) : done; // (a step after its end: it plays on to the end)
    }

    /** With step {@code index} coming at {@code time} instead. */
    public MotionSetup withStepTime(int index, int time) {
        List<Integer> newTimes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) newTimes.add(i == index ? time : time(i));
        return sorted(steps, newTimes);
    }

    /** Without step {@code index}. */
    public MotionSetup withoutStep(int index) {
        List<Map<String, String>> newSteps = new ArrayList<>(steps);
        List<Integer> newTimes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) newTimes.add(time(i));
        if (index < newSteps.size()) {
            newSteps.remove(index);
            newTimes.remove(index);
        }
        return sorted(newSteps, newTimes);
    }

    /** The steps in the order of their keyframes; the time starts at the first one. */
    private MotionSetup sorted(List<Map<String, String>> newSteps, List<Integer> newTimes) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < newSteps.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(newTimes.get(a), newTimes.get(b)));
        List<Map<String, String>> s = new ArrayList<>();
        List<Integer> t = new ArrayList<>();
        for (int i : order) {
            s.add(newSteps.get(i));
            t.add(Math.max(0, Math.min(100, newTimes.get(i))));
        }
        int start = t.isEmpty() ? from : t.getFirst();
        return new MotionSetup(start, Math.max(start, until), s, t);
    }

    /** Without steps (for a motion that isn't a change). */
    public MotionSetup withoutSteps() {
        return new MotionSetup(from, until, List.of(), List.of());
    }

    /** The blocks a look may become in a step ("block"): a cauldron holds nothing, water, lava or powder snow. */
    public static List<Block> swaps(BlockState look) {
        if (Motion.isCauldronLook(look)) {
            return List.of(net.minecraft.world.level.block.Blocks.CAULDRON, net.minecraft.world.level.block.Blocks.WATER_CAULDRON,
                    net.minecraft.world.level.block.Blocks.LAVA_CAULDRON, net.minecraft.world.level.block.Blocks.POWDER_SNOW_CAULDRON);
        }
        return List.of();
    }

    /** The block a step makes of {@code look}: its "block" if that's one it may become, else its own. */
    public static Block target(BlockState look, Map<String, String> step) {
        String id = step.get("block");
        Identifier parsed = id == null ? null : Identifier.tryParse(id);
        if (parsed != null) {
            Block block = BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null);
            if (block != null && swaps(look).contains(block)) return block;
        }
        return look.getBlock();
    }

    /**
     * The same setup kept to what {@code look} can do: times in 0 to 100 and in order, and only state values the
     * block (or the block a step makes of it) has; the steps only for {@code motion} {@link Motion#CHANGE}.
     */
    public MotionSetup tidy(Motion motion, BlockState look) {
        int a = Math.max(0, Math.min(100, from)), b = Math.max(0, Math.min(100, until));
        if (motion != Motion.CHANGE) return new MotionSetup(Math.min(a, b), Math.max(a, b), List.of(), List.of());
        List<Map<String, String>> kept = new ArrayList<>();
        List<Integer> keptTimes = new ArrayList<>();
        for (int i = 0; i < steps.size() && kept.size() < MAX_STEPS; i++) {
            Map<String, String> step = steps.get(i), good = new LinkedHashMap<>();
            Block block = target(look, step);
            if (block != look.getBlock()) good.put("block", BuiltInRegistries.BLOCK.getKey(block).toString());
            for (Map.Entry<String, String> entry : step.entrySet()) {
                if (entry.getKey().equals("block") || good.size() >= MAX_STATE) continue;
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                if (property != null && property.getValue(entry.getValue()).isPresent()) good.put(entry.getKey(), entry.getValue());
            }
            if (good.isEmpty()) continue;
            kept.add(good);
            keptTimes.add(Math.max(0, Math.min(100, time(i))));
        }
        return new MotionSetup(Math.min(a, b), Math.max(a, b), List.of(), List.of()).sorted(kept, keptTimes);
    }
}
