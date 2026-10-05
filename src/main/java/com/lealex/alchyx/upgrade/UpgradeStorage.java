package com.lealex.alchyx.upgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * A machine's upgrade slots: one per type it takes (slot i = types.get(i)), one upgrade each. Made by
 * MultiblockCoreBlockEntity.createUpgrades, which saves, syncs, drops and shows them. Not reachable by pipes.
 */
public class UpgradeStorage {
    /** The animation level published with the installed tiers' sum ("min_level": {"corruption": n}). */
    public static final String CORRUPTION = "corruption";

    private final List<UpgradeType> types;
    private final ItemStacksResourceHandler handler;

    public UpgradeStorage(List<UpgradeType> types, Runnable onChanged) {
        this.types = List.copyOf(types);
        this.handler = createHandler(this.types, onChanged);
    }

    /** The slots' handler; menus build a client copy with the same types. */
    public static ItemStacksResourceHandler createHandler(List<UpgradeType> types, Runnable onChanged) {
        return new ItemStacksResourceHandler(types.size()) {
            @Override
            public boolean isValid(int index, ItemResource resource) {
                return resource.getItem() instanceof UpgradeItem upgrade && index < types.size() && upgrade.type() == types.get(index);
            }

            @Override
            protected void onContentsChanged(int index, ItemStack previousContents) {
                onChanged.run();
            }
        };
    }

    public List<UpgradeType> types() {
        return types;
    }

    public ItemStacksResourceHandler handler() {
        return handler;
    }

    /** The installed upgrade of this type (empty if none, or if the machine doesn't take it). */
    public ItemStack stack(UpgradeType type) {
        int slot = types.indexOf(type);
        if (slot < 0) return ItemStack.EMPTY;
        ItemResource resource = handler.getResource(slot);
        return resource.isEmpty() ? ItemStack.EMPTY : resource.toStack(1);
    }

    /** The installed tier of this type (0 = none). */
    public int tier(UpgradeType type) {
        return UpgradeItem.tierOf(stack(type), type);
    }

    /** How corrupted the machine is: the installed tiers' sum. */
    public int corruption() {
        int sum = 0;
        for (UpgradeType type : types) sum += tier(type);
        return sum;
    }

    /** The corruption with every slot at its top tier. */
    public int maxCorruption() {
        int sum = 0;
        for (UpgradeType type : types) sum += type.maxTier();
        return sum;
    }

    /** The installed upgrades' colors (the veins pulse between them), in slot order. */
    public List<Integer> installedColors() {
        List<Integer> colors = new ArrayList<>();
        for (UpgradeType type : types) if (tier(type) > 0) colors.add(type.color());
        return colors;
    }

    public void dropAll(Level level, BlockPos pos) {
        for (ItemStack stack : handler.copyToList()) {
            if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        }
    }

    public void save(ValueOutput output) {
        handler.serialize(output.child("upgrades"));
    }

    public void load(ValueInput input) {
        handler.deserialize(input.childOrEmpty("upgrades"));
    }
}
