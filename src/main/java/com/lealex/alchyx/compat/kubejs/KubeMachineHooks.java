package com.lealex.alchyx.compat.kubejs;

import com.lealex.alchyx.machine.MachineHooks;
import com.lealex.alchyx.machine.ScriptedCoreBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Posts AlchyXEvents for script-defined machines (only when a script listens). */
final class KubeMachineHooks implements MachineHooks {

    private static Identifier id(ScriptedCoreBlockEntity machine) {
        return BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock());
    }

    @Override
    public void formed(ScriptedCoreBlockEntity machine) {
        Identifier id = id(machine);
        if (AlchyXEvents.FORMED.hasListeners(id)) AlchyXEvents.FORMED.post(new MachineKubeEvent(machine), id);
    }

    @Override
    public void broken(ScriptedCoreBlockEntity machine) {
        Identifier id = id(machine);
        if (AlchyXEvents.BROKEN.hasListeners(id)) AlchyXEvents.BROKEN.post(new MachineKubeEvent(machine), id);
    }

    @Override
    public boolean starting(ScriptedCoreBlockEntity machine, Identifier recipe) {
        Identifier id = id(machine);
        if (!AlchyXEvents.OPERATION_STARTING.hasListeners(id)) return true;
        return !AlchyXEvents.OPERATION_STARTING.post(new MachineOperationKubeEvent(machine, recipe), id).interruptFalse();
    }

    @Override
    public void finished(ScriptedCoreBlockEntity machine, Identifier recipe) {
        Identifier id = id(machine);
        if (AlchyXEvents.OPERATION_FINISHED.hasListeners(id)) {
            AlchyXEvents.OPERATION_FINISHED.post(new MachineOperationKubeEvent(machine, recipe), id);
        }
    }

    @Override
    public void tick(ScriptedCoreBlockEntity machine) {
        Identifier id = id(machine);
        if (AlchyXEvents.TICK.hasListeners(id)) AlchyXEvents.TICK.post(new MachineKubeEvent(machine), id);
    }
}
