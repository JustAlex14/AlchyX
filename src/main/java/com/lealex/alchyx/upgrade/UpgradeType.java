package com.lealex.alchyx.upgrade;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A kind of machine upgrade (speed, productivity...), registered in code with {@link UpgradeTypes#register}. AlchyX
 * gives each one an item per tier ({@link UpgradeItem}), a slot in the machine's upgrade tab, a floating display on
 * the structure ("upgrade_<path>") and a share of the machine's corruption; what it does is the machine's own code
 * (read {@link UpgradeStorage#tier}).
 *
 * @param id      e.g. "alchymastery:speed"
 * @param maxTier highest tier (1 for a single item)
 * @param color   RGB: the corruption veins pulse in the installed upgrades' colors
 * @param emblem  its symbol, shown faint in the empty slot: a 16x16 sprite in a 256x256 texture
 * @param tooltip lines under an upgrade item's name, by tier
 */
public record UpgradeType(Identifier id, int maxTier, int color, Emblem emblem, BiConsumer<Integer, Consumer<Component>> tooltip) {
    /** A 16x16 sprite at (u, v) in a 256x256 texture. */
    public record Emblem(Identifier texture, int u, int v) {}

    /** The name of the machine display that floats this upgrade on the structure (animation files: "display"). */
    public String displayName() {
        return "upgrade_" + id.getPath();
    }

    /** Translation key of the slot hint ("upgrade.<namespace>.<path>"), e.g. "Speed upgrade (I-IV)". */
    public String translationKey() {
        return "upgrade." + id.getNamespace() + "." + id.getPath();
    }
}
