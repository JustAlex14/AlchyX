package com.lealex.alchyx.multiblock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * How a block looks in structure previews (the Patchouli book page and the ghost blocks of the core GUI's preview).
 * Those previews draw block models only: a block drawn entirely by its block entity renderer (a conduit-like core)
 * would be invisible there. Mods register a state of it that has a real model, e.g. a "preview" property.
 */
public final class PreviewLooks {
    private static final Map<Block, UnaryOperator<BlockState>> LOOKS = new ConcurrentHashMap<>();

    private PreviewLooks() {}

    /** Call during common setup: {@code PreviewLooks.register(MY_CORE.get(), state -> state.setValue(PREVIEW, true))}. */
    public static void register(Block block, UnaryOperator<BlockState> look) {
        LOOKS.put(block, look);
    }

    /** The state to show in previews in place of this one. */
    public static BlockState apply(BlockState state) {
        UnaryOperator<BlockState> look = LOOKS.get(state.getBlock());
        return look == null ? state : look.apply(state);
    }
}
