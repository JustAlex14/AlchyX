package com.lealex.alchyx;

import com.lealex.alchyx.multiblock.CoreTracker;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.registry.AlchyXRegistries;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

/**
 * AlchyX: a library for data-driven multiblock machines.
 *
 * <ul>
 *   <li>Structures are datapack files ({@code data/<namespace>/multiblock/<name>.json}, see
 *       {@link com.lealex.alchyx.multiblock.MultiblockPattern}): what to build, what each block turns into.</li>
 *   <li>A machine is a core block whose block entity extends {@link MultiblockCoreBlockEntity} and names its
 *       pattern ({@code patternId()}); detection, the forming wave, disguised shells, rotation and break handling
 *       come for free.</li>
 *   <li>{@link AlchyXApi} has the few hooks a mod may need (legacy part blocks, piston looks).</li>
 * </ul>
 */
@Mod(AlchyX.MODID)
public class AlchyX {
    public static final String MODID = "alchyx";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AlchyX(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        AlchyXRegistries.register(modEventBus);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, AlchyXClientConfig.SPEC);
        modEventBus.addListener(AlchyX::registerCapabilities);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    // Every shell of a formed machine gives pipes and hoppers its core's items and fluids
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.Item.BLOCK,
                (level, pos, state, blockEntity, side) -> {
                    MultiblockCoreBlockEntity core = CoreTracker.formedCoreAt(level, pos);
                    return core != null ? core.getItemHandler() : null;
                },
                AlchyXRegistries.CHAMBER_SHELL.get());
        event.registerBlock(Capabilities.Fluid.BLOCK,
                (level, pos, state, blockEntity, side) -> {
                    MultiblockCoreBlockEntity core = CoreTracker.formedCoreAt(level, pos);
                    return core != null ? core.getFluidHandler() : null;
                },
                AlchyXRegistries.CHAMBER_SHELL.get());
        event.registerBlock(Capabilities.Energy.BLOCK,
                (level, pos, state, blockEntity, side) -> {
                    MultiblockCoreBlockEntity core = CoreTracker.formedCoreAt(level, pos);
                    return core != null ? core.getEnergyHandler() : null;
                },
                AlchyXRegistries.CHAMBER_SHELL.get());
        // Script-defined machine cores (KubeJS): their own items, fluids and energy
        event.registerBlockEntity(Capabilities.Item.BLOCK, AlchyXRegistries.SCRIPTED_CORE_BE.get(), (core, side) -> core.getItemHandler());
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, AlchyXRegistries.SCRIPTED_CORE_BE.get(), (core, side) -> core.getFluidHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, AlchyXRegistries.SCRIPTED_CORE_BE.get(), (core, side) -> core.getEnergyHandler());
    }
}
