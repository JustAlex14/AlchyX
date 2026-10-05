package com.lealex.alchyx.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventTargetType;
import dev.latvian.mods.kubejs.event.TargetedEventHandler;
import net.minecraft.resources.Identifier;

/**
 * Server script events of script-defined machines. The optional first argument is the core block id:
 * {@code AlchyXEvents.operationFinished('mypack:crusher_core', event => { ... })}.
 */
public interface AlchyXEvents {
    EventGroup GROUP = EventGroup.of("AlchyXEvents");

    TargetedEventHandler<Identifier> FORMED = GROUP.server("formed", () -> MachineKubeEvent.class)
            .supportsTarget(EventTargetType.ID);
    TargetedEventHandler<Identifier> BROKEN = GROUP.server("broken", () -> MachineKubeEvent.class)
            .supportsTarget(EventTargetType.ID);
    /** event.cancel() keeps the operation from starting (the machine retries when its inputs change). */
    TargetedEventHandler<Identifier> OPERATION_STARTING = GROUP.server("operationStarting", () -> MachineOperationKubeEvent.class)
            .hasResult().supportsTarget(EventTargetType.ID);
    TargetedEventHandler<Identifier> OPERATION_FINISHED = GROUP.server("operationFinished", () -> MachineOperationKubeEvent.class)
            .supportsTarget(EventTargetType.ID);
    /** Every tick while formed: the core block id is required (it runs a lot). */
    TargetedEventHandler<Identifier> TICK = GROUP.server("tick", () -> MachineKubeEvent.class)
            .requiredTarget(EventTargetType.ID);
}
