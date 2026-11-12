package com.lealex.alchyx.animation;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Lets a block keep its own animation once it's part of a machine. A machine's part draws the block it looks like
 * with that block's own block entity (a "puppet": never in the world, only drawn and ticked on the clients). A part
 * animator is called every tick for those puppets, with the machine's state, so a mod sets what its renderer reads:
 * a Create shaft's speed while the machine works (it turns), a fan's, a press's progress, a lamp's power...
 * <p>
 * Register on the client (client setup). For example, for Create:
 * <pre>{@code
 * PartAnimators.register(state -> state.getBlock() instanceof KineticBlock, (puppet, look, machine) -> {
 *     if (puppet instanceof KineticBlockEntity kinetic) kinetic.setSpeed(machine.working() ? 64 : 0);
 * });
 * }</pre>
 * A block with an animator gets its puppet even without data of its own (a block put in with the studio's Build tab).
 */
public final class PartAnimators {
    private PartAnimators() {}

    /** The machine a part belongs to, as its animation sees it. */
    public record Machine(@Nullable MultiblockCoreBlockEntity core, boolean working, float progress, long time) {}

    @FunctionalInterface
    public interface PartAnimator {
        /** Sets {@code puppet} up for this tick ({@code look}: the block the part looks like now). */
        void animate(BlockEntity puppet, BlockState look, Machine machine);
    }

    private record Entry(Predicate<BlockState> blocks, PartAnimator animator) {}

    private static final List<Entry> ANIMATORS = new CopyOnWriteArrayList<>();

    public static void register(Predicate<BlockState> blocks, PartAnimator animator) {
        ANIMATORS.add(new Entry(blocks, animator));
        ANIMATED.clear();
    }

    // Whether a look has an animator, by look (asked for every visible part every frame)
    private static final java.util.Map<BlockState, Boolean> ANIMATED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void register(Block block, PartAnimator animator) {
        register(state -> state.is(block), animator);
    }

    /** Whether some mod animates parts that look like this. */
    public static boolean animates(BlockState look) {
        if (ANIMATORS.isEmpty()) return false;
        return ANIMATED.computeIfAbsent(look, state -> {
            for (Entry entry : ANIMATORS) if (entry.blocks().test(state)) return true;
            return false;
        });
    }

    /** Calls the animators of this look (AlchyX does, every tick of a part's puppet). */
    public static void animate(BlockEntity puppet, BlockState look, Machine machine) {
        for (Entry entry : ANIMATORS) {
            if (!entry.blocks().test(look)) continue;
            try {
                entry.animator().animate(puppet, look, machine);
            } catch (RuntimeException e) {
                com.lealex.alchyx.AlchyX.LOGGER.warn("AlchyX: a part animator failed for {}: {}", look, e.toString());
            }
        }
    }
}
