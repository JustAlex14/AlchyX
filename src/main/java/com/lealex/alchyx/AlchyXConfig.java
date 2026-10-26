package com.lealex.alchyx;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side settings shared by every AlchyX mod, saved in config/alchyx-common.toml. */
public final class AlchyXConfig {
    private AlchyXConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Where machines tell nearby players what they do (forming, formed, broken, growing, a tier grown...).").push("messages");
    }

    public enum Where { ACTION_BAR, CHAT, OFF }

    public static final ModConfigSpec.EnumValue<Where> MACHINE_MESSAGES = BUILDER
            .comment("ACTION_BAR: a line over the hotbar that fades (default). CHAT: in the chat. OFF: not at all.")
            .defineEnum("machine", Where.ACTION_BAR);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Where machine messages go (the action bar while the config isn't loaded). */
    public static Where machineMessages() {
        try {
            return MACHINE_MESSAGES.get();
        } catch (IllegalStateException notLoaded) {
            return Where.ACTION_BAR;
        }
    }

    /** Tells the players within `range` blocks of `center` a machine's message, where the config says. */
    public static void tellNearby(ServerLevel level, Vec3 center, double range, Component message) {
        Where where = machineMessages();
        if (where == Where.OFF) return;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) <= range * range) player.sendSystemMessage(message, where == Where.ACTION_BAR);
        }
    }
}
