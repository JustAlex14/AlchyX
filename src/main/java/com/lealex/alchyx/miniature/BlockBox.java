package com.lealex.alchyx.miniature;

import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A box of blocks to show in small: a room, a machine, a piece of landscape. Positions are counted from the box's
 * lowest corner: x east, y up, z south. Made on the server from the world ({@link #capture}) or from any list of
 * blocks ({@link #of}), sent to clients in a compact form, drawn there by the client's BoxMesh.
 *
 * A {@link #trimmed} box has an outer layer that is not part of the picture (a room's walls and ceiling): it is
 * left out, and nothing hides the blocks that touch it.
 */
public final class BlockBox {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Refuse sizes no box should have (a broken or hostile packet). */
    public static final int MAX_SIDE = 64;

    public final int sizeX, sizeY, sizeZ;
    public final boolean trimmed;
    private final BlockState[] cells;

    private BlockBox(int sizeX, int sizeY, int sizeZ, boolean trimmed, BlockState[] cells) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.trimmed = trimmed;
        this.cells = cells;
    }

    /** Nothing to show: sent to wipe the picture a client still holds. */
    public static BlockBox empty() {
        return new BlockBox(1, 1, 1, false, new BlockState[] {AIR});
    }

    public boolean isEmpty() {
        return cells.length == 1 && cells[0].isAir();
    }

    /** A box filled by the caller: {@code filler} is asked for every position (x, y, z from 0). */
    public static BlockBox of(int sizeX, int sizeY, int sizeZ, boolean trimmed, Filler filler) {
        BlockBox box = new BlockBox(sizeX, sizeY, sizeZ, trimmed, new BlockState[sizeX * sizeY * sizeZ]);
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) box.cells[box.index(x, y, z)] = filler.at(x, y, z);
            }
        }
        return box;
    }

    @FunctionalInterface
    public interface Filler {
        BlockState at(int x, int y, int z);
    }

    /** Reads a box of the world, corners included (loads its chunks if they aren't). */
    public static BlockBox capture(ServerLevel level, BlockPos min, BlockPos max, boolean trimmed) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return of(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1, trimmed,
                (x, y, z) -> look(level, pos.set(min.getX() + x, min.getY() + y, min.getZ() + z)));
    }

    /**
     * What the block at a position looks like. The parts of a formed machine are all one block (the machine shell)
     * wearing a disguise kept in its block entity: the picture shows the disguise, or nothing for a hidden part.
     */
    private static BlockState look(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChamberShellBlock)) return state;
        if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell) {
            return shell.isHidden() ? AIR : shell.getDisguise();
        }
        return state;
    }

    /** The block at a position, air outside the box. */
    public BlockState get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return AIR;
        return cells[index(x, y, z)];
    }

    /** Changes whenever a block of the box does (block states are single objects, so this holds for a server run). */
    public int contentHash() {
        return 31 * (31 * (31 * (31 * sizeX + sizeY) + sizeZ) + (trimmed ? 1 : 0)) + Arrays.hashCode(cells);
    }

    // Layer by layer, so floors, ceilings and wall rows make long runs of one block
    private int index(int x, int y, int z) {
        return (y * sizeX + x) * sizeZ + z;
    }

    // ---- Network form: sizes, a palette of block states, then runs of (palette index, length) ----

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(sizeX);
        buf.writeVarInt(sizeY);
        buf.writeVarInt(sizeZ);
        buf.writeBoolean(trimmed);
        Map<BlockState, Integer> indices = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        for (BlockState state : cells) {
            if (indices.putIfAbsent(state, palette.size()) == null) palette.add(state);
        }
        buf.writeVarInt(palette.size());
        for (BlockState state : palette) buf.writeVarInt(Block.getId(state));
        int start = 0;
        while (start < cells.length) {
            int end = start + 1;
            while (end < cells.length && cells[end] == cells[start]) end++;
            buf.writeVarInt(indices.get(cells[start]));
            buf.writeVarInt(end - start);
            start = end;
        }
    }

    public static BlockBox read(FriendlyByteBuf buf) {
        int sizeX = buf.readVarInt(), sizeY = buf.readVarInt(), sizeZ = buf.readVarInt();
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || sizeX > MAX_SIDE || sizeY > MAX_SIDE || sizeZ > MAX_SIDE) {
            throw new IllegalArgumentException("Bad block box size " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        boolean trimmed = buf.readBoolean();
        BlockState[] palette = new BlockState[buf.readVarInt()];
        for (int i = 0; i < palette.length; i++) palette[i] = Block.stateById(buf.readVarInt());
        BlockState[] cells = new BlockState[sizeX * sizeY * sizeZ];
        int filled = 0;
        while (filled < cells.length) {
            BlockState state = palette[buf.readVarInt()];
            int length = buf.readVarInt();
            if (length < 1 || filled + length > cells.length) throw new IllegalArgumentException("Bad block box run");
            for (int i = 0; i < length; i++) cells[filled++] = state;
        }
        return new BlockBox(sizeX, sizeY, sizeZ, trimmed, cells);
    }
}
