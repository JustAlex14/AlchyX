package com.lealex.alchyx.compat.kubejs;

import com.lealex.alchyx.machine.MachineSpec;
import com.lealex.alchyx.machine.ScriptedCoreBlock;
import dev.latvian.mods.kubejs.block.custom.CardinalBlockBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * {@code event.create('mypack:crusher_core', 'alchyx:machine_core')} in a startup script: a machine core facing
 * the player who places it (front texture "<name>_front", like KubeJS's cardinal blocks). Every setting has a
 * default, so only {@code pattern} is really needed.
 * <pre>
 * StartupEvents.registry('block', event => {
 *   event.create('mypack:crusher_core', 'alchyx:machine_core')
 *     .displayName('Crusher Core')
 *     .pattern('mypack:crusher')        // data/mypack/multiblock/crusher.json
 *     .itemInputs(1).itemOutputs(2)
 *     .fluidInputs(1).tankCapacity(4000)
 *     .energy(100000, 1000)             // FE buffer, max FE/t from cables
 * })
 * </pre>
 */
public class MachineCoreBlockBuilder extends CardinalBlockBuilder {
    private @Nullable Identifier pattern;
    private @Nullable Identifier recipeType;
    private int itemInputs = 1, itemOutputs = 1, fluidInputs = 0, fluidOutputs = 0;
    private int tankCapacity = 8000, energyCapacity = 0, energyInput = 0;

    public MachineCoreBlockBuilder(Identifier id) {
        super(id);
    }

    /** The multiblock pattern file (default: same id as the block). */
    public MachineCoreBlockBuilder pattern(Identifier pattern) {
        this.pattern = pattern;
        return this;
    }

    /** The "machine" value its recipes use (default: the block id). Several cores can share recipes. */
    public MachineCoreBlockBuilder recipeType(Identifier recipeType) {
        this.recipeType = recipeType;
        return this;
    }

    public MachineCoreBlockBuilder itemInputs(int count) {
        this.itemInputs = count;
        return this;
    }

    public MachineCoreBlockBuilder itemOutputs(int count) {
        this.itemOutputs = count;
        return this;
    }

    public MachineCoreBlockBuilder fluidInputs(int count) {
        this.fluidInputs = count;
        return this;
    }

    public MachineCoreBlockBuilder fluidOutputs(int count) {
        this.fluidOutputs = count;
        return this;
    }

    /** mB per tank (default 8000). */
    public MachineCoreBlockBuilder tankCapacity(int mB) {
        this.tankCapacity = mB;
        return this;
    }

    /** A Forge Energy buffer; cables may insert up to a tenth of it per tick. */
    public MachineCoreBlockBuilder energy(int capacity) {
        return energy(capacity, Math.max(1, capacity / 10));
    }

    /** A Forge Energy buffer and the most FE cables may insert per tick. */
    public MachineCoreBlockBuilder energy(int capacity, int maxInputPerTick) {
        this.energyCapacity = capacity;
        this.energyInput = maxInputPerTick;
        return this;
    }

    @Override
    public Block createObject() {
        MachineSpec spec = new MachineSpec(pattern != null ? pattern : id, recipeType != null ? recipeType : id,
                itemInputs, itemOutputs, fluidInputs, fluidOutputs, tankCapacity, energyCapacity, energyInput);
        return new ScriptedCoreBlock(createProperties(), spec);
    }
}
