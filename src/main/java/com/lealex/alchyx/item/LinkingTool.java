package com.lealex.alchyx.item;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The base of a tool that links blocks: it HOLDS one block (the first of a pair, the machine being set up), kept in
 * its custom data, and its other uses act on what it holds. What every such tool shares is here: it glows while it
 * holds something, its tooltip tells where ({@link #heldLines}), and sneak + use in the air makes it let go. It can
 * also keep other places by name ({@link #note}: a first corner, say). What a use on a block does is the mod's own
 * {@code useOn}; so are its name, its picture and the motes it shows ({@code client/LinkLines} draws lines of them).
 * Each mod keeps its own tool, in its own theme: this is only the part they would otherwise each write.
 */
public abstract class LinkingTool extends Item {
    private static final String HELD = "alchyx_held";

    protected LinkingTool(Properties properties) {
        super(properties);
    }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    /** The block this tool holds, or null. (Both sides: the stack's data is synced.) */
    public static @Nullable BlockPos held(ItemStack stack) {
        return noted(stack, HELD);
    }

    /** A place kept under this name, or null. */
    public static @Nullable BlockPos noted(ItemStack stack, String name) {
        CompoundTag tag = data(stack);
        return tag.contains(name) ? BlockPos.of(tag.getLongOr(name, 0L)) : null;
    }

    /** Keeps a place under this name, or forgets it (null). */
    public static void note(ItemStack stack, String name, @Nullable BlockPos pos) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (pos == null) tag.remove(name);
            else tag.putLong(name, pos.asLong());
        });
    }

    /** The tool takes hold of this block. Its notes are the mod's to clear. */
    public static void hold(ItemStack stack, BlockPos pos) {
        note(stack, HELD, pos);
    }

    /** The tool holds nothing any more. */
    public static void letGo(ItemStack stack) {
        note(stack, HELD, null);
    }

    /** Called when the player made it let go by sneak + use in the air (server side): clear the notes, say so. */
    protected void lostHold(ItemStack stack, Player player) {}

    // Sneak + use in the air: it lets go
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || held(stack) == null) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            letGo(stack);
            lostHold(stack, player);
        }
        return InteractionResult.SUCCESS;
    }

    // Glows while it holds something
    @Override
    public boolean isFoil(ItemStack stack) {
        return held(stack) != null;
    }

    /** Tooltip lines: where the block it holds is ("Holding the post at 10 64 -3"), or that it holds none. */
    protected void heldLines(ItemStack stack, Consumer<Component> tooltip, String what) {
        BlockPos held = held(stack);
        if (held == null) {
            tooltip.accept(Component.literal("Holding no " + what).withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.accept(Component.literal("Holding the " + what + " at " + held.getX() + " " + held.getY() + " " + held.getZ()).withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.accept(Component.literal("Sneak + use in the air: let go").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** A line in the action bar. */
    protected static void tell(Player player, String says) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(Component.literal(says), true);
    }
}
