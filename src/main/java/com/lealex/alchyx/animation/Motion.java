package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A ready-made motion a player can give one block of a free-build machine with the Structure Recorder or the Machine
 * Studio (no animation file to write).
 * <ul>
 *   <li>Moves (spin, bob, strike, swing, shake): a looping block move that hides the block and draws a moving copy
 *       of it. A piston strikes like a piston: its head shoots out and back, its base shows extended meanwhile.</li>
 *   <li>Changes (light, fill, open): the block's own look changes while the machine works (or rests, as the machine
 *       says): a furnace or campfire lights up, a cauldron or composter fills as the work goes on, a barrel or
 *       trapdoor opens. These only fit the blocks that can do it ({@link #fits}).</li>
 * </ul>
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
            frame(2, 0, new Vec3(-0.03, 0.02, 0.04), 0), frame(3, 0, new Vec3(0.03, 0, 0.02), 0), frame(4, 0, Vec3.ZERO, 0))),
    /** Lights up (furnaces, smokers, campfires, candles, redstone lamps: anything that can be lit). */
    LIGHT("light", "up", List.of()),
    /**
     * Fills up as the work goes on: a cauldron with water, and any block (modded ones too) whose fill is a number
     * in its state: composters, beehives, respawn anchors, snow layers, candles, sea pickles...
     */
    FILL("fill", "up", List.of()),
    /** A cauldron fills with lava. */
    FILL_LAVA("fill_lava", "up", List.of()),
    /** A cauldron fills with powder snow. */
    FILL_SNOW("fill_snow", "up", List.of()),
    /** Grows as the work goes on: crops, saplings' stages, anything with an "age" (modded crops too). */
    GROW("grow", "up", List.of()),
    /** Opens (barrels, trapdoors, doors, fence gates). */
    OPEN("open", "up", List.of()),
    /**
     * Takes the state values its player picked (see {@link MotionSetup}): any block with a state, modded ones too
     * (a trial spawner turned active, a vault ejecting, a lamp lit...).
     */
    CHANGE("change", "up", List.of());

    /** The state numbers that say how full a block is, by name (vanilla's and most mods'). */
    private static final List<String> FILL_PROPERTIES = List.of("level", "honey_level", "charges", "layers", "pickles", "candles",
            "eggs", "flower_amount", "segment_amount");

    /** A piston's strike: the head shoots out, stays a moment, and comes back (see ANIMATIONS.md, block_move). */
    private static final List<Keyframe> PISTON = List.of(
            new Keyframe(0, 0, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of("short", "true")),
            new Keyframe(1, 0.5F, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of("short", "false")),
            new Keyframe(2, 1, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of()),
            new Keyframe(6, 1, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of()),
            new Keyframe(7.5F, 0.5F, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of("short", "true")),
            new Keyframe(9, 0, Vec3.ZERO, Vec3.ZERO, 1.0F, Map.of()));
    private static final int PISTON_EVERY = 30;

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

    /** The motion's keyframes, one loop (none for a change of look). */
    public List<Keyframe> keyframes() {
        return keyframes;
    }

    /** Whether it moves a copy of the block (spin, bob...), rather than changing the block's own look. */
    public boolean moves() {
        return !keyframes.isEmpty();
    }

    /** Whether a block that looks like this can do it (moves fit any block; changes only blocks that have them). */
    public boolean fits(BlockState look) {
        return switch (this) {
            case LIGHT -> look.hasProperty(BlockStateProperties.LIT);
            case OPEN -> look.hasProperty(BlockStateProperties.OPEN);
            case FILL, FILL_LAVA, FILL_SNOW, GROW -> !steps(look).isEmpty();
            case CHANGE -> !look.getProperties().isEmpty() || isCauldronLook(look);
            default -> true;
        };
    }

    /** Whether this block strikes as a piston (its head shoots out) rather than as a whole. */
    public static boolean isPiston(BlockState look) {
        return look.is(Blocks.PISTON) || look.is(Blocks.STICKY_PISTON);
    }

    /**
     * The looks a change of look goes through, as state values (and "block" for another block), from the start of
     * the work to its end. Empty for a move, or a block that can't do it.
     */
    public List<Map<String, String>> steps(BlockState look) {
        return switch (this) {
            case LIGHT -> fits(look) ? List.of(Map.of("lit", "true")) : List.of();
            case OPEN -> fits(look) ? List.of(Map.of("open", "true")) : List.of();
            case FILL -> fillSteps(look);
            case FILL_LAVA -> isCauldron(look) ? List.of(Map.of("block", "minecraft:lava_cauldron")) : List.of();
            case FILL_SNOW -> isCauldron(look) ? levels(Blocks.POWDER_SNOW_CAULDRON, "level", 1, 3) : List.of();
            case GROW -> numberSteps(look, "age");
            default -> List.of();
        };
    }

    /** Whether it's a cauldron (empty, or with water, lava or powder snow): a change may swap what it holds. */
    public static boolean isCauldronLook(BlockState look) {
        return isCauldron(look);
    }

    private static boolean isCauldron(BlockState look) {
        return look.is(Blocks.CAULDRON) || look.is(Blocks.WATER_CAULDRON) || look.is(Blocks.LAVA_CAULDRON) || look.is(Blocks.POWDER_SNOW_CAULDRON);
    }

    private static List<Map<String, String>> fillSteps(BlockState look) {
        if (look.is(Blocks.CAULDRON) || look.is(Blocks.LAVA_CAULDRON)) return levels(Blocks.WATER_CAULDRON, "level", 1, 3);
        for (String name : FILL_PROPERTIES) {
            List<Map<String, String>> steps = numberSteps(look, name);
            if (!steps.isEmpty()) return steps;
        }
        return List.of();
    }

    /** Each value of a number in the block's state after its lowest, in order (its fill, its age), or none. */
    private static List<Map<String, String>> numberSteps(BlockState look, String name) {
        if (!(look.getBlock().getStateDefinition().getProperty(name)
                instanceof net.minecraft.world.level.block.state.properties.IntegerProperty number)) return List.of();
        List<Integer> values = number.getPossibleValues().stream().sorted().toList();
        List<Map<String, String>> steps = new java.util.ArrayList<>();
        for (int i = 1; i < values.size(); i++) steps.add(Map.of(name, Integer.toString(values.get(i))));
        return List.copyOf(steps);
    }

    private static List<Map<String, String>> levels(Block block, String property, int from, int to) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        List<Map<String, String>> steps = new java.util.ArrayList<>();
        for (int level = from; level <= to; level++) steps.add(Map.of("block", id, property, Integer.toString(level)));
        return List.copyOf(steps);
    }

    /** The way it moves along: "up", or "facing" (the block's facing). */
    public String direction() {
        return direction;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /**
     * The motion as an animation effect on the block named by this anchor, which looks like {@code look}, playing
     * while {@code when} holds. Null if that block can't do it.
     */
    public @org.jspecify.annotations.Nullable Effect effect(String anchor, Trigger when, BlockState look) {
        return effect(anchor, when, look, MotionSetup.ALWAYS);
    }

    /** The same, as {@code setup} says: only in its window of the work, and for a change, the state it picked. */
    public @org.jspecify.annotations.Nullable Effect effect(String anchor, Trigger when, BlockState look, MotionSetup setup) {
        Target target = new Target(Optional.of(anchor), Optional.empty(), Map.of(), Vec3.ZERO);
        float from = setup.from() / 100.0F, until = setup.until() / 100.0F;
        if (!moves()) {
            List<Map<String, String>> steps = steps(look, setup);
            if (steps.isEmpty()) return null;
            Timing timing = new Timing(when, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until);
            if (this == CHANGE) { // each step from its keyframe
                List<Float> times = new java.util.ArrayList<>();
                for (int i = 0; i < steps.size(); i++) times.add(setup.time(i) / 100.0F);
                return new Effect.StateSteps(timing, target, steps, times);
            }
            return new Effect.StateSteps(timing, target, steps);
        }
        if (this == STRIKE && isPiston(look)) {
            String head = look.is(Blocks.STICKY_PISTON) ? "sticky" : "normal";
            Timing timing = new Timing(when, PISTON_EVERY, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until);
            return new Effect.BlockMove(timing, target, Optional.of(new BlockLook(Blocks.PISTON_HEAD, Map.of("type", head))),
                    "facing", true, Map.of("extended", "true"), PISTON, false);
        }
        int loop = Math.max(1, (int) Keyframe.duration(keyframes));
        Timing timing = new Timing(when, loop, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until);
        return new Effect.BlockMove(timing, target, Optional.empty(), direction, false, Map.of(), keyframes, true);
    }

    /** The looks a change goes through, as {@code setup} says (a change: its steps, each from its keyframe). */
    public List<Map<String, String>> steps(BlockState look, MotionSetup setup) {
        if (this == CHANGE) return setup.steps();
        return steps(look);
    }

    /** The piston strike's keyframes (the head's way out and back), for previews. */
    public static List<Keyframe> pistonKeyframes() {
        return PISTON;
    }

    /** How often a piston strikes, in ticks. */
    public static int pistonEvery() {
        return PISTON_EVERY;
    }
}
