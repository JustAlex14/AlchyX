package com.lealex.alchyx.machine;

import net.minecraft.resources.Identifier;

/**
 * Script hooks of script-defined machines. The defaults do nothing; the KubeJS plugin installs an implementation
 * that posts {@code AlchyXEvents} (so AlchyX itself never needs KubeJS).
 */
public interface MachineHooks {
    MachineHooks NONE = new MachineHooks() {};

    /** The structure finished forming. */
    default void formed(ScriptedCoreBlockEntity machine) {}

    /** The structure broke. */
    default void broken(ScriptedCoreBlockEntity machine) {}

    /** An operation is about to start: false cancels it (the machine tries again later). */
    default boolean starting(ScriptedCoreBlockEntity machine, Identifier recipe) {
        return true;
    }

    /** An operation finished (outputs already inserted). */
    default void finished(ScriptedCoreBlockEntity machine, Identifier recipe) {}

    /** Every server tick while formed (only implement if someone listens: it runs a lot). */
    default void tick(ScriptedCoreBlockEntity machine) {}

    // ---- The installed implementation ----

    final class Holder {
        private static volatile MachineHooks hooks = NONE;

        private Holder() {}
    }

    static MachineHooks get() {
        return Holder.hooks;
    }

    static void set(MachineHooks hooks) {
        Holder.hooks = hooks;
    }
}
