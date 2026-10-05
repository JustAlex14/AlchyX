package com.lealex.alchyx.compat.kubejs;

import com.lealex.alchyx.machine.ScriptedCoreBlockEntity;
import dev.latvian.mods.kubejs.level.KubeLevelEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/** event.level, event.pos, event.machine (items, tanks, energy, progress), event.block (the core block id). */
public class MachineKubeEvent implements KubeLevelEvent {
    private final ScriptedCoreBlockEntity machine;

    public MachineKubeEvent(ScriptedCoreBlockEntity machine) {
        this.machine = machine;
    }

    @Override
    public Level getLevel() {
        return machine.getLevel();
    }

    public BlockPos getPos() {
        return machine.getBlockPos();
    }

    public ScriptedCoreBlockEntity getMachine() {
        return machine;
    }

    public Identifier getBlock() {
        return BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock());
    }
}
