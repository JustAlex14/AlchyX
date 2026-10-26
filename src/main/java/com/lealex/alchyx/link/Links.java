package com.lealex.alchyx.link;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks for links stored as positions (a relay's partner, a machine's source, a store tied to a post...). The rule
 * every mod follows: a linked place is GONE only when its chunk is loaded and what stands there is not what the link
 * wants (broken, replaced, removed by a command, erased with a room). A place whose chunk is not loaded is never judged
 * gone, so leaving an area never cuts links. Use it to hide a link's line, to skip it, or to drop it for good.
 */
public final class Links {
    private Links() {}

    /** The place is loaded and its block is not what the link wants. Either side. */
    public static boolean gone(Level level, BlockPos pos, Predicate<BlockState> wanted) {
        return level.isLoaded(pos) && !wanted.test(level.getBlockState(pos));
    }

    /** The place is loaded and its block entity is not what the link wants (null when there is none). Either side. */
    public static boolean goneEntity(Level level, BlockPos pos, Predicate<BlockEntity> wanted) {
        return level.isLoaded(pos) && !wanted.test(level.getBlockEntity(pos));
    }

    /** Server, for a link that may lead into another dimension: as goneEntity, and a dimension that no longer exists is gone. */
    public static boolean goneEntity(MinecraftServer server, GlobalPos pos, Predicate<BlockEntity> wanted) {
        ServerLevel there = server.getLevel(pos.dimension());
        return there == null || goneEntity(there, pos.pos(), wanted);
    }

    /**
     * Every block entity in the loaded chunks within {@code reach} blocks of pos (by chunk, so a little more): for a
     * cleanup after something was broken, to find who pointed at it. The lists are copied, so the action may change
     * block entities.
     */
    public static void forEachNear(Level level, BlockPos pos, int reach, Consumer<BlockEntity> action) {
        int x0 = (pos.getX() - reach) >> 4, x1 = (pos.getX() + reach) >> 4, z0 = (pos.getZ() - reach) >> 4, z1 = (pos.getZ() + reach) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockEntity entity : List.copyOf(level.getChunk(cx, cz).getBlockEntities().values())) action.accept(entity);
            }
        }
    }
}
