package com.lealex.alchyx.mixin;

import com.lealex.alchyx.rift.RiftMark;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A rifted thing (rift/RiftMark) stays rifted through a furnace, a smoker or a blast furnace. The game has no event for what a furnace makes, and a
 * recipe cannot see that its input is rifted, so this changes the RESULT the furnace is about to use, in the two
 * places it uses it: canBurn (does it fit in the result slot?) and burn (put it there). Both get the recipe's
 * result as an argument; when the input is rifted, they get what the mods
that make rifted things say it cooks into (RiftMark.cooked, RiftMark.onCooked).
 * So a rifted potato becomes a rifted baked potato, rifted ones stack with rifted ones in the result slot, and a
 * furnace whose result slot holds the other kind waits, as it does for any result that does not fit.
 *
 * Both injections are optional (require = 0): if a game update moves these methods, the mod still loads and
 * cooking simply loses the rift again. Campfires are not covered.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceRiftMixin {
    // private static boolean canBurn(NonNullList<ItemStack> items, int maxStackSize, ItemStack result)
    @ModifyVariable(method = "canBurn", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static ItemStack alchyx$riftedFits(ItemStack result, NonNullList<ItemStack> items) {
        return items.isEmpty() ? result : RiftMark.cooked(items.get(0), result);
    }

    // private static void burn(NonNullList<ItemStack> items, ItemStack input, ItemStack result)
    @ModifyVariable(method = "burn", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private static ItemStack alchyx$riftedResult(ItemStack result, NonNullList<ItemStack> items, ItemStack input) {
        return RiftMark.cooked(input, result);
    }
}
