package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.AlchyXApi;
import com.lealex.alchyx.registry.AlchyXRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Blocks that only exist inside a formed machine. */
public final class ShellParts {

    private ShellParts() {}

    /** The block every converted position becomes (disguised as its result block). */
    public static boolean isShell(BlockState state) {
        return state.is(AlchyXRegistries.CHAMBER_SHELL.get());
    }

    /** Shells, plus legacy part blocks registered by mods ({@link AlchyXApi#registerLegacyPart}). */
    public static boolean isPart(BlockState state) {
        return isShell(state) || AlchyXApi.isLegacyPart(state);
    }
}
