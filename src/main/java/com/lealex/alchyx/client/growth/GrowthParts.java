package com.lealex.alchyx.client.growth;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.lealex.alchyx.growth.GrowthPayload;
import com.lealex.alchyx.multiblock.ShellParts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A growing machine's parts, drawn (growth/GrowthPayload): a grown part flies from its source on an arc down to its
 * place, small at first, turning, growing to its size and coming to rest square, full bright (it comes out of light);
 * there its hidden shell is shown. A cleared part flies up into the source shrinking. A rising part slides straight up
 * out of the ground by its distance, lit as its place, grit at its foot, and stays drawn until the real block is
 * there to take over (a core that moves only once everything stands). Each mod picks its particles with a
 * {@link Style}: the trail behind a flying part and the puff where it lands (default: reverse portal motes, its own
 * dust). What the source itself looks like (a rift, a portal, nothing) is the mod's own drawing.
 */
public final class GrowthParts {
    private GrowthParts() {}

    /** A mod's particles: the trail behind a flying part (null for none) and what happens where a part lands. */
    public record Style(Supplier<ParticleOptions> trail, BiConsumer<ClientLevel, BlockPos> landing) {}

    public static final Identifier DEFAULT = AlchyX.id("default");
    private static final Map<Identifier, Style> STYLES = new HashMap<>();
    private static final Style FALLBACK = new Style(() -> ParticleTypes.REVERSE_PORTAL, (level, pos) -> {
        BlockState state = level.getBlockState(pos);
        RandomSource random = level.getRandom();
        for (int i = 0; i < 6; i++) {
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + random.nextDouble(), pos.getY() + 0.9,
                    pos.getZ() + random.nextDouble(), 0, 0.05, 0);
        }
    });

    public static void registerStyle(Identifier id, Style style) {
        STYLES.put(id, style);
    }

    private static Style style(Identifier id) {
        return STYLES.getOrDefault(id, FALLBACK);
    }

    private static final class Part {
        final GrowthPayload order;
        int age = 0;

        Part(GrowthPayload order) {
            this.order = order;
        }

        boolean sinking() {
            return order.kind() == GrowthPayload.SINK;
        }

        boolean rising() {
            return order.kind() == GrowthPayload.RISE;
        }

        int length() {
            return rising() ? GrowthPayload.riseTicks(order.distance()) : sinking() ? GrowthPayload.SINK_TICKS : GrowthPayload.FLY_TICKS;
        }
    }

    private record Shown(GrowthPayload order, float age) {}

    private static final ContextKey<List<Shown>> RENDER_KEY = new ContextKey<>(AlchyX.id("growth_parts"));
    private static final List<Part> PARTS = new ArrayList<>();

    /** GrowthPayload's client hook. */
    public static void receive(GrowthPayload payload) {
        PARTS.add(new Part(payload));
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            PARTS.clear();
            return;
        }
        if (minecraft.isPaused()) return;
        RandomSource random = level.getRandom();
        for (Iterator<Part> it = PARTS.iterator(); it.hasNext(); ) {
            Part part = it.next();
            part.age++;
            BlockPos pos = part.order.pos();
            if (part.rising()) { // grinding up out of the stone: dust and grit round its foot
                if (part.age % 4 == 0 && part.age < part.length()) {
                    BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, part.order.look());
                    double side = random.nextDouble() - 0.5;
                    boolean alongX = random.nextBoolean();
                    double px = pos.getX() + 0.5 + (alongX ? side : (random.nextBoolean() ? 0.52 : -0.52));
                    double pz = pos.getZ() + 0.5 + (alongX ? (random.nextBoolean() ? 0.52 : -0.52) : side);
                    level.addParticle(dust, px, pos.getY() + rise(part, part.age) - 0.95, pz, 0, 0.05, 0);
                }
            } else if (part.age % 3 == 0) { // a thin trail behind it
                ParticleOptions trail = style(part.order.style()).trail().get();
                if (trail != null) {
                    Vec3 at = flight(part.order, part.age / (float) part.length());
                    level.addParticle(trail, at.x, at.y, at.z, 0, 0, 0);
                }
            }
            if (part.age >= part.length() && (!part.rising() || arrived(level, part) || part.age > part.length() + 2400)) {
                if (!part.sinking()) land(level, part);
                it.remove();
            }
        }
    }

    /** The part comes to rest: its shell is shown, the style's puff. */
    private static void land(ClientLevel level, Part part) {
        BlockPos pos = part.order.pos();
        if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell && shell.isClientHidden()) shell.setClientHidden(false);
        if (!part.rising()) style(part.order.style()).landing().accept(level, pos);
    }

    /** A risen part stays drawn until the real block is there to take over (a shell, or the very block). */
    private static boolean arrived(ClientLevel level, Part part) {
        BlockState there = level.getBlockState(part.order.pos());
        return there.equals(part.order.look()) || ShellParts.isShell(there);
    }

    /** Where a rising part's bottom is against its place: -distance + 1 at the start, 1 there. */
    private static float rise(Part part, float age) {
        return Math.max(1, part.order.distance()) * (ease(Math.min(1, age / part.length())) - 1) + 1;
    }

    /** Where a part is along its way (the middle of the block): an arc between the source and its place. */
    private static Vec3 flight(GrowthPayload order, float t) {
        t = Mth.clamp(t, 0, 1);
        boolean sinking = order.kind() == GrowthPayload.SINK;
        float k = sinking ? 1 - ease(t) : 1 - (1 - t) * (1 - t); // out fast, slowing as it lands
        Vec3 from = Vec3.atCenterOf(order.source()), to = Vec3.atCenterOf(order.pos());
        Vec3 control = from.add(to).scale(0.5).add(0, 2.0, 0); // a little arc over the straight line
        double u = 1 - k;
        return from.scale(u * u).add(control.scale(2 * u * k)).add(to.scale(k * k));
    }

    private static float ease(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static void onExtract(ExtractLevelRenderStateEvent event) {
        if (PARTS.isEmpty()) {
            event.getRenderState().setRenderData(RENDER_KEY, null);
            return;
        }
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<Shown> shown = new ArrayList<>();
        for (Part part : PARTS) shown.add(new Shown(part.order, part.age + partial));
        event.getRenderState().setRenderData(RENDER_KEY, shown);
    }

    /** A moving block lit full bright, whatever the light where it ends up. */
    private static final class Lit extends MovingBlockRenderState {
        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }
    }

    public static void onSubmit(SubmitCustomGeometryEvent event) {
        List<Shown> shown = event.getLevelRenderState().getRenderData(RENDER_KEY);
        Minecraft minecraft = Minecraft.getInstance();
        if (shown == null || minecraft.level == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        for (Shown part : shown) {
            GrowthPayload order = part.order();
            BlockPos pos = order.pos();
            if (order.kind() == GrowthPayload.RISE) { // slid straight up out of the ground, lit as its place
                int d = Math.max(1, order.distance());
                float up = d * ease(Math.min(1, part.age() / GrowthPayload.riseTicks(d)));
                poseStack.pushPose();
                poseStack.translate(pos.getX() - camera.x, pos.getY() - d + up - camera.y, pos.getZ() - camera.z);
                MovingBlockRenderState state = new MovingBlockRenderState();
                state.randomSeedPos = pos;
                state.blockPos = pos;
                state.blockState = order.look();
                state.biome = minecraft.level.getBiome(pos);
                state.cardinalLighting = minecraft.level.cardinalLighting();
                state.lightEngine = minecraft.level.getLightEngine();
                collector.submitMovingBlock(poseStack, state);
                poseStack.popPose();
                continue;
            }
            boolean sinking = order.kind() == GrowthPayload.SINK;
            float t = Mth.clamp(part.age() / (sinking ? GrowthPayload.SINK_TICKS : GrowthPayload.FLY_TICKS), 0, 1);
            Vec3 at = flight(order, t);
            float grown = sinking ? 1 - ease(t) : ease(t);
            float scale = 0.3F + 0.7F * grown;
            poseStack.pushPose();
            poseStack.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            poseStack.mulPose(Axis.YP.rotationDegrees((1 - grown) * 270F)); // turning on the way, square when it lands
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5, -0.5, -0.5);
            MovingBlockRenderState state = new Lit();
            state.randomSeedPos = pos;
            state.blockPos = pos;
            state.blockState = order.look();
            state.biome = minecraft.level.getBiome(pos);
            state.cardinalLighting = minecraft.level.cardinalLighting();
            collector.submitMovingBlock(poseStack, state);
            poseStack.popPose();
        }
    }
}
