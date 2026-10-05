package com.lealex.alchyx.machine;

import net.minecraft.resources.Identifier;

/**
 * What a script-defined machine core has (set by its KubeJS builder at startup):
 * its structure pattern, its recipe type, item slots, fluid tanks and Forge Energy buffer.
 *
 * @param pattern        multiblock pattern id ({@code data/<ns>/multiblock/<name>.json})
 * @param recipeType     the "machine" value its recipes use
 * @param itemInputs     input slots (0-9)
 * @param itemOutputs    output slots (0-9)
 * @param fluidInputs    input tanks (0-2)
 * @param fluidOutputs   output tanks (0-2)
 * @param tankCapacity   mB per tank
 * @param energyCapacity FE buffer (0 = the machine needs no energy)
 * @param energyInput    max FE accepted per tick from cables
 */
public record MachineSpec(Identifier pattern, Identifier recipeType, int itemInputs, int itemOutputs,
                          int fluidInputs, int fluidOutputs, int tankCapacity, int energyCapacity, int energyInput) {

    public MachineSpec {
        itemInputs = Math.clamp(itemInputs, 0, 9);
        itemOutputs = Math.clamp(itemOutputs, 0, 9);
        fluidInputs = Math.clamp(fluidInputs, 0, 2);
        fluidOutputs = Math.clamp(fluidOutputs, 0, 2);
        tankCapacity = Math.max(1, tankCapacity);
        energyCapacity = Math.max(0, energyCapacity);
        energyInput = Math.max(0, energyInput);
    }

    public int itemSlots() {
        return itemInputs + itemOutputs;
    }

    public int tanks() {
        return fluidInputs + fluidOutputs;
    }

    public boolean usesEnergy() {
        return energyCapacity > 0;
    }
}
