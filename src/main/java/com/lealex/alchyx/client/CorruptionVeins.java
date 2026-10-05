package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.upgrade.UpgradeStorage;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Corruption rifts: lone tears in the middle of block faces of a machine's structure while it holds upgrades, the End
 * showing through them (nothing is placed in the world). More installed tiers (corruption / max) = more rifts. Each
 * rift lives in cycles: a dark crack fades in, tears open, stays open a while, closes back to a crack and fades
 * away, then opens again somewhere else on the structure. Each is one decal (4 variants x 8 opening stages, blended, turned
 * and mirrored) in three layers: the block breaking around it (lit by the world), the tear (End portal render type)
 * and its glowing lips, pulsing slowly from one installed upgrade's color to the next (UpgradeType.color).
 * <p>
 * Only full, visible faces (of the block each cell looks like, a shell's disguise) get one: not air, invisible or
 * hidden cells, not faces against an opaque neighbor. The faces are listed when the corruption changes (or every
 * 10 s); each frame only picks each rift's face and stage and emits a few quads per rift.
 * Textures: textures/misc/corruption_rift_(rim|glow|void).png (columns = variants, rows = stages 0 closed .. 7 open),
 * made by AlchyMastery tools/rift_textures.py.
 */
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public final class CorruptionVeins {
    private CorruptionVeins() {}

    private static final Identifier RIM = AlchyX.id("textures/misc/corruption_rift_rim.png");
    private static final Identifier GLOW = AlchyX.id("textures/misc/corruption_rift_glow.png");
    private static final Identifier VOID = AlchyX.id("textures/misc/corruption_rift_void.png");
    private static final int VARIANTS = 4, STAGES = 8;
    private static final ContextKey<List<Shown>> KEY = new ContextKey<>(AlchyX.id("corruption_rifts"));
    private static final double RANGE = 48;
    private static final float TICKS_PER_COLOR = 60;        // the glow moves to the next upgrade's color every 3 s
    private static final int FACES_PER_RIFT = 5;            // at max corruption, about one rift per this many faces
    private static final int MIN_RIFTS = 3, MAX_RIFTS = 20; // ... within these bounds
    private static final int MIN_CYCLE = 260, CYCLE_SPREAD = 140; // ticks a rift lives before moving elsewhere
    private static final int FADE = 20, STAGE_TICKS = 4, CRACK_TICKS = 14; // fade in/out, per opening stage, as a crack
    private static final float RIM_LIFT = 0.003F, VOID_LIFT = 0.004F, GLOW_LIFT = 0.005F;

    /** A face that can hold a rift, and the world light on it. */
    private record Spot(BlockPos pos, Direction side, long hash, int light) {}

    /** One rift right now: its face, decal variant, quarter turns, mirrored, opening stage. */
    private record Rift(BlockPos pos, Direction side, int variant, int turns, boolean mirror, int stage, int light) {}

    private record Cache(int corruption, long builtAt, List<Spot> spots, int count) {}

    /**
     * A rift to draw: its glow color (alpha = breath x fade), its fade (0-255, for the broken block around it), and
     * how far it is from its stage to the next (0-1): the two are blended, so it opens and closes smoothly.
     */
    private record Shown(Rift rift, int color, int fade, float blend) {}

    private static final Map<MultiblockCoreBlockEntity, Cache> CACHE = new WeakHashMap<>();

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        ClientLevel level = event.getLevel();
        Vec3 camera = event.getCamera().position();
        float time = level.getGameTime() + event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<Shown> shown = new ArrayList<>();
        for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
            if (core.isRemoved() || core.getLevel() != level || !core.isFormed()) continue;
            UpgradeStorage upgrades = core.getUpgrades();
            if (upgrades == null) continue;
            int corruption = upgrades.corruption();
            if (corruption <= 0) continue;
            if (Vec3.atCenterOf(core.getBlockPos()).distanceToSqr(camera) > RANGE * RANGE) continue;

            Cache cache = CACHE.get(core);
            if (cache == null || cache.corruption != corruption || level.getGameTime() - cache.builtAt > 200) {
                float ratio = Mth.clamp((corruption - 1F) / Math.max(1, upgrades.maxCorruption() - 1), 0, 1);
                List<Spot> spots = spots(level, core);
                int most = Mth.clamp(Math.round(spots.size() / (float) FACES_PER_RIFT), MIN_RIFTS, MAX_RIFTS);
                int count = Math.min(spots.size(), Math.round(Mth.lerp(ratio, 1, most)));
                cache = new Cache(corruption, level.getGameTime(), spots, count);
                CACHE.put(core, cache);
            }
            if (cache.spots.isEmpty()) continue;
            int rgb = color(upgrades.installedColors(), time);
            long salt = core.getBlockPos().asLong();
            java.util.Set<Spot> taken = new java.util.HashSet<>();
            for (int slot = 0; slot < cache.count; slot++) {
                long id = mix(salt * 31 + slot);
                int length = MIN_CYCLE + (int) (id % CYCLE_SPREAD);
                float t = time + (id >>> 8) % length; // each rift starts at its own point of its cycle
                long cycle = (long) Math.floor(t / length);
                float age = t - cycle * length;
                // where it is this cycle: a new face each time
                long pick = mix(id ^ cycle * 0x9E3779B97F4A7C15L);
                Spot spot = cache.spots.get((int) (pick % cache.spots.size()));
                if (!taken.add(spot)) continue; // two rifts on one face: this one rests this cycle
                int stage, fade = 255;
                float blend = 0;
                float closing = length - age;
                if (age < FADE) { // a crack fades in
                    stage = 0;
                    fade = (int) (255 * age / FADE);
                } else if (closing < FADE) { // the crack fades away
                    stage = 0;
                    fade = (int) (255 * closing / FADE);
                } else {
                    float opening = age - FADE - CRACK_TICKS, shutting = closing - FADE - CRACK_TICKS;
                    float stages = Mth.clamp(Math.min(opening, shutting) / STAGE_TICKS, 0, STAGES - 1); // 0: still a crack
                    stage = Math.min((int) stages, STAGES - 1);
                    blend = stage < STAGES - 1 ? stages - stage : 0;
                }
                Rift rift = new Rift(spot.pos, spot.side, (int) ((pick >>> 20) % VARIANTS), (int) (pick >>> 24 & 3),
                        (pick >>> 26 & 1) == 1, stage, spot.light);
                float breath = 0.7F + 0.3F * Mth.sin(time * 0.07F + slot * 1.7F); // each rift breathes on its own beat
                shown.add(new Shown(rift, (int) (255 * breath * fade / 255F) << 24 | rgb, fade, blend));
            }
        }
        event.getRenderState().setRenderData(KEY, shown.isEmpty() ? null : shown);
    }

    /** The color now: one installed upgrade's color, then blending into the next. */
    private static int color(List<Integer> colors, float time) {
        if (colors.isEmpty()) return 0xB98CFF;
        float t = time / TICKS_PER_COLOR;
        int i = Mth.floor(t) % colors.size();
        float blend = Mth.clamp((t - Mth.floor(t) - 0.6F) / 0.4F, 0, 1);
        int a = colors.get(i), b = colors.get((i + 1) % colors.size());
        int r = (int) Mth.lerp(blend, a >> 16 & 0xFF, b >> 16 & 0xFF);
        int g = (int) Mth.lerp(blend, a >> 8 & 0xFF, b >> 8 & 0xFF);
        int bl = (int) Mth.lerp(blend, a & 0xFF, b & 0xFF);
        return r << 16 | g << 8 | bl;
    }

    // ---- Picking the faces ----

    /** What the cell shows (a shell's disguise), or null if it shows nothing (air, invisible, a hidden shell). */
    private static @Nullable BlockState visible(ClientLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell && shell.isHidden()) return null;
        BlockState look = ChamberShellBlock.lookAt(level, pos);
        if (look.isAir() || look.getRenderShape() == RenderShape.INVISIBLE) return null;
        return look;
    }

    /** Every full, visible face of the structure, in a fixed order. */
    private static List<Spot> spots(ClientLevel level, MultiblockCoreBlockEntity core) {
        MultiblockPattern pattern = core.pattern();
        if (pattern == null) return List.of();
        List<Spot> spots = new ArrayList<>();
        for (BlockPos pos : pattern.occupiedPositions(core.getBlockPos(), core.rotation())) {
            BlockState look = visible(level, pos);
            if (look == null) continue;
            List<AABB> boxes = look.getShape(level, pos).toAabbs();
            for (Direction side : Direction.values()) {
                if (!fullFace(boxes, side)) continue;
                BlockPos next = pos.relative(side);
                BlockState neighbor = visible(level, next);
                if (neighbor != null && (neighbor.isSolidRender() || fullFace(neighbor.getShape(level, next).toAabbs(), side.getOpposite()))) continue;
                long hash = mix(pos.asLong() * 0x9E3779B97F4A7C15L + side.ordinal());
                spots.add(new Spot(pos.immutable(), side, hash, LevelRenderer.getLightCoords(level, next)));
            }
        }
        spots.sort(Comparator.comparingLong(Spot::hash));
        return spots;
    }

    /** Whether the shape has a box covering this whole side of the block. */
    private static boolean fullFace(List<AABB> boxes, Direction side) {
        double e = 1.0E-4;
        for (AABB b : boxes) {
            boolean spans = switch (side.getAxis()) {
                case X -> b.minY < e && b.maxY > 1 - e && b.minZ < e && b.maxZ > 1 - e;
                case Y -> b.minX < e && b.maxX > 1 - e && b.minZ < e && b.maxZ > 1 - e;
                case Z -> b.minX < e && b.maxX > 1 - e && b.minY < e && b.maxY > 1 - e;
            };
            boolean onSide = switch (side) {
                case DOWN -> b.minY < e;
                case UP -> b.maxY > 1 - e;
                case NORTH -> b.minZ < e;
                case SOUTH -> b.maxZ > 1 - e;
                case WEST -> b.minX < e;
                case EAST -> b.maxX > 1 - e;
            };
            if (spans && onSide) return true;
        }
        return false;
    }

    private static long mix(long h) {
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h & Long.MAX_VALUE;
    }

    // ---- The void mask (read once) ----

    /** Per variant * STAGES + stage, the torn-open pixels as row runs {y, x0, x1 (exclusive)}; empty if unreadable. */
    private static int @Nullable [][] voidRuns;

    private static int[][] voidRuns() {
        if (voidRuns != null) return voidRuns;
        int[][] runs = new int[VARIANTS * STAGES][0];
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(VOID); NativeImage image = NativeImage.read(in)) {
            for (int tile = 0; tile < VARIANTS * STAGES; tile++) {
                int v = tile / STAGES, st = tile % STAGES;
                List<Integer> out = new ArrayList<>();
                for (int y = 0; y < 16; y++) {
                    int start = -1;
                    for (int x = 0; x <= 16; x++) {
                        boolean open = x < 16 && (image.getPixel(v * 16 + x, st * 16 + y) >>> 24) > 127;
                        if (open && start < 0) start = x;
                        if (!open && start >= 0) {
                            out.add(y);
                            out.add(start);
                            out.add(x);
                            start = -1;
                        }
                    }
                }
                runs[tile] = out.stream().mapToInt(Integer::intValue).toArray();
            }
        } catch (Exception e) {
            AlchyX.LOGGER.warn("Couldn't read {}: corruption rifts drawn without their void", VOID, e);
        }
        voidRuns = runs;
        return runs;
    }

    // ---- Submitting ----

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        List<Shown> shown = event.getLevelRenderState().getRenderData(KEY);
        if (shown == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        int[][] voids = voidRuns();
        event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(RIM), (pose, buffer) -> {
            for (Shown s : shown) blended(pose, buffer, camera, s, s.fade << 24 | 0xFFFFFF, s.rift.light, RIM_LIFT);
        });
        event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.endPortal(), (pose, buffer) -> {
            for (Shown s : shown) {
                if (s.fade < 255) continue; // fading cracks are shut
                int stage = s.blend >= 0.5F ? s.rift.stage + 1 : s.rift.stage; // the tear itself can't fade: nearest stage
                int[] runs = voids[s.rift.variant * STAGES + stage];
                for (int i = 0; i < runs.length; i += 3) tear(pose, buffer, camera, s.rift, runs[i], runs[i + 1], runs[i + 2]);
            }
        });
        event.getSubmitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(GLOW), (pose, buffer) -> {
            for (Shown s : shown) blended(pose, buffer, camera, s, s.color, LightCoordsUtil.FULL_BRIGHT, GLOW_LIFT);
        });
    }

    /** The rift's stage and the next one, each at its share of the alpha. */
    private static void blended(PoseStack.Pose pose, VertexConsumer buffer, Vec3 camera, Shown s, int color, int light, float lift) {
        int alpha = color >>> 24, rgb = color & 0xFFFFFF;
        if (s.blend < 0.99F) decal(pose, buffer, camera, s.rift, s.rift.stage, Math.round(alpha * (1 - s.blend)) << 24 | rgb, light, lift);
        if (s.blend > 0.01F) decal(pose, buffer, camera, s.rift, s.rift.stage + 1, Math.round(alpha * s.blend) << 24 | rgb, light, lift);
    }

    /** The decal over the whole face: tile corners (a right, b up) with their UVs in its variant/stage tile. */
    private static void decal(PoseStack.Pose pose, VertexConsumer buffer, Vec3 camera, Rift rift, int stage, int color, int light, float lift) {
        float u0 = rift.variant / (float) VARIANTS, u1 = (rift.variant + 1) / (float) VARIANTS;
        float v0 = stage / (float) STAGES, v1 = (stage + 1) / (float) STAGES;
        float[][] tile = {{0, 0, u0, v1}, {1, 0, u1, v1}, {1, 1, u1, v0}, {0, 1, u0, v0}};
        float nx = rift.side.getStepX(), ny = rift.side.getStepY(), nz = rift.side.getStepZ();
        for (int k = 0; k < 4; k++) {
            float[] t = tile[order(rift, k)];
            float[] p = place(rift, camera, t[0], t[1], lift);
            buffer.addVertex(pose, p[0], p[1], p[2]).setColor(color).setUv(t[2], t[3])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        }
    }

    /** One run of torn-open pixels (row y from the tile's top, x0..x1) as a quad of starfield. */
    private static void tear(PoseStack.Pose pose, VertexConsumer buffer, Vec3 camera, Rift rift, int y, int x0, int x1) {
        float a0 = x0 / 16F, a1 = x1 / 16F, b0 = 1 - (y + 1) / 16F, b1 = 1 - y / 16F;
        float[][] tile = {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}};
        for (int k = 0; k < 4; k++) {
            float[] t = tile[order(rift, k)];
            float[] p = place(rift, camera, t[0], t[1], VOID_LIFT);
            buffer.addVertex(pose, p[0], p[1], p[2]);
        }
    }

    /**
     * Vertex k's corner, so every quad runs counter-clockwise seen from outside: mirroring flips it, and the
     * top/bottom corner order (see corners) already runs the other way.
     */
    private static int order(Rift rift, int k) {
        boolean reverse = rift.mirror ^ (rift.side.getAxis() == Direction.Axis.Y);
        return reverse ? (4 - k) % 4 : k;
    }

    /** A point of the tile (a right, b up), turned and mirrored, on the rift's face, camera-relative. */
    private static float[] place(Rift rift, Vec3 camera, float a, float b, float lift) {
        if (rift.mirror) a = 1 - a;
        for (int i = 0; i < rift.turns; i++) {
            float t = a;
            a = b;
            b = 1 - t;
        }
        float[][] c = corners(rift.side);
        float[] p = new float[3];
        double[] base = {rift.pos.getX() - camera.x, rift.pos.getY() - camera.y, rift.pos.getZ() - camera.z};
        float[] step = {rift.side.getStepX(), rift.side.getStepY(), rift.side.getStepZ()};
        for (int axis = 0; axis < 3; axis++) {
            p[axis] = (float) (base[axis] + c[0][axis] + (c[1][axis] - c[0][axis]) * a + (c[3][axis] - c[0][axis]) * b + step[axis] * lift);
        }
        return p;
    }

    /** The side's 4 corners on the unit block: bottom-left, bottom-right, top-right, top-left. */
    private static float[][] corners(Direction side) {
        return switch (side) {
            case DOWN -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}};
            case UP -> new float[][]{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
            case NORTH -> new float[][]{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}};
            case SOUTH -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}};
            case WEST -> new float[][]{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}};
            case EAST -> new float[][]{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}};
        };
    }
}
