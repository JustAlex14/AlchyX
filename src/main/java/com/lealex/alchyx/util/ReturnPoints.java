package com.lealex.alchyx.util;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A way home kept on a player while they are somewhere they should not stay (a pocket dimension, an arena, a trial):
 * where they stood, in which world, facing which way. Saved in the player's persistent data under the caller's key,
 * so a crash or a logout still brings them back. Each mod uses its own key ("modid:what").
 */
public final class ReturnPoints {
    private ReturnPoints() {}

    /** Remembers where the player is now (replacing a point kept before under that key). */
    public static void keep(ServerPlayer player, String key) {
        CompoundTag back = new CompoundTag();
        back.putString("dim", player.level().dimension().identifier().toString());
        back.putDouble("x", player.getX());
        back.putDouble("y", player.getY());
        back.putDouble("z", player.getZ());
        back.putFloat("yaw", player.getYRot());
        player.getPersistentData().put(key, back);
    }

    public static boolean has(ServerPlayer player, String key) {
        return player.getPersistentData().contains(key);
    }

    public static void forget(ServerPlayer player, String key) {
        player.getPersistentData().remove(key);
    }

    /**
     * Sends the player back to the point and forgets it. With no point, a world that is gone, or a world in
     * {@code never} (the place they are leaving), they go to the overworld's spawn instead.
     */
    public static void sendBack(ServerPlayer player, String key, @Nullable ResourceKey<Level> never) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        CompoundTag back = player.getPersistentData().getCompoundOrEmpty(key);
        player.getPersistentData().remove(key);
        ServerLevel level = null;
        String dim = back.getStringOr("dim", "");
        if (!dim.isEmpty()) level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
        if (level == null || level.dimension() == never) {
            level = server.overworld();
            BlockPos spawn = level.getRespawnData().pos();
            player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), player.getYRot(), 0F, true);
        } else {
            player.teleportTo(level, back.getDoubleOr("x", 0), back.getDoubleOr("y", 64), back.getDoubleOr("z", 0), Set.of(),
                    back.getFloatOr("yaw", 0F), 0F, true);
        }
    }
}
