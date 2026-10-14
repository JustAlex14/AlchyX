package com.lealex.alchyx.rift;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;

/**
 * The rift's mark on an item: a RIFTED thing is an ordinary item (any mod's) that a rift has enhanced, known by a
 * level kept in its custom data, 1 (barely touched) to {@link #TOP} (6). No new item and no texture: a mod changes
 * the item's components as it likes (more food, an effect, a glint, a name) and marks it here; any other mod can
 * then ask {@link #levelOf}, take rifted things in its recipes ({@link RiftedIngredient}, "alchyx:rifted"), or make
 * its own.
 *
 * Through a furnace the mark would be lost (the result is a new item): {@code mixin/FurnaceRiftMixin} asks {@link
 * #cooked} for the result instead, and the mods that make rifted things say there what a rifted input cooks into
 * ({@link #onCooked}).
 *
 * The data's key is "alchyfarm_rifted": the first rifted things were AlchyFarm's, and those already in players'
 * chests must stay rifted.
 */
public final class RiftMark {
    private RiftMark() {}

    public static final int TOP = 6;
    private static final String MARK = "alchyfarm_rifted";

    /** The rift's hold on this thing when it was made (1 to 6), or 0 if it is not rifted. */
    public static int levelOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            var tag = data.copyTag();
            int level = tag.getIntOr(MARK, 0);
            if (level > 0) return Math.min(TOP, level);
            if (tag.getBooleanOr(MARK, false)) return 3; // marked before levels existed
        }
        // (food rifted before any mark existed: it glints and is named "Rifted ...")
        Component name = stack.get(DataComponents.ITEM_NAME);
        return Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) && name != null && name.getString().startsWith("Rifted ") ? 3 : 0;
    }

    public static boolean isRifted(ItemStack stack) {
        return levelOf(stack) > 0;
    }

    /** Marks this stack as rifted to this level (1 to 6). Its look and what it does are the caller's to set. */
    public static void mark(ItemStack stack, int level) {
        int kept = Math.clamp(level, 1, TOP);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(MARK, kept));
    }

    /** The word before a rifted thing's name: "Rifted ", from level 3 "Deep-Rifted ", at the top "Void-Rifted ". */
    public static String grade(int level) {
        return level >= TOP ? "Void-Rifted " : level >= 3 ? "Deep-Rifted " : "Rifted ";
    }

    /** The colour of a rifted thing's name: light violet, dark violet at the top. */
    public static ChatFormatting color(int level) {
        return level >= TOP ? ChatFormatting.DARK_PURPLE : ChatFormatting.LIGHT_PURPLE;
    }

    /** How many times as much a rifted thing is worth: 2 at levels 1 to 3, 3 at 4 and 5, 4 at the top. */
    public static int times(int level) {
        return level >= TOP ? 4 : level >= 4 ? 3 : 2;
    }

    // ---- Through a furnace ----

    private static final List<BiFunction<ItemStack, ItemStack, @Nullable ItemStack>> COOKS = new CopyOnWriteArrayList<>();

    /**
     * A mod says what a RIFTED input cooks into: it is given the input (rifted) and the recipe's own result, and
     * returns the result to use instead, or null to leave it to the next mod. Asked for every smelt of a rifted
     * thing, in furnaces, smokers and blast furnaces: keep it light.
     */
    public static void onCooked(BiFunction<ItemStack, ItemStack, @Nullable ItemStack> cook) {
        COOKS.add(cook);
    }

    /** What a furnace gives for this input: the recipe's result, or what a mod makes of it when the input is rifted. */
    public static ItemStack cooked(ItemStack input, ItemStack result) {
        if (result.isEmpty() || COOKS.isEmpty() || levelOf(input) <= 0) return result;
        for (BiFunction<ItemStack, ItemStack, @Nullable ItemStack> cook : COOKS) {
            ItemStack made = cook.apply(input, result);
            if (made != null) return made;
        }
        return result;
    }
}
