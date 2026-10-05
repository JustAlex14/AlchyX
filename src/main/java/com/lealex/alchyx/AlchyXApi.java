package com.lealex.alchyx;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Hooks for mods built on AlchyX. Call them from your mod's constructor or common setup. */
public final class AlchyXApi {
    private AlchyXApi() {}

    private static final List<Predicate<BlockState>> LEGACY_PARTS = new CopyOnWriteArrayList<>();
    private static final Map<Block, Supplier<BlockState>> PISTON_HEADS = new ConcurrentHashMap<>();

    static {
        PISTON_HEADS.put(Blocks.PISTON, Blocks.PISTON_HEAD::defaultBlockState);
        PISTON_HEADS.put(Blocks.STICKY_PISTON, Blocks.PISTON_HEAD::defaultBlockState);
    }

    /**
     * Extra blocks that count as machine parts (besides shells), e.g. part blocks an older version of your mod
     * placed: formed structures keep accepting them and convert them into shells on the next check.
     */
    public static void registerLegacyPart(Predicate<BlockState> predicate) {
        LEGACY_PARTS.add(predicate);
    }

    public static boolean isLegacyPart(BlockState state) {
        for (Predicate<BlockState> predicate : LEGACY_PARTS) {
            if (predicate.test(state)) return true;
        }
        return false;
    }

    /**
     * The head drawn when a shell disguised as this piston-like block strikes (vanilla pistons are built in).
     * The head state gets the piston head's FACING / TYPE / SHORT values set by the renderer.
     */
    public static void registerPistonHead(Block pistonBase, Supplier<BlockState> head) {
        PISTON_HEADS.put(pistonBase, head);
    }

    public static BlockState pistonHeadFor(BlockState pistonBase) {
        return PISTON_HEADS.getOrDefault(pistonBase.getBlock(), Blocks.PISTON_HEAD::defaultBlockState).get();
    }
}
