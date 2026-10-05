package com.lealex.alchyx.machine;

import com.lealex.alchyx.registry.AlchyXRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The generic GUI of a script-defined machine: its item slots laid out by {@link MachineLayout}, the player
 * inventory, and synced progress/energy. Tanks are read from the core block entity (synced with it).
 */
public class MachineMenu extends AbstractContainerMenu implements com.lealex.alchyx.menu.CoreMenu {
    private final MachineSpec spec;
    private final MachineLayout layout;
    private final ItemStacksResourceHandler items;
    private final ContainerData data;
    private final @Nullable ScriptedCoreBlockEntity core; // client: the client's copy of the block entity (tanks)
    private final boolean server;

    /** Client side: the core's position comes with the menu. */
    public MachineMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory,
                playerInventory.player.level().getBlockEntity(extraData.readBlockPos()) instanceof ScriptedCoreBlockEntity be ? be : null,
                null, new SimpleContainerData(ScriptedCoreBlockEntity.DATA_COUNT));
    }

    /** Server side. */
    public MachineMenu(int containerId, Inventory playerInventory, @Nullable ScriptedCoreBlockEntity core,
                       @Nullable ItemStacksResourceHandler items, ContainerData data) {
        super(AlchyXRegistries.MACHINE_MENU.get(), containerId);
        this.core = core;
        this.server = items != null;
        this.spec = core != null ? core.getSpec()
                : new MachineSpec(net.minecraft.resources.Identifier.withDefaultNamespace("air"),
                net.minecraft.resources.Identifier.withDefaultNamespace("air"), 0, 0, 0, 0, 1, 0, 0);
        this.layout = new MachineLayout(spec);
        this.items = items != null ? items : ScriptedCoreBlockEntity.createItems(spec, () -> {});
        this.data = data;

        for (int i = 0; i < spec.itemSlots(); i++) {
            addSlot(new ResourceHandlerSlot(this.items, this.items::set, i, layout.slotX[i], layout.slotY[i]));
        }
        addStandardInventorySlots(playerInventory, layout.inventoryX + 1, 84);
        addDataSlots(data);
    }

    public MachineSpec spec() {
        return spec;
    }

    public MachineLayout layout() {
        return layout;
    }

    public @Nullable ScriptedCoreBlockEntity core() {
        return core;
    }

    @Override
    public net.minecraft.core.@Nullable BlockPos getCorePos() {
        return core != null ? core.getBlockPos() : null;
    }

    public float progress() {
        int total = data.get(1);
        return total <= 0 ? 0 : Math.min(1.0F, data.get(0) / (float) total);
    }

    public long energy() {
        return (data.get(2) & 0xFFFFL) | ((data.get(3) & 0xFFFFL) << 16);
    }

    public long energyCapacity() {
        return (data.get(4) & 0xFFFFL) | ((data.get(5) & 0xFFFFL) << 16);
    }

    @Override
    public boolean stillValid(Player player) {
        return !server || core == null || core.stillValid(player);
    }

    /** Shift-click: machine slots go to the inventory; inventory items go into the first input that takes them. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int machineSlots = spec.itemSlots();
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, machineSlots + 36, true)) return ItemStack.EMPTY;
        } else {
            int moved = 0;
            try (Transaction tx = Transaction.openRoot()) {
                for (int i = 0; i < spec.itemInputs() && moved < stack.getCount(); i++) {
                    moved += items.insert(i, ItemResource.of(stack), stack.getCount() - moved, tx);
                }
                if (moved == 0) return ItemStack.EMPTY;
                tx.commit();
            }
            stack.shrink(moved);
        }
        slot.setByPlayer(stack.isEmpty() ? ItemStack.EMPTY : stack);
        return original;
    }
}
