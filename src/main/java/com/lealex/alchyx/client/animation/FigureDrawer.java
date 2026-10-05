package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.Effect;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Turns a "figure" effect into something to draw: an entity's render state (the entity is created once per machine
 * and effect, never added to the world), or a coded figure with its context. Used by the AnimationEngine for the
 * machines' own figures, and by mods that draw a machine's figures elsewhere (e.g. in a miniature).
 */
public final class FigureDrawer {
    private FigureDrawer() {}

    /** A figure ready to submit with its feet at the pose's origin. */
    public record Prepared(@Nullable EntityRenderState entity, @Nullable CodedFigure coded, CodedFigure.Context context,
                           float yaw, float scale, float lift) {}

    private record Cached(Effect.Figure effect, Entity entity) {}

    // Entities per owner (a machine core, or whatever draws the figure) and effect index
    private static final Map<Object, Map<Integer, Cached>> ENTITIES = new WeakHashMap<>();

    /**
     * @param owner     what the figure belongs to (its entity is cached per owner and index)
     * @param feet      world position of its feet (light, head-to-camera direction)
     * @param yaw       its heading, entity convention (degrees; 0 = toward +z)
     * @param displays  the machine's displays by name (for "display" / "unless_display" and coded figures)
     * @param camera    the camera, for "look_at_player"; null to keep the head straight (e.g. in a miniature, where
     *                  feet isn't where the small figure really is)
     * @return null when it shouldn't show now (its "when" or display conditions) or can't be made
     */
    public static @Nullable Prepared prepare(Object owner, int index, ClientLevel level, Effect.Figure effect, Vec3 feet,
                                             float yaw, boolean working, Function<String, ItemStack> displays,
                                             float time, float partialTicks, @Nullable Vec3 camera) {
        var when = effect.timing().when();
        if (when.continuous() ? !when.holds(working) : !working) return null;
        if (effect.display().isPresent() && displays.apply(effect.display().get()).isEmpty()) return null;
        if (effect.unlessDisplay().isPresent() && !displays.apply(effect.unlessDisplay().get()).isEmpty()) return null;

        float lift = effect.hover() + (effect.bob() == 0 ? 0 : Mth.sin(time * 0.08F) * effect.bob());
        int light = LevelRenderer.getLightCoords(level, BlockPos.containing(feet.add(0, 0.5, 0)));
        ItemStack display = effect.display().map(displays).orElse(ItemStack.EMPTY);
        CodedFigure.Context context = new CodedFigure.Context(display, working, time, light);

        if (effect.figure().isPresent()) {
            CodedFigure coded = Figures.get(effect.figure().get());
            return coded == null ? null : new Prepared(null, coded, context, yaw, effect.scale(), lift);
        }

        Entity entity = entity(owner, index, level, effect);
        if (entity == null) return null;
        entity.setPos(feet.x, feet.y, feet.z);
        entity.xo = entity.xOld = feet.x;
        entity.yo = entity.yOld = feet.y;
        entity.zo = entity.zOld = feet.z;
        entity.setYRot(yaw);
        entity.yRotO = yaw;
        entity.tickCount = (int) time;
        float headYaw = yaw, pitch = 0;
        if (effect.lookAtPlayer() && camera != null) {
            Vec3 eyes = feet.add(0, entity.getEyeHeight() * effect.scale() + lift, 0);
            Vec3 to = camera.subtract(eyes);
            float toward = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
            headYaw = yaw + Mth.clamp(Mth.wrapDegrees(toward - yaw), -75.0F, 75.0F);
            pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))), -40.0F, 40.0F);
        }
        entity.setXRot(pitch);
        entity.xRotO = pitch;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = living.yBodyRotO = yaw;
            living.yHeadRot = living.yHeadRotO = headYaw;
        }
        EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(entity, partialTicks);
        state.shadowPieces.clear(); // it's drawn at a pose, not where the entity "is": no shadow, no name
        state.shadowRadius = 0;
        state.nameTag = null;
        if (effect.ghost() && state instanceof LivingEntityRenderState livingState) {
            livingState.isInvisible = true;          // vanilla draws an invisible mob see-through when the player
            livingState.isInvisibleToPlayer = false; // may see it (spectators): that's the ghost look
        }
        return new Prepared(state, null, context, yaw, effect.scale(), lift);
    }

    private static @Nullable Entity entity(Object owner, int index, ClientLevel level, Effect.Figure effect) {
        Map<Integer, Cached> byIndex = ENTITIES.computeIfAbsent(owner, key -> new HashMap<>());
        Cached cached = byIndex.get(index);
        if (cached != null && cached.effect() == effect && cached.entity().level() == level) return cached.entity();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(effect.entity().orElseThrow()).orElse(null);
        Entity entity = type == null ? null : type.create(level, EntitySpawnReason.LOAD);
        if (entity == null) {
            AlchyX.LOGGER.warn("AlchyX: figure entity {} can't be created", effect.entity().get());
            byIndex.remove(index);
            return null;
        }
        if (effect.nbt().isPresent()) {
            try {
                CompoundTag tag = TagParser.parseCompoundFully(effect.nbt().get());
                entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            } catch (Exception e) {
                AlchyX.LOGGER.warn("AlchyX: bad figure nbt {}: {}", effect.nbt().get(), e.getMessage());
            }
        }
        byIndex.put(index, new Cached(effect, entity));
        return entity;
    }

    /** Draws a prepared figure with its feet at the pose's origin. */
    public static void submit(Prepared figure, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0, figure.lift(), 0);
        poseStack.scale(figure.scale(), figure.scale(), figure.scale());
        if (figure.entity() != null) {
            // The renderer turns it by the yaw in its state
            Minecraft.getInstance().getEntityRenderDispatcher().submit(figure.entity(), camera, 0, 0, 0, poseStack, collector);
        } else if (figure.coded() != null) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - figure.yaw()));
            figure.coded().submit(figure.context(), poseStack, collector);
        }
        poseStack.popPose();
    }
}
