package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.animation.MiniStructure;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Something that shows mini structures without being a machine in the world (the Machine Studio's preview): what
 * the minis are timed on. A machine core in the world needs none of this, the mini reads the core itself.
 */
public interface MiniHost {
    boolean miniWorking();

    /** 0 to 1 through the operation under way, at this time (ticks, with the partial tick). */
    float miniProgress(float time);

    /** Changes when an operation starts. */
    long miniOperationStart();

    /** Changes when an operation finishes. */
    int miniOperations();

    /** How long one operation lasts, in ticks. */
    int miniOperationTicks();

    /** The minis this machine keeps of its own ("#0", "#1"...). */
    List<MiniStructure.Own> miniOwn();

    default ItemStack miniDisplay(String name) {
        return ItemStack.EMPTY;
    }
}
