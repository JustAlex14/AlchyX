package com.lealex.alchyx.rift;

import com.lealex.alchyx.registry.AlchyXRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

/**
 * A recipe ingredient: ANY item a rift has enhanced to this level or more ({@link RiftMark#levelOf}), whatever the
 * item. In a recipe file: {"neoforge:ingredient_type": "alchyx:rifted", "level": 3}.
 *
 * What recipe viewers show for it is a handful of samples: by default a few plain foods and raw metals carrying the
 * mark and a glint; a mod that makes rifted things gives better ones through {@link #samples} (as it really makes
 * them at that level).
 */
public record RiftedIngredient(int level) implements ICustomIngredient {
    public static final MapCodec<RiftedIngredient> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(1, RiftMark.TOP).optionalFieldOf("level", 1).forGetter(RiftedIngredient::level)).apply(instance, RiftedIngredient::new));

    private static final List<Item> SHOWN = List.of(Items.CARROT, Items.POTATO, Items.BEETROOT, Items.APPLE, Items.MELON_SLICE, Items.SWEET_BERRIES,
            Items.COD, Items.SALMON, Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.HONEY_BOTTLE, Items.RAW_IRON, Items.RAW_COPPER,
            Items.RAW_GOLD);

    /** Level to the stacks shown for it. A mod making rifted things may replace it (once, at set-up). */
    public static volatile IntFunction<List<ItemStack>> samples = level -> {
        List<ItemStack> plain = new ArrayList<>(SHOWN.size());
        for (Item item : SHOWN) {
            ItemStack stack = new ItemStack(item);
            RiftMark.mark(stack, level);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            plain.add(stack);
        }
        return plain;
    };

    @Override
    public boolean test(ItemStack stack) {
        return RiftMark.levelOf(stack) >= level;
    }

    @Override
    public Stream<Holder<Item>> items() {
        return SHOWN.stream().map(Item::builtInRegistryHolder);
    }

    @Override
    public boolean isSimple() {
        return false; // it reads the stack's components
    }

    @Override
    public IngredientType<?> getType() {
        return AlchyXRegistries.RIFTED_INGREDIENT.get();
    }

    @Override
    public SlotDisplay display() {
        List<SlotDisplay> shown = new ArrayList<>();
        for (ItemStack stack : samples.apply(level)) {
            if (!stack.isEmpty()) shown.add(new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(stack.getItem(), stack.getComponentsPatch())));
        }
        return new SlotDisplay.Composite(shown);
    }
}
