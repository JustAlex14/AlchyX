package com.lealex.alchyx.client.miniature;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.miniature.BoxWatch;
import com.lealex.alchyx.miniature.Figure;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * The mobs and players of miniatures, as puppets: for each {@link Figure} told for an owner, an entity of its kind
 * is made here (never added to the world) and moved smoothly between the places told from one report to the next,
 * legs swinging when it walks.
 *
 * Reports are expected every {@link BoxWatch#FIGURES_EVERY} ticks or so. They can come from the server (a live
 * picture, see BoxWatch) or be made up on the client (a simulation: a farm's preview moving a golem along).
 * An owner is whatever the miniature belongs to: a position, a block entity.
 */
public final class Puppets {
    private Puppets() {}

    private static final Map<Object, Group> GROUPS = new HashMap<>();

    /** The puppets of one owner's miniature. */
    private static final class Group {
        private List<Figure> seen = List.of();
        private final Map<Integer, Puppet> puppets = new HashMap<>();
    }

    /** One mob or player: its stand-in entity, where it was and where it is going (box coordinates). */
    private static final class Puppet {
        private @Nullable Entity entity;
        private boolean broken;
        private float fromX, fromY, fromZ, fromYaw, fromHeadYaw, fromPitch;
        private Figure to;
        private long since;

        private Puppet(Figure figure, long now) {
            this.to = figure;
            this.since = now - BoxWatch.FIGURES_EVERY; // just appeared: already in place
            startFrom(figure.x(), figure.y(), figure.z(), figure.yaw(), figure.headYaw(), figure.pitch());
        }

        private void startFrom(float x, float y, float z, float yaw, float headYaw, float pitch) {
            fromX = x;
            fromY = y;
            fromZ = z;
            fromYaw = yaw;
            fromHeadYaw = headYaw;
            fromPitch = pitch;
        }

        private float progress(float time) {
            return Mth.clamp((time - since) / BoxWatch.FIGURES_EVERY, 0.0F, 1.0F);
        }
    }

    /** A figure ready to draw with its feet at a place counted from the box's lowest corner. */
    public record Prepared(EntityRenderState state, float x, float y, float z) {}

    public static void clear() {
        GROUPS.clear();
    }

    /**
     * Once per client tick: takes in what was last told for each owner and keeps the legs moving. Owners left out
     * lose their puppets. A new report is recognised as a new list object, so pass the same list until it changes.
     */
    public static void tick(ClientLevel level, Map<?, List<Figure>> reports) {
        GROUPS.keySet().retainAll(reports.keySet());
        long now = level.getGameTime();
        for (Map.Entry<?, List<Figure>> report : reports.entrySet()) {
            Group group = GROUPS.computeIfAbsent(report.getKey(), key -> new Group());
            List<Figure> told = report.getValue();
            if (told != group.seen) {
                group.seen = told;
                Map<Integer, Puppet> kept = new HashMap<>();
                for (Figure figure : told) {
                    Puppet puppet = group.puppets.get(figure.id());
                    if (puppet == null || puppet.to.type() != figure.type()) {
                        puppet = new Puppet(figure, now);
                    } else {
                        float t = puppet.progress(now);
                        puppet.startFrom(Mth.lerp(t, puppet.fromX, puppet.to.x()), Mth.lerp(t, puppet.fromY, puppet.to.y()),
                                Mth.lerp(t, puppet.fromZ, puppet.to.z()), Mth.rotLerp(t, puppet.fromYaw, puppet.to.yaw()),
                                Mth.rotLerp(t, puppet.fromHeadYaw, puppet.to.headYaw()), Mth.lerp(t, puppet.fromPitch, puppet.to.pitch()));
                        puppet.to = figure;
                        puppet.since = now;
                    }
                    kept.put(figure.id(), puppet);
                }
                group.puppets.clear();
                group.puppets.putAll(kept);
            }
            for (Puppet puppet : group.puppets.values()) {
                if (!(puppet.entity instanceof LivingEntity living)) continue;
                float dx = puppet.to.x() - puppet.fromX, dz = puppet.to.z() - puppet.fromZ;
                float perTick = puppet.progress(now) < 1.0F ? Mth.sqrt(dx * dx + dz * dz) / BoxWatch.FIGURES_EVERY : 0.0F;
                living.walkAnimation.update(Math.min(perTick * 4.0F, 1.0F), 0.4F, living.isBaby() ? 3.0F : 1.0F);
            }
        }
    }

    /**
     * An owner's figures for this frame. Their stand-in entities are put at {@code lightPos} (so they are lit like
     * the miniature), never where they are drawn.
     */
    public static List<Prepared> prepare(ClientLevel level, Object owner, BlockPos lightPos, float time, float partialTicks) {
        Group group = GROUPS.get(owner);
        if (group == null || group.puppets.isEmpty()) return List.of();
        List<Prepared> prepared = new ArrayList<>(group.puppets.size());
        double x = lightPos.getX() + 0.5, y = lightPos.getY(), z = lightPos.getZ() + 0.5;
        for (Puppet puppet : group.puppets.values()) {
            if (puppet.broken) continue;
            try {
                if (puppet.entity == null || puppet.entity.level() != level) puppet.entity = create(level, puppet.to);
                Entity entity = puppet.entity;
                if (entity == null) {
                    puppet.broken = true;
                    continue;
                }
                float t = puppet.progress(time);
                float yaw = Mth.rotLerp(t, puppet.fromYaw, puppet.to.yaw());
                float headYaw = Mth.rotLerp(t, puppet.fromHeadYaw, puppet.to.headYaw());
                float pitch = Mth.lerp(t, puppet.fromPitch, puppet.to.pitch());
                entity.setPos(x, y, z);
                entity.xo = entity.xOld = x;
                entity.yo = entity.yOld = y;
                entity.zo = entity.zOld = z;
                entity.setYRot(yaw);
                entity.yRotO = yaw;
                entity.setXRot(pitch);
                entity.xRotO = pitch;
                entity.tickCount = (int) time;
                if (entity instanceof LivingEntity living) {
                    living.yBodyRot = living.yBodyRotO = yaw;
                    living.yHeadRot = living.yHeadRotO = headYaw;
                }
                EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(entity, partialTicks);
                state.shadowPieces.clear(); // drawn at a pose, not where the entity "is": no shadow, no name
                state.shadowRadius = 0;
                state.nameTag = null;
                prepared.add(new Prepared(state, Mth.lerp(t, puppet.fromX, puppet.to.x()), Mth.lerp(t, puppet.fromY, puppet.to.y()),
                        Mth.lerp(t, puppet.fromZ, puppet.to.z())));
            } catch (Throwable e) { // a mob that can't be shown must never take the game down
                puppet.broken = true;
                AlchyX.LOGGER.warn("AlchyX: can't show {} in a miniature: {}", EntityType.getKey(puppet.to.type()), e.toString());
            }
        }
        return prepared;
    }

    private static @Nullable Entity create(ClientLevel level, Figure figure) {
        if (figure.player().isPresent()) return new RemotePlayer(level, new GameProfile(figure.player().get(), figure.name()));
        Entity entity = figure.type().create(level, EntitySpawnReason.LOAD);
        if (entity instanceof Mob mob) mob.setBaby(figure.baby());
        return entity;
    }
}
