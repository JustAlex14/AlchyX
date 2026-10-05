package com.lealex.alchyx.compat.kubejs;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.machine.MachineHooks;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.registry.BuilderTypeRegistry;
import net.minecraft.core.registries.Registries;

/**
 * KubeJS integration (listed in {@code kubejs.plugins.txt}, so it only loads when KubeJS is installed):
 * <ul>
 *   <li>the {@code alchyx:machine_core} block type for {@code StartupEvents.registry('block', ...)};</li>
 *   <li>the {@code AlchyXEvents} server events (formed, broken, operationStarting, operationFinished, tick).</li>
 * </ul>
 * See KUBEJS.md in the AlchyX repository for a complete example machine.
 */
public class AlchyXKubeJSPlugin implements KubeJSPlugin {

    @Override
    public void init() {
        MachineHooks.set(new KubeMachineHooks());
    }

    @Override
    public void registerBuilderTypes(BuilderTypeRegistry registry) {
        registry.of(Registries.BLOCK, reg -> reg.add(AlchyX.id("machine_core"), MachineCoreBlockBuilder.class, MachineCoreBlockBuilder::new));
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(AlchyXEvents.GROUP);
    }
}
