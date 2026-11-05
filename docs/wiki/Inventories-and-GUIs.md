# Inventories and GUIs

AlchyX does not draw your machine's screen for you (except for KubeJS machines and choice screens). It gives you the pieces that every machine screen of the Alchy mods shares: the link between the menu and the core, the structure preview button, and the upgrade tab.

## Items, fluids and energy

Hold your storage in the block entity with NeoForge's transfer API and return it from the matching method:

```java
private final ItemStacksResourceHandler inventory = new ItemStacksResourceHandler(3) { ... };
private final FluidStacksResourceHandler tanks = new FluidStacksResourceHandler(2, 8_000) { ... };

@Override public ResourceHandler<ItemResource> getItemHandler() { return inventory; }
@Override public ResourceHandler<FluidResource> getFluidHandler() { return tanks; }
@Override public EnergyHandler getEnergyHandler() { return energy; }
```

AlchyX registers the capabilities on its shell block, so every part of a formed machine answers with your handlers. When the machine breaks or forms, the capabilities are invalidated so pipes notice.

If your inventory must refuse some operations from pipes (no extracting from an input), give pipes a wrapper and keep the full handler for your own code and your menu. AlchyMastery's `DestructurationCoreBlockEntity` has an `AutomationHandler` that does this.

With a fluid handler, a bucket or any fluid container right-clicked on the core fills or empties the tanks without any code from you.

## A menu that knows its core

Implement `com.lealex.alchyx.menu.CoreMenu` on your menu. It has one method, `getCorePos()`. The server writes the core's position when it opens the menu (see `openGui` on [Core Block Entity](Core-Block-Entity)), and the client constructor reads it back:

```java
public class KilnMenu extends AbstractContainerMenu implements CoreMenu {
    private @Nullable BlockPos corePos;

    // Client side: the extra data holds the core's position
    public KilnMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, KilnCoreBlockEntity.createInventory(() -> {}), null);
        this.corePos = extraData.readBlockPos();
    }

    // Server side
    public KilnMenu(int containerId, Inventory playerInventory, ItemStacksResourceHandler inventory,
                    @Nullable KilnCoreBlockEntity core) {
        super(ModRegistries.KILN_MENU.get(), containerId);
        this.corePos = core == null ? null : core.getBlockPos();
        this.core = core;
        addSlot(new ResourceHandlerSlot(inventory, inventory::set, KilnCoreBlockEntity.INPUT, 56, 35));
        addSlot(new ResourceHandlerSlot(inventory, inventory::set, KilnCoreBlockEntity.OUTPUT, 116, 35));
        addStandardInventorySlots(playerInventory, 8, 84);
    }

    @Override
    public @Nullable BlockPos getCorePos() {
        return corePos;
    }

    @Override
    public boolean stillValid(Player player) {
        return core == null || core.withinReach(player);
    }
    // quickMoveStack ...
}
```

Register the menu type with `IMenuTypeExtension.create(KilnMenu::new)` and its screen in `RegisterMenuScreensEvent` as usual.

What you get from `CoreMenu`: while the machine is not formed, AlchyX adds a **Show structure / Hide structure** button above the top right corner of your screen, and opening the screen turns the preview on. The preview shows ghost blocks where blocks are missing and red dust on wrong blocks, and switches itself off when the machine forms. You do nothing in your screen class for this.

A machine without a menu (one that does not implement `MenuProvider`) gets the preview on right-click instead, and sneak-right-click hides it.

## The upgrade tab

If your machine takes upgrades (see [Upgrades](Upgrades)), add their slots to the menu right after the player inventory, and implement `UpgradeMenu`:

```java
public class KilnMenu extends AbstractContainerMenu implements CoreMenu, UpgradeMenu {
    private final int upgradeStart, upgradeEnd;

    // in the server constructor, after addStandardInventorySlots:
    upgradeStart = slots.size();
    UpgradeSlot.addAll(this::addSlot,
            core != null ? core.getUpgrades().handler() : UpgradeStorage.createHandler(KILN_UPGRADES, () -> {}),
            KILN_UPGRADES);
    upgradeEnd = slots.size();

    @Override public int upgradeStart() { return upgradeStart; }
    @Override public int upgradeEnd() { return upgradeEnd; }
}
```

In the screen, draw the tab with `UpgradeTab`:

```java
@Override
public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    UpgradeTab.draw(graphics, menu, leftPos, topPos);   // before the window, which covers the tab's left edge
    // ... draw your window
    UpgradeTab.hints(graphics, menu, leftPos, topPos, mouseX, mouseY); // faint emblems in empty slots
}

@Override
protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
    return super.hasClickedOutside(mouseX, mouseY, left, top) && !UpgradeTab.contains(menu, mouseX, mouseY, left, top);
}
```

`UpgradeTab.area(menu, left, top)` gives the tab's rectangle for JEI exclusion zones. The tab holds up to five slots and sits at x 175 right of a standard 176 pixel wide window.

## Window themes

`com.lealex.alchyx.client.WindowTheme` lays out a window from two sheets of pixel art (a panel material and a border band) and a few colours, so one pair of sheets serves screens of any size. It is used by choice screens and by screens laid out in code. Each mod brings its own sheets and colours. See the class comment for the sheet layout (256 x 512).

## Choice screens

For a machine whose screen is a grid of kinds to turn on or off (what a mine digs, what a farm grows), see Choice screens on [Advanced Machines](Advanced-Machines).

## KubeJS machines

Machines made with KubeJS get a generic screen built from their slot, tank and energy counts (`client/machine/MachineScreen`), and a JEI page. See [KubeJS Machines](KubeJS-Machines).
