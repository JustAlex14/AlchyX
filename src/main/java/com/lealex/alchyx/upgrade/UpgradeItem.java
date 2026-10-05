package com.lealex.alchyx.upgrade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** One tier of an upgrade type. It goes in its type's slot of a machine's upgrade tab. */
public class UpgradeItem extends Item {
    private final UpgradeType type;
    private final int tier;

    public UpgradeItem(Properties properties, UpgradeType type, int tier) {
        super(properties);
        this.type = type;
        this.tier = tier;
    }

    public UpgradeType type() {
        return type;
    }

    public int tier() {
        return tier;
    }

    /** The stack's tier if it's an upgrade of this type, else 0. */
    public static int tierOf(ItemStack stack, UpgradeType type) {
        return stack.getItem() instanceof UpgradeItem upgrade && upgrade.type == type ? upgrade.tier : 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        type.tooltip().accept(tier, tooltip);
        tooltip.accept(Component.translatableWithFallback("tooltip.alchyx.upgrade", "Goes in a machine's upgrade tab")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
