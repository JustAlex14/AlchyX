package com.lealex.alchyx.item;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.FreeBuild;
import com.lealex.alchyx.multiblock.MachineStudio;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Opens the Machine Studio (client/studio/StudioScreen) on the machine it is used on: its core, or any block of a
 * formed machine. A free-build machine is built there from its core alone, block by block, and forms on Save; any
 * machine is animated there. (Sneak + right-click on a core with an empty hand opens it too.)
 */
public class StructureRecorderItem extends Item implements MachineTool {
    public StructureRecorderItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        MultiblockCoreBlockEntity core = coreOf(level, context.getClickedPos());
        if (core == null) return InteractionResult.PASS;
        boolean opens = MachineStudio.opens(core);
        if (level.isClientSide()) {
            if (opens) MachineStudio.clientOpen.accept(core.getBlockPos().immutable());
            return InteractionResult.SUCCESS;
        }
        if (opens) {
            FreeBuild rules = core.freeBuild();
            if (rules != null && core.recordedShape() == null) tell(player, rules.describe());
        } else if (core.isForming()) {
            tell(player, "It's forming: wait a moment");
        } else {
            tell(player, core.studioEditable() && !core.getBlockState().is(MultiblockCoreBlockEntity.STUDIO_LOCKED)
                    ? "Complete this machine once as its book shows: then the studio opens on it"
                    : "This machine can't be changed in the Machine Studio");
        }
        return InteractionResult.SUCCESS;
    }

    /** The core it was used on, or the core of the formed machine that block belongs to. */
    private static @Nullable MultiblockCoreBlockEntity coreOf(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core) return core;
        if (!com.lealex.alchyx.multiblock.ShellParts.isPart(level.getBlockState(pos))) return null;
        if (!level.isClientSide()) return com.lealex.alchyx.multiblock.CoreTracker.formedCoreAt(level, pos);
        for (MultiblockCoreBlockEntity core : com.lealex.alchyx.animation.LoadedCores.snapshot()) {
            if (core.getLevel() == level && core.isFormed() && core.structureContains(pos)) return core;
        }
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("Use on a machine: open the Machine Studio").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Free-build machines: place the core, then build it in the studio").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void tell(Player player, String says) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(Component.literal(says), true);
    }
}
