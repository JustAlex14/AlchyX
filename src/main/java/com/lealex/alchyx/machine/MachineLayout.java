package com.lealex.alchyx.machine;

/**
 * Where everything goes in a script machine's GUI, computed from its spec (shared by the menu and the screen).
 * Left to right: energy bar, input tanks, input slot grid (up to 3 columns), progress arrow, output slot grid,
 * output tanks. The GUI widens past 176 pixels when that doesn't fit; the player inventory stays centered.
 */
public final class MachineLayout {
    public static final int TOP = 17, AREA_HEIGHT = 54;          // machine area under the title
    public static final int TANK_W = 16, TANK_H = 52, ENERGY_W = 8;
    public static final int ARROW_W = 24, ARROW_H = 16;
    private static final int GAP = 6, SLOT = 18;

    public final int width;            // GUI width
    public final int energyX;          // -1 if no energy bar
    public final int[] tankX;          // input tanks then output tanks
    public final int[] slotX, slotY;   // item slots (inputs then outputs), slot positions as vanilla Slot x/y
    public final int arrowX, arrowY;
    public final int inventoryX;       // player inventory left edge

    public MachineLayout(MachineSpec spec) {
        int inCols = Math.min(3, Math.max(1, spec.itemInputs())), outCols = Math.min(3, Math.max(1, spec.itemOutputs()));
        int x = 0;
        int energy = -1;
        if (spec.usesEnergy()) {
            energy = x;
            x += ENERGY_W + GAP;
        }
        int[] tanks = new int[spec.tanks()];
        for (int t = 0; t < spec.fluidInputs(); t++) {
            tanks[t] = x;
            x += TANK_W + GAP;
        }
        int inX = x;
        if (spec.itemInputs() > 0) x += inCols * SLOT + GAP;
        int arrow = x;
        x += ARROW_W + GAP;
        int outX = x;
        if (spec.itemOutputs() > 0) x += outCols * SLOT + GAP;
        for (int t = spec.fluidInputs(); t < spec.tanks(); t++) {
            tanks[t] = x;
            x += TANK_W + GAP;
        }
        int contentWidth = x - GAP;
        this.width = Math.max(176, contentWidth + 16);
        int offset = (width - contentWidth) / 2;

        this.energyX = energy < 0 ? -1 : energy + offset;
        for (int t = 0; t < tanks.length; t++) tanks[t] += offset;
        this.tankX = tanks;
        this.arrowX = arrow + offset;
        this.arrowY = TOP + (AREA_HEIGHT - ARROW_H) / 2;

        int slots = spec.itemSlots();
        this.slotX = new int[slots];
        this.slotY = new int[slots];
        place(0, spec.itemInputs(), inCols, inX + offset);
        place(spec.itemInputs(), spec.itemOutputs(), outCols, outX + offset);
        this.inventoryX = (width - 162) / 2;
    }

    private void place(int first, int count, int cols, int left) {
        int rows = (count + cols - 1) / Math.max(1, cols);
        int top = TOP + (AREA_HEIGHT - rows * SLOT) / 2;
        for (int i = 0; i < count; i++) {
            slotX[first + i] = left + (i % cols) * SLOT + 1; // Slot x/y is the item's corner, inside the 18px frame
            slotY[first + i] = top + (i / cols) * SLOT + 1;
        }
    }

    public int tankY() {
        return TOP + (AREA_HEIGHT - TANK_H) / 2;
    }
}
