package com.lealex.alchyx.block;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.item.MachineTool;
import com.lealex.alchyx.multiblock.CoreTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import org.jspecify.annotations.Nullable;

/**
 * Base for every machine's central block: ticks its {@link MultiblockCoreBlockEntity} on the server,
 * opens it on right-click, and lets the structure builder work on it.
 */
public abstract class MultiblockCoreBlock extends Block implements EntityBlock {

    protected MultiblockCoreBlock(Properties properties) {
        super(properties);
    }

    // Holding a machine tool (structure builder, wormhole attuner): let the item act (PASS = go on to the item's use)
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof MachineTool) return InteractionResult.PASS;
        // A bucket (or another fluid container) on a machine with tanks: fill or empty it
        if (owner(level, pos) instanceof MultiblockCoreBlockEntity core) {
            ResourceHandler<FluidResource> fluids = core.getFluidHandler();
            if (fluids != null && ItemAccess.forPlayerInteraction(player, hand).getCapability(Capabilities.Fluid.ITEM) != null) {
                // The client can't see the tanks: it just says "handled" so it doesn't pour the bucket on the ground
                if (level.isClientSide() || FluidUtil.interactWithFluidHandler(player, hand, pos, fluids, null)) {
                    return InteractionResult.SUCCESS;
                }
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    // Right-click (empty hand, or an item the block doesn't react to): the machine's GUI or status
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && owner(level, pos) instanceof MultiblockCoreBlockEntity core) {
            core.openGui(player);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The machine a click on this core is for: the core's own machine, or, when this core is a part of a bigger
     * formed machine (a machine core inside the alchemical nexus), that machine. Server side for the second case.
     */
    private static @Nullable MultiblockCoreBlockEntity owner(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core)) return null;
        if (!core.isFormed() && !level.isClientSide()) {
            MultiblockCoreBlockEntity parent = CoreTracker.formedCoreAt(level, pos);
            if (parent != null) return parent;
        }
        return core;
    }

    // Runs serverTick() on the server (the machine) and clientTick() on the client (particles)
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return (lvl, pos, st, be) -> {
                if (be instanceof MultiblockCoreBlockEntity core) core.clientTick();
            };
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof MultiblockCoreBlockEntity core) core.serverTick();
        };
    }
}
