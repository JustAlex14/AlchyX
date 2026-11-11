package com.lealex.alchyx.item;

import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.FreeBuild;
import com.lealex.alchyx.multiblock.MachineStudio;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
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
 * The tool that gives a free-build machine (multiblock/FreeBuild) its shape, in survival:
 * <ol>
 *   <li>use it on a block: the first corner of the box; on another block: the opposite corner. The box shows as
 *       lines of motes while the tool is in hand (client/RecorderOutline).</li>
 *   <li>use it on the machine's core: the core checks its rules against what stands in the box and, if they hold,
 *       records the build as its shape and forms. Otherwise it says what is wrong (too big, a block it refuses,
 *       what is still missing).</li>
 * </ol>
 * Once the machine stands, use it on its core (no corners marked) to open the Machine Studio: the machine in 3D,
 * where each block's motion is picked and previewed, then saved at once (client/studio/StudioScreen).
 * Or use it on one of its blocks to pick how that block moves (animation/Motion: spin, bob,
 * strike, swing, shake, in turn, then still again; sneak + use stops it at once), among the motions the machine allows.
 * Sneak + use on a core forgets its shape (a formed machine breaks, its parts turn back into their blocks). Sneak +
 * use in the air forgets the corners. Used on a core with no corners marked, it tells that machine's rules.
 */
public class StructureRecorderItem extends Item implements MachineTool {
    private static final String FIRST = "alchyx_corner_a", SECOND = "alchyx_corner_b";

    public StructureRecorderItem(Properties properties) {
        super(properties);
    }

    /** The first corner marked, or null. (Both sides: the stack's data is synced.) */
    public static @Nullable BlockPos first(ItemStack stack) {
        return LinkingTool.noted(stack, FIRST);
    }

    /** The second corner marked, or null. */
    public static @Nullable BlockPos second(ItemStack stack) {
        return LinkingTool.noted(stack, SECOND);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();
        boolean studio = !player.isShiftKeyDown() && first(stack) == null
                && level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core && MachineStudio.opens(core);
        if (level.isClientSide()) {
            if (studio) MachineStudio.clientOpen.accept(pos.immutable()); // the Machine Studio
            return InteractionResult.SUCCESS; // otherwise the server decides; just swing the arm
        }
        if (studio) return InteractionResult.SUCCESS; // the client opened the studio

        if (level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core) {
            useOnCore(level, player, stack, core);
            return InteractionResult.SUCCESS;
        }

        // A part of a formed free-build machine: pick its motion
        if (com.lealex.alchyx.multiblock.ShellParts.isPart(level.getBlockState(pos))) {
            MultiblockCoreBlockEntity owner = com.lealex.alchyx.multiblock.CoreTracker.formedCoreAt(level, pos);
            if (owner != null && owner.freeBuild() != null && owner.recordedShape() != null) {
                useOnPart(level, player, pos, owner);
                return InteractionResult.SUCCESS;
            }
        }

        // A corner
        BlockPos a = first(stack), b = second(stack);
        if (a == null || b != null) {
            LinkingTool.note(stack, FIRST, pos);
            LinkingTool.note(stack, SECOND, null);
            tell(player, "First corner at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": now the opposite corner");
        } else {
            LinkingTool.note(stack, SECOND, pos);
            tell(player, "Box of " + size(a, pos) + " marked: use the Recorder on the machine's core");
        }
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.PLAYERS, 0.6f, 1.4f);
        return InteractionResult.SUCCESS;
    }

    private static void useOnCore(Level level, Player player, ItemStack stack, MultiblockCoreBlockEntity core) {
        FreeBuild rules = core.freeBuild();
        if (rules == null) {
            tell(player, "This machine has a fixed shape: build it as its book shows");
            return;
        }
        if (!level.mayInteract(player, core.getBlockPos())) {
            tell(player, "You can't change this machine here");
            return;
        }
        if (player.isShiftKeyDown()) {
            tell(player, core.forgetShape() ? "Shape forgotten: build it again and record it" : "Nothing recorded yet. " + rules.describe());
            return;
        }
        BlockPos a = first(stack), b = second(stack);
        if (a == null || b == null) {
            tell(player, (core.recordedShape() != null ? "Shape recorded. " : "Mark two opposite corners of the build first. ") + rules.describe());
            return;
        }
        String problem = core.recordShape(a, b);
        if (problem != null) {
            if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(Component.literal("Not recorded: " + problem), false); // chat: it can be long
            level.playSound(null, core.getBlockPos(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.5f, 1.0f);
            return;
        }
        LinkingTool.note(stack, FIRST, null);
        LinkingTool.note(stack, SECOND, null);
        tell(player, "Shape recorded (" + size(a, b) + ")");
        level.playSound(null, core.getBlockPos(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    private static void useOnPart(Level level, Player player, BlockPos pos, MultiblockCoreBlockEntity owner) {
        FreeBuild rules = owner.freeBuild();
        if (rules == null) return;
        if (!level.mayInteract(player, pos)) {
            tell(player, "You can't change this machine here");
            return;
        }
        if (rules.motions().isEmpty()) {
            tell(player, "This machine's blocks don't move");
            return;
        }
        Motion motion = owner.changeMotion(pos, player.isShiftKeyDown());
        String when = switch (rules.motionWhen()) {
            case IDLE -> " while the machine rests";
            case WORKING -> " while the machine works";
            default -> "";
        };
        tell(player, motion == null ? "This block stays still" : "This block will " + motion.getSerializedName() + when + " (use again: next motion)");
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6f, motion == null ? 0.8f : 1.3f);
    }

    // Sneak + use in the air: forget the corners
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || first(stack) == null) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            LinkingTool.note(stack, FIRST, null);
            LinkingTool.note(stack, SECOND, null);
            tell(player, "Corners forgotten");
        }
        return InteractionResult.SUCCESS;
    }

    // Glows while a box is marked
    @Override
    public boolean isFoil(ItemStack stack) {
        return second(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        BlockPos a = first(stack), b = second(stack);
        if (a != null && b != null) {
            tooltip.accept(Component.literal("Box of " + size(a, b) + " marked").withStyle(ChatFormatting.LIGHT_PURPLE));
        } else if (a != null) {
            tooltip.accept(Component.literal("First corner at " + a.getX() + " " + a.getY() + " " + a.getZ()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        tooltip.accept(Component.literal("Use on two blocks: the corners of your build").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Use on the machine's core: record the build").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Use on a formed machine's core: open the Machine Studio").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Use on a block of a formed machine: pick how it moves").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Sneak + use on the core: forget its shape").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.accept(Component.literal("Sneak + use in the air: forget the corners").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String size(BlockPos a, BlockPos b) {
        return (Math.abs(a.getX() - b.getX()) + 1) + " x " + (Math.abs(a.getY() - b.getY()) + 1) + " x " + (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    private static void tell(Player player, String says) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(Component.literal(says), true);
    }
}
