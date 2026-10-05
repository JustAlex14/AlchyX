package com.lealex.alchyx.machine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;

import java.util.List;

/**
 * A recipe for a script-defined machine: {@code data/<namespace>/machine_recipe/<name>.json}.
 * <pre>
 * {
 *   "machine": "mypack:crusher_core",                       (the machine's recipe type: by default its core block id)
 *   "inputs": [{"item": "#c:ores/iron", "count": 1}],        (item id, #tag or list; count default 1)
 *   "fluid_inputs": [{"fluid": "minecraft:water", "amount": 250}],
 *   "outputs": [{"item": "minecraft:raw_iron", "count": 2}, {"item": "minecraft:gravel", "chance": 0.25}],
 *   "fluid_outputs": [{"fluid": "minecraft:lava", "amount": 100}],
 *   "time": 100,                                             (ticks, default 100)
 *   "energy": 2000                                           (FE for the whole operation, default 0)
 * }
 * </pre>
 * Inputs are matched in any slot (each input needs its own slot); outputs must all fit before the machine starts.
 */
public record MachineRecipe(Identifier machine, List<ItemInput> inputs, List<FluidAmount> fluidInputs,
                            List<ItemOutput> outputs, List<FluidAmount> fluidOutputs, int time, int energy) {

    public record ItemInput(Ingredient ingredient, int count) {
        public static final Codec<ItemInput> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Ingredient.CODEC.fieldOf("item").forGetter(ItemInput::ingredient),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(ItemInput::count)
        ).apply(instance, ItemInput::new));

        public boolean test(ItemStack stack) {
            return !stack.isEmpty() && stack.getCount() >= count && ingredient.test(stack);
        }
    }

    public record ItemOutput(Item item, int count, float chance) {
        public static final Codec<ItemOutput> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemOutput::item),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(ItemOutput::count),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("chance", 1.0F).forGetter(ItemOutput::chance)
        ).apply(instance, ItemOutput::new));

        public ItemStack toStack() {
            return new ItemStack(item, count);
        }
    }

    public record FluidAmount(Fluid fluid, int amount) {
        public static final Codec<FluidAmount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.FLUID.byNameCodec().fieldOf("fluid").forGetter(FluidAmount::fluid),
                ExtraCodecs.POSITIVE_INT.fieldOf("amount").forGetter(FluidAmount::amount)
        ).apply(instance, FluidAmount::new));
    }

    public static final Codec<MachineRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("machine").forGetter(MachineRecipe::machine),
            ItemInput.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(MachineRecipe::inputs),
            FluidAmount.CODEC.listOf().optionalFieldOf("fluid_inputs", List.of()).forGetter(MachineRecipe::fluidInputs),
            ItemOutput.CODEC.listOf().optionalFieldOf("outputs", List.of()).forGetter(MachineRecipe::outputs),
            FluidAmount.CODEC.listOf().optionalFieldOf("fluid_outputs", List.of()).forGetter(MachineRecipe::fluidOutputs),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("time", 100).forGetter(MachineRecipe::time),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("energy", 0).forGetter(MachineRecipe::energy)
    ).apply(instance, MachineRecipe::new));
}
