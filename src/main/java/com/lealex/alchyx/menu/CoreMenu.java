package com.lealex.alchyx.menu;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * A machine's menu that knows its core's position on the client too (the core writes it into the menu's extra data,
 * the client constructor reads it back). AlchyX then adds the structure preview button to its screen while the
 * machine isn't formed.
 */
public interface CoreMenu {
    @Nullable BlockPos getCorePos();
}
