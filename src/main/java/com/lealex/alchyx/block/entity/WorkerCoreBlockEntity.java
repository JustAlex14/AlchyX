package com.lealex.alchyx.block.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A core worked by ONE figure that goes on errands over a ground of squares (a farmhand in a field, a miner in a
 * shaft; a cultist at an altar). This holds what such a machine keeps and what both sides must agree on; what the
 * work IS, and when the worker looks for some, are the machine's own.
 *
 * THE GROUND ({@link #field}): squares counted from the core (y = 0: the core's level), sorted by z then x. The
 * machine sets it (a fence search with {@code multiblock/Enclosure}, a fixed place in its pattern...).
 *
 * THE ERRAND: the worker goes from the square it stands on to another ({@code to}), faces {@code target} (what it
 * works on, not always where it stands), and works for a while. It starts at {@code taskStart} (game time), takes
 * {@code travel} ticks on the way, then the work's ticks. {@link #startErrand} begins one and sends it to clients
 * (the machine's sync), where {@code client/animation/Errands} draws the figure from those numbers alone: nothing
 * else is sent while it moves. The work has up to TWO MOMENTS ({@link #first}, {@link #second}), at shares of its
 * length ({@link #firstAt}, {@link #secondAt}); {@link #runMoments} calls each once when its time has come.
 *
 * Saved (and so synced): {@link #saveWork} / {@link #loadWork}, with a square packed into one int.
 */
public abstract class WorkerCoreBlockEntity extends MultiblockCoreBlockEntity {
    /** The ground's squares, counted from the core. Set by the machine; never changed in place. */
    protected List<BlockPos> field = List.of();

    // The worker's last errand: afterwards it stays on "to"
    protected BlockPos from = BlockPos.ZERO, to = BlockPos.ZERO, target = BlockPos.ZERO;
    protected int action = 0, travel = 0;
    protected long taskStart = Long.MIN_VALUE / 2;
    protected boolean firstDone = true, secondDone = true; // the errand's two moments are behind
    protected boolean went;                                // an errand was started since this was last cleared

    protected WorkerCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- What the machine says ----

    /** The shares of the work (0 to 1) at which {@link #first} and {@link #second} happen. */
    protected abstract float firstAt();

    protected abstract float secondAt();

    /** The errand's work. Only called for the moments {@link #startErrand} was told the errand has. */
    protected abstract void first(ServerLevel server);

    protected abstract void second(ServerLevel server);

    /** The worker is free: start an errand, or do nothing. */
    protected abstract void findWork(ServerLevel server, long now);

    // ---- What screens and the figure read (both sides) ----

    public List<BlockPos> field() {
        return field;
    }

    public long taskStart() {
        return taskStart;
    }

    public int travelTicks() {
        return travel;
    }

    /** The current errand's action, in the machine's own numbers. */
    public int action() {
        return action;
    }

    /**
     * A number from 0 to 1 that is the current errand's own (both sides: worked out from its start time), for what
     * should not be the same from one errand to the next (how long the work takes, when something happens in it).
     */
    protected float whim() {
        long mixed = taskStart * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 29;
        return ((mixed >>> 11) & 0x3FF) / 1023.0F;
    }

    // ---- The errand ----

    /**
     * Starts an errand: the worker goes from where it stands to {@code stand} in {@code travel} ticks, faces
     * {@code target} and works. Sent to clients at the end of the tick.
     *
     * @param hasFirst whether {@link #first} is to be called for it; hasSecond the same for {@link #second}
     */
    protected void startErrand(BlockPos stand, BlockPos target, int action, long now, int travel, boolean hasFirst, boolean hasSecond) {
        went = true;
        from = to;
        to = stand;
        this.target = target;
        this.action = action;
        taskStart = now;
        this.travel = travel;
        firstDone = !hasFirst;
        secondDone = !hasSecond;
        setChanged();
        syncToClients();
    }

    /** Calls the errand's moments whose time has come ({@code since} ticks after its start, for work of this length). */
    protected void runMoments(ServerLevel server, long since, int work) {
        if (!firstDone && since >= travel + work * firstAt()) {
            firstDone = true;
            first(server);
            setChanged();
        }
        if (!secondDone && since >= travel + work * secondAt()) {
            secondDone = true;
            second(server);
            setChanged();
        }
    }

    // ---- The ground's geometry ----

    /** The world position of a square of the ground. */
    protected BlockPos world(BlockPos square) {
        return worldPosition.offset(square);
    }

    /** The square of the ground this world position is on (whatever its height), which may not be in the field. */
    protected BlockPos square(BlockPos pos) {
        return new BlockPos(pos.getX() - worldPosition.getX(), 0, pos.getZ() - worldPosition.getZ());
    }

    /** The field square nearest to the core: where the worker first stands. */
    protected BlockPos byThePost() {
        return nearest(0, 0);
    }

    /** The field square nearest to the field's middle. */
    protected BlockPos middle() {
        double x = 0, z = 0;
        for (BlockPos square : field) {
            x += square.getX();
            z += square.getZ();
        }
        return nearest(x / Math.max(1, field.size()), z / Math.max(1, field.size()));
    }

    /** The field square nearest to this spot (counted from the core). */
    protected BlockPos nearest(double x, double z) {
        BlockPos best = BlockPos.ZERO;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos square : field) {
            double distance = (square.getX() - x) * (square.getX() - x) + (square.getZ() - z) * (square.getZ() - z);
            if (distance < bestDistance) {
                best = square;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Where the worker stands to work on this square from the side: on a square of the field beside it (the nearest
     * to where it is now), so its work is in front of it. On the square itself if it has no neighbor in the field.
     */
    protected BlockPos besides(BlockPos square) {
        BlockPos best = square;
        double bestDistance = Double.MAX_VALUE;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = square.relative(side);
            if (!field.contains(beside)) continue;
            double distance = beside.distSqr(to);
            if (distance < bestDistance) {
                best = beside;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ---- Saving (also what clients get: a square is packed into one int to keep the update small) ----

    /** A square within 64 blocks of the core, as one int. */
    protected static int pack(BlockPos square) {
        return (square.getX() + 64) | ((square.getZ() + 64) << 8);
    }

    protected static BlockPos unpack(int packed) {
        return new BlockPos((packed & 0xFF) - 64, 0, ((packed >> 8) & 0xFF) - 64);
    }

    /** Writes the ground and the errand (call from saveAdditional). */
    protected void saveWork(ValueOutput output) {
        int[] squares = new int[field.size()];
        for (int i = 0; i < squares.length; i++) squares[i] = pack(field.get(i));
        output.putIntArray("squares", squares);
        output.putInt("from", pack(from));
        output.putInt("to", pack(to));
        output.putInt("target", pack(target));
        output.putInt("action", action);
        output.putInt("travel", travel);
        output.putLong("taskStart", taskStart);
        output.putBoolean("firstDone", firstDone);
        output.putBoolean("secondDone", secondDone);
    }

    /** Reads them back (call from loadAdditional). */
    protected void loadWork(ValueInput input) {
        int[] squares = input.getIntArray("squares").orElse(new int[0]);
        List<BlockPos> loaded = new ArrayList<>(squares.length);
        for (int packed : squares) loaded.add(unpack(packed));
        field = List.copyOf(loaded);
        from = unpack(input.getIntOr("from", pack(BlockPos.ZERO)));
        to = unpack(input.getIntOr("to", pack(BlockPos.ZERO)));
        target = unpack(input.getIntOr("target", pack(to)));
        action = input.getIntOr("action", 0);
        travel = input.getIntOr("travel", 0);
        taskStart = input.getLongOr("taskStart", Long.MIN_VALUE / 2);
        firstDone = input.getBooleanOr("firstDone", true);
        secondDone = input.getBooleanOr("secondDone", true);
    }
}
