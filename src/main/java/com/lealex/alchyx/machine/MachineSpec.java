package com.lealex.alchyx.machine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;
import java.util.Map;

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
 * @param slotFilters    input slot -> what it takes: item ids and {@code #tag}s (a slot not listed takes anything)
 */
public record MachineSpec(Identifier pattern, Identifier recipeType, int itemInputs, int itemOutputs,
                          int fluidInputs, int fluidOutputs, int tankCapacity, int energyCapacity, int energyInput,
                          Map<Integer, List<String>> slotFilters) {

    public MachineSpec(Identifier pattern, Identifier recipeType, int itemInputs, int itemOutputs,
                       int fluidInputs, int fluidOutputs, int tankCapacity, int energyCapacity, int energyInput) {
        this(pattern, recipeType, itemInputs, itemOutputs, fluidInputs, fluidOutputs, tankCapacity, energyCapacity, energyInput, Map.of());
    }

    public MachineSpec {
        itemInputs = Math.clamp(itemInputs, 0, 9);
        itemOutputs = Math.clamp(itemOutputs, 0, 9);
        fluidInputs = Math.clamp(fluidInputs, 0, 2);
        fluidOutputs = Math.clamp(fluidOutputs, 0, 2);
        tankCapacity = Math.max(1, tankCapacity);
        energyCapacity = Math.max(0, energyCapacity);
        energyInput = Math.max(0, energyInput);
        slotFilters = slotFilters == null ? Map.of() : Map.copyOf(slotFilters);
    }

    /** Whether this input slot takes this item (its filter, if it has one). */
    public boolean accepts(int slot, Item item) {
        List<String> filter = slotFilters.get(slot);
        if (filter == null || filter.isEmpty()) return true;
        for (String entry : filter) {
            if (entry.startsWith("#")) {
                Identifier tag = Identifier.tryParse(entry.substring(1));
                if (tag != null && item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag))) return true;
            } else {
                Identifier id = Identifier.tryParse(entry);
                if (id != null && id.equals(BuiltInRegistries.ITEM.getKey(item))) return true;
            }
        }
        return false;
    }

    public boolean accepts(int slot, ItemResource resource) {
        return accepts(slot, resource.getItem());
    }

    public boolean accepts(int slot, ItemStack stack) {
        return accepts(slot, stack.getItem());
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
