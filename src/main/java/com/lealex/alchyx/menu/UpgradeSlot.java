package com.lealex.alchyx.menu;

import com.lealex.alchyx.upgrade.UpgradeType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

import java.util.List;
import java.util.function.Function;

/**
 * A slot of a machine's upgrade tab (a column right of the window, drawn by client/UpgradeTab): one upgrade, of
 * its slot's type only (the handler checks it).
 */
public class UpgradeSlot extends ResourceHandlerSlot {
    /** Where the tab's slots are, relative to the window. */
    public static final int X = 182, FIRST_Y = 12, STEP = 18;

    private final UpgradeType type;

    public UpgradeSlot(ItemStacksResourceHandler handler, int index, UpgradeType type, int x, int y) {
        super(handler, handler::set, index, x, y);
        this.type = type;
    }

    public UpgradeType type() {
        return type;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return 1;
    }

    /**
     * Adds one slot per type through the menu's addSlot (pass {@code this::addSlot}), top to bottom. Add them right
     * after the player inventory and implement {@link UpgradeMenu} with the indices.
     */
    public static void addAll(Function<Slot, Slot> addSlot, ItemStacksResourceHandler upgrades, List<UpgradeType> types) {
        for (int i = 0; i < types.size(); i++) {
            addSlot.apply(new UpgradeSlot(upgrades, i, types.get(i), X, FIRST_Y + i * STEP));
        }
    }
}
