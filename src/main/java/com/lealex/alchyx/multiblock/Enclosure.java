package com.lealex.alchyx.multiblock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A structure no pattern file can describe: a line of blocks that starts at a core and closes around some ground,
 * in any shape (a fence round a field, a wall round a yard). {@link #find} follows the line and says what it closes
 * in. Flat: the line and the ground are on the core's level.
 *
 * A machine using it keeps a one-block pattern for its core (so everything else of AlchyX still works for it),
 * returns the search's reach from {@code MultiblockCoreBlockEntity.watchReach()} so that block changes around it ask
 * for a structure check, and searches again only then: the search reads every block of the line, it is not for
 * every tick.
 */
public final class Enclosure {
    private Enclosure() {}

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * @param line   every block of the line, the core included (world positions)
     * @param inside the squares closed in, counted from the core (y = 0), sorted by z then x; empty if the line
     *               closes around nothing
     * @param minX   the inside's bounding box, counted from the core (all 0 when inside is empty)
     */
    public record Found(Set<BlockPos> line, List<BlockPos> inside, int minX, int minZ, int maxX, int maxZ) {
        public int width() {
            return inside.isEmpty() ? 0 : maxX - minX + 1;
        }

        public int depth() {
            return inside.isEmpty() ? 0 : maxZ - minZ + 1;
        }
    }

    /**
     * Follows the line from the core: blocks on its level that {@code joins} accepts, side by side (never
     * diagonally), no further than {@code reach} blocks from the core along x or z, {@code most} blocks at most.
     * Then finds the ground it closes in: every square the outside can't get to, where the outside also slips
     * through diagonal gaps (two blocks of the line touching by a corner close nothing).
     */
    public static Found find(BlockGetter level, BlockPos core, Predicate<BlockState> joins, int reach, int most) {
        Set<BlockPos> line = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = core.immutable();
        line.add(start);
        queue.add(start);
        int minX = start.getX(), maxX = minX, minZ = start.getZ(), maxZ = minZ;
        while (!queue.isEmpty() && line.size() < most) {
            BlockPos at = queue.poll();
            for (Direction side : SIDES) {
                BlockPos next = at.relative(side);
                if (Math.abs(next.getX() - start.getX()) > reach || Math.abs(next.getZ() - start.getZ()) > reach) continue;
                if (line.contains(next) || !joins.test(level.getBlockState(next))) continue;
                line.add(next);
                queue.add(next);
                minX = Math.min(minX, next.getX());
                maxX = Math.max(maxX, next.getX());
                minZ = Math.min(minZ, next.getZ());
                maxZ = Math.max(maxZ, next.getZ());
            }
        }
        if (line.size() < 4) return new Found(line, List.of(), 0, 0, 0, 0); // too short to close around anything

        // A grid one square wider than the line on every side: the outside floods in from its corner
        int wide = maxX - minX + 3, deep = maxZ - minZ + 3;
        boolean[] wall = new boolean[wide * deep], outside = new boolean[wide * deep];
        for (BlockPos pos : line) wall[(pos.getZ() - minZ + 1) * wide + pos.getX() - minX + 1] = true;
        int[] flood = new int[wide * deep];
        int head = 0, tail = 0;
        outside[0] = true;
        flood[tail++] = 0;
        while (head < tail) {
            int cell = flood[head++], x = cell % wide, z = cell / wide;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= wide || nz >= deep) continue;
                    int next = nz * wide + nx;
                    if (wall[next] || outside[next]) continue;
                    outside[next] = true;
                    flood[tail++] = next;
                }
            }
        }
        List<BlockPos> inside = new ArrayList<>();
        int inMinX = Integer.MAX_VALUE, inMaxX = Integer.MIN_VALUE, inMinZ = Integer.MAX_VALUE, inMaxZ = Integer.MIN_VALUE;
        for (int z = 0; z < deep; z++) { // row by row: sorted by z then x
            for (int x = 0; x < wide; x++) {
                if (wall[z * wide + x] || outside[z * wide + x]) continue;
                int rx = x + minX - 1 - start.getX(), rz = z + minZ - 1 - start.getZ();
                inside.add(new BlockPos(rx, 0, rz));
                inMinX = Math.min(inMinX, rx);
                inMaxX = Math.max(inMaxX, rx);
                inMinZ = Math.min(inMinZ, rz);
                inMaxZ = Math.max(inMaxZ, rz);
            }
        }
        if (inside.isEmpty()) return new Found(line, List.of(), 0, 0, 0, 0);
        return new Found(line, List.copyOf(inside), inMinX, inMinZ, inMaxX, inMaxZ);
    }
}
