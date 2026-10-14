package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.item.MachineTool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;

/** Game events that keep multiblocks up to date. */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class MultiblockEvents {

    private MultiblockEvents() {}

    // Fired whenever a block changes and notifies its neighbors: placing, breaking,
    // explosions, lighting a campfire... Nearby matrices re-check their structure on their next tick.
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (BlockPos matrixPos : CoreTracker.matricesNear(level, event.getPos())) {
            if (level.getBlockEntity(matrixPos) instanceof MultiblockCoreBlockEntity core) {
                core.requestStructureCheck(event.getPos());
            }
        }
    }

    // Right-click any part of a formed machine, whatever is in hand: opens its core's GUI (or shows its state).
    // Same for the machine's real vanilla blocks (cauldron, campfire, candles, lava...), so using them can't break
    // the machine (an empty bottle draining the cauldron). Sneaking (shift + right-click) keeps the normal
    // behavior, e.g. to place a block against the chamber or put the campfire out with a shovel.
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getEntity().isShiftKeyDown()) return;
        // Machine tools (wormhole attuner...) act on the clicked part themselves
        if (event.getItemStack().getItem() instanceof MachineTool) return;
        // A block in hand is placed against the machine (a tap on its cauldron...), no need to sneak
        if (event.getItemStack().getItem() instanceof net.minecraft.world.item.BlockItem) return;

        // Chamber parts only exist in a formed chamber, so both sides can decide from the clicked block.
        // Cancelling on the client too stops it from predicting a block placement.
        MultiblockCoreBlockEntity protecting = null;
        if (!ShellParts.isPart(event.getLevel().getBlockState(event.getPos()))) {
            protecting = MultiblockCoreBlockEntity.protectingCore(event.getLevel(), event.getPos());
            if (protecting == null) return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        if (!(event.getLevel() instanceof ServerLevel level)) return; // the rest happens on the server
        MultiblockCoreBlockEntity core = protecting != null ? protecting : CoreTracker.formedCoreAt(level, event.getPos());
        if (core == null) return;
        // A bucket (or any fluid container) fills or empties the machine's tanks instead of opening the GUI
        ResourceHandler<FluidResource> fluids = core.getFluidHandler();
        if (fluids != null && FluidUtil.interactWithFluidHandler(event.getEntity(), event.getHand(), event.getPos(), fluids, null)) return;
        core.openGui(event.getEntity());
    }

    // Single-player can open several worlds in one session: start each one with an empty list
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CoreTracker.clear();
    }
}
