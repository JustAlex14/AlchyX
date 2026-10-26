package com.lealex.alchyx.registry;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.lealex.alchyx.item.StructureBuilderItem;
import com.lealex.alchyx.machine.MachineMenu;
import com.lealex.alchyx.machine.ScriptedCoreBlock;
import com.lealex.alchyx.machine.ScriptedCoreBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The library's own blocks and items. Mods using AlchyX add the structure builder to their creative tab. */
public final class AlchyXRegistries {
    private AlchyXRegistries() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AlchyX.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AlchyX.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AlchyX.MODID);

    /**
     * What every converted machine position becomes: looks like the block named in the pattern's result grid
     * (its "disguise"), behaves like a machine part (breaking it breaks the machine).
     */
    public static final DeferredBlock<ChamberShellBlock> CHAMBER_SHELL = BLOCKS.registerBlock("chamber_shell",
            ChamberShellBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE)
                    .strength(1.5f, 6.0f)
                    .sound(SoundType.STONE)
                    .dynamicShape()                       // shape comes from the disguise, can't be cached per state
                    // Count as solid for liquids: with a dynamic shape Minecraft can't work that out itself, and
                    // treats the block as not solid, so lava next to a slab shell would flow in and erase it
                    .forceSolidOn()
                    .pushReaction(PushReaction.BLOCK)     // pistons can't pull blocks out of a machine
                    .lightLevel(state -> state.getValue(ChamberShellBlock.LIGHT))
                    .isValidSpawn((state, level, pos, type) -> false)
                    .isRedstoneConductor((state, level, pos) -> state.getValue(ChamberShellBlock.OPAQUE))
                    .isSuffocating((state, level, pos) -> state.getValue(ChamberShellBlock.OPAQUE))
                    .isViewBlocking((state, level, pos) -> state.getValue(ChamberShellBlock.OPAQUE))
                    .noLootTable());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChamberShellBlockEntity>> CHAMBER_SHELL_BE =
            BLOCK_ENTITY_TYPES.register("chamber_shell",
                    () -> new BlockEntityType<>(ChamberShellBlockEntity::new, false, CHAMBER_SHELL.get()));

    /**
     * Shared block entity of every script-defined machine core (KubeJS {@code alchyx:machine_core} blocks). The
     * cores are registered by scripts, so they join this type in {@link #addScriptedCores}.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScriptedCoreBlockEntity>> SCRIPTED_CORE_BE =
            BLOCK_ENTITY_TYPES.register("scripted_core", () -> new BlockEntityType<>(ScriptedCoreBlockEntity::new, java.util.Set.of())); // blocks added by addScriptedCores

    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, AlchyX.MODID);

    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, AlchyX.MODID);
    /**
     * What a machine core's item keeps when the core is broken (MultiblockCoreBlockEntity.keptState: its upgrades,
     * and whatever the machine adds), given back when it is set down again.
     */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>, net.minecraft.core.component.DataComponentType<net.minecraft.world.item.component.CustomData>> KEPT_CORE =
            DATA_COMPONENTS.registerComponentType("kept_core", builder -> builder
                    .persistent(net.minecraft.world.item.component.CustomData.CODEC)
                    .networkSynchronized(net.minecraft.world.item.component.CustomData.STREAM_CODEC));

    public static final DeferredRegister<net.neoforged.neoforge.common.crafting.IngredientType<?>> INGREDIENT_TYPES =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.INGREDIENT_TYPES, AlchyX.MODID);
    /** "alchyx:rifted": a recipe ingredient taking anything a rift enhanced to a level or more (rift/RiftedIngredient). */
    public static final DeferredHolder<net.neoforged.neoforge.common.crafting.IngredientType<?>,
            net.neoforged.neoforge.common.crafting.IngredientType<com.lealex.alchyx.rift.RiftedIngredient>> RIFTED_INGREDIENT =
            INGREDIENT_TYPES.register("rifted", () -> new net.neoforged.neoforge.common.crafting.IngredientType<>(
                    com.lealex.alchyx.rift.RiftedIngredient.CODEC));

    /** The generic GUI of script-defined machines (the client reads the block position to find the core). */
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> MACHINE_MENU =
            MENU_TYPES.register("machine", () -> IMenuTypeExtension.create(MachineMenu::new));

    /** Creative tool: right-click a machine core to place its whole structure. */
    public static final DeferredItem<StructureBuilderItem> STRUCTURE_BUILDER =
            ITEMS.registerItem("structure_builder", StructureBuilderItem::new, p -> p.stacksTo(1));

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AlchyX.MODID);

    /**
     * "AlchyX": the structure builder (and the manual, added by Patchouli). Script-defined machine cores stay in
     * KubeJS's tab (or wherever the pack puts them), so they aren't listed twice.
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.alchyx"))
                    .icon(() -> STRUCTURE_BUILDER.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(STRUCTURE_BUILDER.get()))
                    .build());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        INGREDIENT_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(AlchyXRegistries::addScriptedCores);
    }

    // Every ScriptedCoreBlock (from KubeJS startup scripts) becomes a valid block of SCRIPTED_CORE_BE
    private static void addScriptedCores(BlockEntityTypeAddBlocksEvent event) {
        Block[] cores = BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ScriptedCoreBlock).toArray(Block[]::new);
        if (cores.length > 0) event.modify(SCRIPTED_CORE_BE.get(), cores);
    }
}
