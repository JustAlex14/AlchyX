package com.lealex.alchyx.animation;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Motions other mods add to the Machine Studio, beside its own (spin, strike...): a Create addon can add "turn like
 * a shaft", "press like a mechanical press", "crush like a crushing wheel"... Players find them on the Blocks tab
 * (for the blocks they fit) and give them to a machine's blocks, alone or with other motions (Shift + click), and
 * Export writes them into animation files.
 * <p>
 * A motion is a loop of {@link Keyframe}s, like a "block_move" in an animation file: a move along
 * {@code direction} ("facing": the way the block faces; "up", "north"...: that way, turned with the machine), an
 * offset, a turn (degrees around x, y and z, around the block's middle), a size, and state values (the block's
 * look at that keyframe: a press's head down...). Register on both sides, in your mod's constructor, so the server
 * keeps it on the machines and the clients play it.
 * <p>
 * For a block whose own renderer animates it (a Create shaft that turns with its speed, a fan...), register a
 * {@link PartAnimators part animator} instead: the machine's part then plays the block's own animation.
 */
public final class StudioMotions {
    private StudioMotions() {}

    /** One motion: its name in the studio, the blocks it fits, the way it moves and its keyframes (one loop). */
    public record Custom(Identifier id, String name, Predicate<BlockState> fits, String direction, List<Keyframe> keyframes) {
        /** The loop's keyframes, sorted by time. */
        public Custom {
            keyframes = keyframes.stream().sorted(java.util.Comparator.comparingDouble(Keyframe::t)).toList();
            if (keyframes.isEmpty()) throw new IllegalArgumentException("A studio motion needs keyframes: " + id);
        }
    }

    private static final Map<Identifier, Custom> MOTIONS = new ConcurrentHashMap<>();

    /**
     * Adds a motion to the studio.
     *
     * @param name      what the studio's button says ("Press")
     * @param fits      the blocks it may be given to (any block: {@code state -> true})
     * @param direction the way "move" goes: "facing", "up", "down", "north"... (turned with the machine)
     * @param keyframes one loop of it (played again and again while it plays)
     */
    public static void register(Identifier id, String name, Predicate<BlockState> fits, String direction, List<Keyframe> keyframes) {
        MOTIONS.put(id, new Custom(id, name, fits, direction, keyframes));
    }

    /** The same, for the blocks given only. */
    public static void register(Identifier id, String name, List<Block> blocks, String direction, List<Keyframe> keyframes) {
        register(id, name, state -> blocks.contains(state.getBlock()), direction, keyframes);
    }

    public static @Nullable Custom get(@Nullable Identifier id) {
        return id == null ? null : MOTIONS.get(id);
    }

    /** Every registered motion, sorted by id. */
    public static List<Custom> all() {
        return MOTIONS.values().stream().sorted(java.util.Comparator.comparing(custom -> custom.id().toString())).toList();
    }

    /** Whether that motion is registered and fits that block. */
    public static boolean fits(Identifier id, BlockState look) {
        Custom custom = MOTIONS.get(id);
        return custom != null && custom.fits().test(look);
    }
}
