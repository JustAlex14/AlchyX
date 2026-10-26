package com.lealex.alchyx.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A quick walking path over blocks for a player-sized body (two blocks high), for scripted walks (a player led to a
 * machine): A* over feet positions, eight ways (no cutting corners), a step or a jump up one block, a drop of up to
 * two. Fire, lava and magma are never walked through. `through` marks blocks the walker may pass through anyway
 * (a machine's own parts) at a cost per block, so it goes round them when it can. Bounded by `reach` from the start
 * and by `most` nodes; null when no way is found.
 */
public final class StepPath {
    private StepPath() {}

    /** A cell of the way: where the feet go, and whether getting there passes through `through` blocks. */
    public record Step(BlockPos at, boolean through) {}

    private record Node(long key, BlockPos pos, int cost, int estimate, @Nullable Node from, boolean through) {}

    private static final int[][] WAYS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] RISES = {0, 1, -1, -2};

    public static @Nullable List<Step> find(BlockGetter level, BlockPos start, BlockPos goal, int reach, int most,
                                            Predicate<BlockPos> through, int throughCost) {
        Cells cells = new Cells(level, through);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Integer.compare(a.cost() + a.estimate(), b.cost() + b.estimate()));
        Map<Long, Integer> best = new HashMap<>();
        Node first = new Node(start.asLong(), start, 0, estimate(start, goal), null, false);
        open.add(first);
        best.put(first.key(), 0);
        int seen = 0;
        while (!open.isEmpty() && seen++ < most) {
            Node node = open.poll();
            if (node.pos().equals(goal)) return walkBack(node);
            if (best.getOrDefault(node.key(), Integer.MAX_VALUE) < node.cost()) continue;
            BlockPos p = node.pos();
            for (int[] way : WAYS) {
                boolean diagonal = way[0] != 0 && way[1] != 0;
                if (diagonal && !(cells.open(p.offset(way[0], 0, 0)) && cells.open(p.offset(0, 0, way[1])))) continue;
                for (int rise : RISES) {
                    BlockPos q = p.offset(way[0], rise, way[1]);
                    if (Math.abs(q.getX() - start.getX()) > reach || Math.abs(q.getZ() - start.getZ()) > reach
                            || Math.abs(q.getY() - start.getY()) > reach) continue;
                    if (!cells.stand(q)) continue;
                    if (rise > 0 && cells.passCost(p.above(2)) < 0) continue; // room to jump
                    if (rise < 0 && !(cells.open(p.offset(way[0], 0, way[1])))) continue; // walk off the edge first
                    int pass = cells.passCost(q) + cells.passCost(q.above());
                    int cost = node.cost() + (diagonal ? 14 : 10) + (rise > 0 ? 6 : rise < 0 ? 2 : 0) + pass * throughCost;
                    long key = q.asLong();
                    if (cost >= best.getOrDefault(key, Integer.MAX_VALUE)) break;
                    best.put(key, cost);
                    open.add(new Node(key, q, cost, estimate(q, goal), node, pass > 0));
                    break; // the first rise that works for this way
                }
            }
        }
        return null;
    }

    /**
     * Fewer turns: from each point, straight on to the furthest later cell on the same level that a body (0.7 wide with
     * its berth, two high) can walk to in a line (clear all along, ground under it, no `through` cell merged), so a walk
     * crosses open ground diagonally instead of zigzagging over cell centres. `start` is where the walker stands.
     */
    public static List<Step> straighten(BlockGetter level, net.minecraft.world.phys.Vec3 start, List<Step> steps) {
        List<Step> out = new ArrayList<>();
        net.minecraft.world.phys.Vec3 from = start;
        int i = -1;
        while (i < steps.size() - 1) {
            int j = steps.size() - 1;
            for (; j > i + 1; j--) {
                boolean ok = true;
                for (int k = i + 1; k <= j && ok; k++) ok = !steps.get(k).through() && steps.get(k).at().getY() == steps.get(j).at().getY();
                if (ok && Math.abs(from.y - steps.get(j).at().getY()) < 0.1 && walkable(level, from, centre(steps.get(j).at()))) break;
            }
            out.add(steps.get(j));
            from = centre(steps.get(j).at());
            i = j;
        }
        return out;
    }

    /** The middle of a cell's floor (where the feet go). */
    public static net.minecraft.world.phys.Vec3 centre(BlockPos at) {
        return new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static boolean walkable(BlockGetter level, net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b) {
        int samples = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 0.25));
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int n = 0; n <= samples; n++) {
            net.minecraft.world.phys.Vec3 p = a.lerp(b, n / (double) samples);
            int feet = net.minecraft.util.Mth.floor(p.y + 0.01);
            for (int x = net.minecraft.util.Mth.floor(p.x - 0.35); x <= net.minecraft.util.Mth.floor(p.x + 0.35); x++) {
                for (int z = net.minecraft.util.Mth.floor(p.z - 0.35); z <= net.minecraft.util.Mth.floor(p.z + 0.35); z++) {
                    for (int y = feet; y <= feet + 1; y++) {
                        at.set(x, y, z);
                        if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false;
                    }
                }
            }
            at.set(net.minecraft.util.Mth.floor(p.x), feet - 1, net.minecraft.util.Mth.floor(p.z));
            if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false; // a hole
        }
        return true;
    }

    private static int estimate(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX()), dz = Math.abs(a.getZ() - b.getZ()), dy = Math.abs(a.getY() - b.getY());
        return 10 * Math.max(dx, dz) + 4 * Math.min(dx, dz) + 3 * dy;
    }

    private static List<Step> walkBack(Node end) {
        List<Step> steps = new ArrayList<>();
        for (Node n = end; n.from() != null; n = n.from()) steps.add(new Step(n.pos(), n.through()));
        Collections.reverse(steps);
        return steps;
    }

    /** What each block is for the walker (asked once per block). */
    private static final class Cells {
        private final BlockGetter level;
        private final Predicate<BlockPos> through;
        private final Map<Long, Integer> kinds = new HashMap<>(); // 0 open, 1 through, -1 blocked, -2 hurts

        Cells(BlockGetter level, Predicate<BlockPos> through) {
            this.level = level;
            this.through = through;
        }

        private int kind(BlockPos pos) {
            return kinds.computeIfAbsent(pos.asLong(), k -> {
                BlockState state = level.getBlockState(pos);
                if (state.is(BlockTags.FIRE) || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                        || state.getFluidState().is(FluidTags.LAVA)) return -2;
                if (state.getCollisionShape(level, pos).isEmpty()) return 0;
                return through.test(pos) ? 1 : -1;
            });
        }

        /** 0 open, 1 passable through, -1 not. */
        int passCost(BlockPos pos) {
            int kind = kind(pos);
            return kind < 0 ? -1 : kind;
        }

        boolean open(BlockPos pos) {
            return passCost(pos) >= 0 && passCost(pos.above()) >= 0;
        }

        /** Feet here: both blocks passable, something to stand on (not something that hurts). */
        boolean stand(BlockPos pos) {
            if (!open(pos)) return false;
            int below = kind(pos.below());
            return below == -1 || below == 1;
        }
    }
}
