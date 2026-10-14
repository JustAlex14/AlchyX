package com.lealex.alchyx.menu;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.machine.KindChoice;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The menu of a machine that offers kinds to turn on or off ({@link KindChoice}). It holds no slots: only the
 * buttons its screen sends through clickMenuButton. A kind's number (its place in offered()) turns it off or on;
 * DIAL + a step turns the machine's dial; any other number goes to {@link KindChoice#button}. The screen reads the
 * kinds from the machine's block entity, which the server keeps in step.
 *
 * A mod registers its OWN menu type for it (so it can give it its own screen), and hands it in:
 * {@code IMenuTypeExtension.create((id, inventory, data) -> new ChoiceMenu(MY_TYPE.get(), id, inventory, data))};
 * the data is the block entity's position.
 */
public class ChoiceMenu extends AbstractContainerMenu {
    /** The dial's buttons: DIAL + the place of a step in DIAL_STEPS. */
    public static final int DIAL = 10000;
    public static final int[] DIAL_STEPS = {-8, -1, 1, 8};

    private final @Nullable KindChoice choice; // on the client: the client's copy (null if it isn't there)
    private final boolean server;

    /** Client side. */
    public ChoiceMenu(MenuType<?> type, int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(type, containerId, playerInventory.player.level().getBlockEntity(extraData.readBlockPos()) instanceof KindChoice found ? found : null, false);
    }

    /** Server side. */
    public ChoiceMenu(MenuType<?> type, int containerId, Inventory playerInventory, KindChoice choice) {
        this(type, containerId, choice, true);
    }

    private ChoiceMenu(MenuType<?> type, int containerId, @Nullable KindChoice choice, boolean server) {
        super(type, containerId);
        this.choice = choice;
        this.server = server;
    }

    public @Nullable KindChoice choice() {
        return choice;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!server || choice == null) return false;
        if (buttonId >= DIAL && buttonId < DIAL + DIAL_STEPS.length) choice.turnDial(DIAL_STEPS[buttonId - DIAL]);
        else if (buttonId >= 0 && buttonId < choice.offered().size()) choice.toggle(buttonId);
        else choice.button(player, buttonId, getCarried());
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return !server || (choice instanceof MultiblockCoreBlockEntity core && !core.isRemoved() && core.withinReach(player));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
