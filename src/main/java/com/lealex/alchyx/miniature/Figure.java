package com.lealex.alchyx.miniature;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * A mob or a player shown in a miniature: where it stands counted from the box's lowest corner, where it looks, and
 * for a player whose skin to show. Only what a small moving figure needs: no armor, no held items, no colors.
 *
 * Figures come from real entities ({@link #gather}, for a live picture) or are made up by a mod (a simulation: a
 * golem walking to its end in a farm's preview). The client's Puppets move them smoothly between two reports.
 *
 * @param id any number that stays the same for one figure from report to report
 */
public record Figure(int id, EntityType<?> type, float x, float y, float z, float yaw, float headYaw, float pitch,
                     boolean baby, Optional<UUID> player, String name) {
    private static final StreamCodec<RegistryFriendlyByteBuf, EntityType<?>> TYPE_CODEC = ByteBufCodecs.registry(Registries.ENTITY_TYPE);

    /** A mob (not a player), for mods that make their own figures. */
    public static Figure mob(int id, EntityType<?> type, float x, float y, float z, float yaw, boolean baby) {
        return new Figure(id, type, x, y, z, yaw, yaw, 0, baby, Optional.empty(), "");
    }

    /** The living things inside a box of the world, at most {@code max}, as figures counted from {@code min}. */
    public static List<Figure> gather(ServerLevel level, BlockPos min, BlockPos max, int most) {
        List<Figure> figures = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, AABB.encapsulatingFullBlocks(min, max))) {
            if (entity.isSpectator() || entity.isInvisible() || !entity.isAlive()) continue;
            boolean isPlayer = entity instanceof ServerPlayer;
            figures.add(new Figure(entity.getId(), entity.getType(),
                    (float) (entity.getX() - min.getX()), (float) (entity.getY() - min.getY()), (float) (entity.getZ() - min.getZ()),
                    entity.yBodyRot, entity.getYHeadRot(), entity.getXRot(), entity.isBaby(),
                    isPlayer ? Optional.of(entity.getUUID()) : Optional.empty(), isPlayer ? entity.getScoreboardName() : ""));
            if (figures.size() >= most) break;
        }
        return figures;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(id);
        TYPE_CODEC.encode(buf, type);
        buf.writeFloat(x);
        buf.writeFloat(y);
        buf.writeFloat(z);
        buf.writeFloat(yaw);
        buf.writeFloat(headYaw);
        buf.writeFloat(pitch);
        buf.writeBoolean(baby);
        buf.writeBoolean(player.isPresent());
        if (player.isPresent()) {
            buf.writeUUID(player.get());
            buf.writeUtf(name, 16);
        }
    }

    public static Figure read(RegistryFriendlyByteBuf buf) {
        int id = buf.readVarInt();
        EntityType<?> type = TYPE_CODEC.decode(buf);
        float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
        float yaw = buf.readFloat(), headYaw = buf.readFloat(), pitch = buf.readFloat();
        boolean baby = buf.readBoolean();
        boolean isPlayer = buf.readBoolean();
        return new Figure(id, type, x, y, z, yaw, headYaw, pitch, baby,
                isPlayer ? Optional.of(buf.readUUID()) : Optional.empty(), isPlayer ? buf.readUtf(16) : "");
    }
}
