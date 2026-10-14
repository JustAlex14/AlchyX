package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side list of loaded machine cores (distortion matrices, destructuration cores...), per dimension, with
 * the pattern each one uses. Lets a block change find the machines it may belong to without scanning the area.
 *
 * Every block change in the world asks {@link #matricesNear} (NeighborNotifyEvent), so it must stay cheap with many
 * machines: cores are indexed by chunk, and only the chunks a pattern can reach from the changed block are looked at
 * (usually the block's chunk and its 8 neighbors, a few hash lookups that find nothing).
 */
public final class CoreTracker {
    private static final Map<ResourceKey<Level>, Map<BlockPos, Identifier>> CORES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2ObjectMap<List<BlockPos>>> BY_CHUNK = new HashMap<>();
    // Cores whose structure is not in their pattern (Enclosure): how far around them a block change concerns them.
    // Empty unless a mod has such cores, and then matricesNear looks no further than the widest of them.
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> WATCH = new HashMap<>();
    private static int widestWatch = 0;

    private CoreTracker() {}

    private static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static void add(Level level, BlockPos pos, Identifier patternId) {
        BlockPos core = pos.immutable();
        Identifier previous = CORES.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(core, patternId);
        if (previous == null) {
            BY_CHUNK.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>())
                    .computeIfAbsent(chunkKey(core.getX() >> 4, core.getZ() >> 4), key -> new ArrayList<>(2))
                    .add(core);
        }
    }

    /**
     * A tracked core also hears of block changes within {@code reach} blocks of it along x and z, on its own level
     * and the one above and below (see MultiblockCoreBlockEntity.watchReach).
     */
    public static void watch(Level level, BlockPos pos, int reach) {
        if (reach <= 0) return;
        WATCH.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(pos.immutable(), reach);
        widestWatch = Math.max(widestWatch, reach);
    }

    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Integer> watching = WATCH.get(level.dimension());
        if (watching != null) watching.remove(pos);
        Map<BlockPos, Identifier> cores = CORES.get(level.dimension());
        if (cores == null || cores.remove(pos) == null) return;
        Long2ObjectMap<List<BlockPos>> chunks = BY_CHUNK.get(level.dimension());
        if (chunks == null) return;
        long key = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        List<BlockPos> inChunk = chunks.get(key);
        if (inChunk != null) {
            inChunk.remove(pos);
            if (inChunk.isEmpty()) chunks.remove(key);
        }
    }

    /** Cores whose structure could include pos (inside their pattern's bounding box, or within their watch). */
    public static List<BlockPos> matricesNear(Level level, BlockPos pos) {
        Map<BlockPos, Identifier> cores = CORES.get(level.dimension());
        if (cores == null || cores.isEmpty()) return List.of();
        Long2ObjectMap<List<BlockPos>> chunks = BY_CHUNK.get(level.dimension());
        if (chunks == null) return List.of();
        Map<BlockPos, Integer> watching = WATCH.get(level.dimension());
        if (watching != null && watching.isEmpty()) watching = null;
        int reach = MultiblockPatterns.maxReachChunks();
        if (watching != null) reach = Math.max(reach, (widestWatch + 15) >> 4);
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        List<BlockPos> found = null;
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                List<BlockPos> inChunk = chunks.get(chunkKey(x, z));
                if (inChunk == null) continue;
                for (BlockPos core : inChunk) {
                    MultiblockPattern pattern = MultiblockPatterns.get(cores.get(core));
                    if ((pattern != null && pattern.isWithinStructure(core, pos)) || watches(watching, core, pos)) {
                        if (found == null) found = new ArrayList<>(2);
                        found.add(core);
                    }
                }
            }
        }
        return found == null ? List.of() : found;
    }

    private static boolean watches(@Nullable Map<BlockPos, Integer> watching, BlockPos core, BlockPos pos) {
        if (watching == null) return false;
        Integer reach = watching.get(core);
        return reach != null && Math.abs(pos.getX() - core.getX()) <= reach && Math.abs(pos.getZ() - core.getZ()) <= reach
                && Math.abs(pos.getY() - core.getY()) <= 1;
    }

    /** The formed machine whose structure contains pos (not counting the core itself), or null. */
    public static @Nullable MultiblockCoreBlockEntity formedCoreAt(Level level, BlockPos pos) {
        for (BlockPos center : matricesNear(level, pos)) {
            if (!center.equals(pos)
                    && level.getBlockEntity(center) instanceof MultiblockCoreBlockEntity core
                    && core.isFormed() && core.structureContains(pos)) {
                return core;
            }
        }
        return null;
    }

    public static void clear() {
        CORES.clear();
        BY_CHUNK.clear();
        WATCH.clear();
        widestWatch = 0;
    }
}
