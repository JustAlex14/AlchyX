package com.lealex.alchyx.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a linking tool shows while it is in hand: lines of motes between linked blocks. Client only, nothing is sent
 * for it. A mod calls these from its own ClientTickEvent listener, every few ticks, with its own motes and colours.
 */
public final class LinkLines {
    private LinkLines() {}

    /** The stack of this kind of tool the player has in either hand, or null. */
    public static @Nullable ItemStack inHand(LocalPlayer player, Class<? extends Item> tool) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (tool.isInstance(stack.getItem())) return stack;
        }
        return null;
    }

    /**
     * A line of motes from one point to another, one every half block (two at least, {@code most} at most). They
     * drift along the line from call to call, so a link reads as a flow and not as a row of dots.
     *
     * @param every how many ticks apart the mod calls this (the drift's pace)
     */
    public static void line(ClientLevel level, Vec3 from, Vec3 to, ParticleOptions mote, int most, int every) {
        Vec3 along = to.subtract(from);
        int motes = (int) Math.min(most, Math.max(2, along.length() / 0.5));
        double shift = (level.getGameTime() / (double) Math.max(1, every) % 4) / 4.0;
        for (int i = 0; i < motes; i++) {
            double t = (i + shift) / motes;
            level.addParticle(mote, from.x + along.x * t, from.y + along.y * t, from.z + along.z * t, 0, 0, 0);
        }
    }
}
