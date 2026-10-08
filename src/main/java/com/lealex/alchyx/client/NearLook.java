package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

/**
 * Blocks that take another look where the player comes close: a wall turning into a rift, stone waking up, a
 * hidden door showing itself. The blocks never change. The other look is painted over their faces around the
 * player, fully there within arm's reach and fading out smoothly a few blocks away, so it follows the player with
 * no delay. Only a picture: what the blocks do is their own business.
 *
 * Two kinds of other look, registered once during client setup:
 * <pre>
 * // another block's texture, face by face
 * NearLook.register(NearLook.Rule.texture(
 *         level -> level.dimension() == MY_DIMENSION,        // where it applies (checked every tick: keep it cheap)
 *         state -> state.is(MY_WALL),                        // the blocks that change
 *         () -> MY_OTHER.get().defaultBlockState(),          // the look they take (that block's texture, animated or not)
 *         1.5F, 5.0F));                                      // all the other look within 1.5 blocks, none beyond 5
 *
 * // one whirl spread over the blocks, its middle right in front of the player's eyes and following them (see Vortex)
 * NearLook.register(NearLook.Rule.vortex(where, on, List.of(new Vortex.Layer(THROAT, 0.4F, 1), new Vortex.Layer(ARMS, 3, 1)), 1.5F, 5.0F, false));
 *
 * // a tear that opens from the point right in front of the player's eyes, wider the closer they stand (see Tear)
 * NearLook.register(NearLook.Rule.tear(where, on, List.of(CRACK, TORN, GAPING), 1.5F, 5.0F, 3.0F, true));
 * </pre>
 */
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public final class NearLook {
    private NearLook() {}

    /**
     * @param where  which worlds it applies in
     * @param on     the blocks that take the other look
     * @param shows  texture kind: the block whose texture is painted over them (its particle texture: for a plain
     *               cube, its texture); null for a whirl
     * @param layers whirl kind: its layers, the first at the back; empty otherwise
     * @param stages tear kind: its stages, closed to open; empty otherwise
     * @param full   blocks from the player within which the other look is complete (a tear: fully open)
     * @param none   blocks from the player beyond which nothing shows (at most {@link #MAX_REACH}); a whirl is this
     *               wide; a tear starts to crack at this distance from the block's face
     * @param size   tear kind: half the width of its pictures, in blocks
     * @param unstable whirl and tear kinds: it has fits (see {@link Vortex#shake}, {@link Tear#unstable})
     */
    public record Rule(Predicate<ClientLevel> where, Predicate<BlockState> on, @Nullable Supplier<BlockState> shows,
                       List<Vortex.Layer> layers, List<Tear.Stage> stages, float full, float none, float size, boolean unstable) {
        public static Rule texture(Predicate<ClientLevel> where, Predicate<BlockState> on, Supplier<BlockState> shows, float full, float none) {
            return new Rule(where, on, shows, List.of(), List.of(), full, none, 0, false);
        }

        public static Rule tear(Predicate<ClientLevel> where, Predicate<BlockState> on, List<Tear.Stage> stages, float full, float none,
                                float size, boolean unstable) {
            return new Rule(where, on, null, List.of(), List.copyOf(stages), full, none, size, unstable);
        }

        public static Rule vortex(Predicate<ClientLevel> where, Predicate<BlockState> on, List<Vortex.Layer> layers, float full, float none,
                                  boolean unstable) {
            return new Rule(where, on, null, List.copyOf(layers), List.of(), full, none, 0, unstable);
        }
    }

    public static final int MAX_REACH = 8;
    private static final int SCAN_EVERY = 2;
    /** How far the picture floats off the block, so the two never fight. */
    private static final float LIFT = 0.003F;

    /** One visible face of a block near the player. */
    private record Face(Rule rule, BlockPos pos, Direction side) {}

    /**
     * A face ready to draw: 4 corners (x, y, z) in the world, their opacity, and either its texture or, for a whirl,
     * where each corner is from the whirl's middle (a, b). For a tear, how open it is on this face.
     */
    private record Quad(Rule rule, float[] corners, float[] alpha, Direction side, @Nullable TextureAtlasSprite sprite, float[] plane,
                        float open) {}

    private record Frame(List<Quad> quads, float time) {}

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();
    private static final ContextKey<Frame> RENDER_KEY = new ContextKey<>(AlchyX.id("near_look"));
    private static List<Face> faces = List.of();
    private static int ticks;

    public static void register(Rule rule) {
        RULES.add(rule);
    }

    /** Every other tick: which faces of which rules are around the player. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (RULES.isEmpty() || level == null || player == null) {
            faces = List.of();
            return;
        }
        List<Rule> active = new ArrayList<>();
        int reach = 0;
        for (Rule rule : RULES) {
            if (!rule.where().test(level)) continue;
            active.add(rule);
            reach = Math.max(reach, Math.min(MAX_REACH, Mth.ceil(Math.max(rule.none(), rule.size())) + 1));
        }
        if (active.isEmpty()) {
            faces = List.of();
            return;
        }
        if (++ticks % SCAN_EVERY != 0) return;
        List<Face> found = new ArrayList<>();
        BlockPos center = BlockPos.containing(player.getX(), player.getY() + 0.9, player.getZ());
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-reach, -reach, -reach), center.offset(reach, reach, reach))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            for (Rule rule : active) {
                if (!rule.on().test(state)) continue;
                for (Direction side : Direction.values()) {
                    BlockState beside = level.getBlockState(next.setWithOffset(pos, side));
                    if (beside.isSolidRender() || rule.on().test(beside)) continue; // a face nobody sees
                    found.add(new Face(rule, pos.immutable(), side));
                }
                break;
            }
        }
        faces = found;
    }

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        event.getRenderState().setRenderData(RENDER_KEY, null);
        if (faces.isEmpty() || player == null || minecraft.level == null) return;
        float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 body = player.getEyePosition(partialTicks); // distances and a whirl's middle are measured from the eyes
        float time = minecraft.level.getGameTime() % 24000L + partialTicks;
        // The other look breathes a little
        float breath = 0.88F + 0.12F * Mth.sin(time * 0.15F);
        List<Quad> quads = new ArrayList<>();
        Rule spriteOf = null;
        TextureAtlasSprite sprite = null;
        for (Face face : faces) {
            Rule rule = face.rule();
            float[] corners = corners(face);
            float[] alpha = new float[4];
            float[] plane = new float[8];
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                float dx = (float) (corners[i * 3] - body.x), dy = (float) (corners[i * 3 + 1] - body.y), dz = (float) (corners[i * 3 + 2] - body.z);
                float span = Math.max(0.01F, rule.none() - rule.full());
                float near = 1 - Mth.clamp((Mth.sqrt(dx * dx + dy * dy + dz * dz) - rule.full()) / span, 0.0F, 1.0F);
                alpha[i] = near * near * (3 - 2 * near) * breath;
                any |= alpha[i] > 0.004F;
                // Across the face, from the point of its plane right in front of the player (a whirl's middle)
                switch (face.side().getAxis()) {
                    case X -> { plane[i * 2] = dz * face.side().getStepX(); plane[i * 2 + 1] = dy; }
                    case Z -> { plane[i * 2] = -dx * face.side().getStepZ(); plane[i * 2 + 1] = dy; }
                    default -> { plane[i * 2] = dx; plane[i * 2 + 1] = dz * face.side().getStepY(); }
                }
            }
            if (!rule.stages().isEmpty()) {
                // A tear: how open depends on how far the eyes are from this face's plane, and it only shows within
                // its picture, on faces turned toward the eyes
                Direction side = face.side();
                double away = (corners[0] - body.x) * side.getStepX() + (corners[1] - body.y) * side.getStepY() + (corners[2] - body.z) * side.getStepZ();
                if (away >= 0) continue;
                float open = 1 - Mth.clamp(((float) -away - rule.full()) / Math.max(0.01F, rule.none() - rule.full()), 0.0F, 1.0F);
                if (open <= 0) continue;
                any = false;
                for (int i = 0; i < 4; i++) {
                    float reach = Mth.sqrt(plane[i * 2] * plane[i * 2] + plane[i * 2 + 1] * plane[i * 2 + 1]);
                    alpha[i] = Mth.clamp((rule.size() - reach) / (0.15F * rule.size()), 0.0F, 1.0F);
                    any |= alpha[i] > 0.004F;
                }
                if (any) quads.add(new Quad(rule, corners, alpha, side, null, plane, open));
                continue;
            }
            if (!any) continue;
            if (!rule.layers().isEmpty()) {
                quads.add(new Quad(rule, corners, alpha, face.side(), null, plane, 0));
                continue;
            }
            if (rule != spriteOf) {
                spriteOf = rule;
                try {
                    sprite = rule.shows() == null ? null
                            : minecraft.getModelManager().getBlockStateModelSet().getParticleMaterial(rule.shows().get()).sprite();
                } catch (Exception e) {
                    sprite = null;
                }
            }
            if (sprite != null) quads.add(new Quad(rule, corners, alpha, face.side(), sprite, plane, 0));
        }
        if (!quads.isEmpty()) event.getRenderState().setRenderData(RENDER_KEY, new Frame(quads, time));
    }

    /** The 4 corners of a block's face, lifted a hair off it, counter-clockwise seen from outside. */
    private static float[] corners(Face face) {
        float x0 = face.pos().getX(), y0 = face.pos().getY(), z0 = face.pos().getZ(), x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        return switch (face.side()) {
            case DOWN -> new float[] {x0, y0 - LIFT, z0, x1, y0 - LIFT, z0, x1, y0 - LIFT, z1, x0, y0 - LIFT, z1};
            case UP -> new float[] {x0, y1 + LIFT, z1, x1, y1 + LIFT, z1, x1, y1 + LIFT, z0, x0, y1 + LIFT, z0};
            case NORTH -> new float[] {x1, y0, z0 - LIFT, x0, y0, z0 - LIFT, x0, y1, z0 - LIFT, x1, y1, z0 - LIFT};
            case SOUTH -> new float[] {x0, y0, z1 + LIFT, x1, y0, z1 + LIFT, x1, y1, z1 + LIFT, x0, y1, z1 + LIFT};
            case WEST -> new float[] {x0 - LIFT, y0, z0, x0 - LIFT, y0, z1, x0 - LIFT, y1, z1, x0 - LIFT, y1, z0};
            case EAST -> new float[] {x1 + LIFT, y0, z1, x1 + LIFT, y0, z0, x1 + LIFT, y1, z0, x1 + LIFT, y1, z1};
        };
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        Frame frame = event.getLevelRenderState().getRenderData(RENDER_KEY);
        if (frame == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        poseStack.pushPose();
        // Corners are world positions: drawn from the camera's block corner so the numbers stay small and exact
        double baseX = Math.floor(camera.x), baseY = Math.floor(camera.y), baseZ = Math.floor(camera.z);
        poseStack.translate(baseX - camera.x, baseY - camera.y, baseZ - camera.z);
        List<Quad> quads = frame.quads();

        // Textures: one batch (block textures all share one sheet)
        TextureAtlasSprite first = null;
        for (Quad quad : quads) {
            if (quad.sprite() != null) {
                first = quad.sprite();
                break;
            }
        }
        if (first != null) {
            Identifier sheet = first.atlasLocation();
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(sheet), (pose, buffer) -> {
                for (Quad quad : quads) {
                    TextureAtlasSprite sprite = quad.sprite();
                    if (sprite == null || !sprite.atlasLocation().equals(sheet)) continue;
                    float[] u = {sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()};
                    float[] v = {sprite.getV1(), sprite.getV1(), sprite.getV0(), sprite.getV0()};
                    emit(pose, buffer, quad, 0, baseX, baseY, baseZ, u, v, 1);
                }
            });
        }

        // Whirls: one batch per rule and layer, the first layer at the back
        List<Rule> done = new ArrayList<>();
        for (Quad start : quads) {
            Rule rule = start.rule();
            if (rule.layers().isEmpty() || done.contains(rule)) continue;
            done.add(rule);
            for (int index = 0; index < rule.layers().size(); index++) {
                Vortex.Layer layer = rule.layers().get(index);
                Vortex.Shake shake = rule.unstable() ? Vortex.shake(frame.time(), index) : new Vortex.Shake(frame.time(), 1, 0, 0);
                float radius = rule.none();
                int lift = index;
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(layer.texture()), (pose, buffer) -> {
                    float[] u = new float[4], v = new float[4];
                    for (Quad quad : quads) {
                        if (quad.rule() != rule) continue;
                        for (int i = 0; i < 4; i++) {
                            float[] uv = Vortex.uv(layer, shake.time(), radius * shake.scale(),
                                    quad.plane()[i * 2] - shake.a() * radius, quad.plane()[i * 2 + 1] - shake.b() * radius);
                            u[i] = uv[0];
                            v[i] = uv[1];
                        }
                        emit(pose, buffer, quad, lift, baseX, baseY, baseZ, u, v, 1);
                    }
                });
            }
        }

        // Tears: one batch per rule and picture; each face shows the one picture that goes with its hole
        for (Quad start : quads) {
            Rule rule = start.rule();
            if (rule.stages().isEmpty() || done.contains(rule)) continue;
            done.add(rule);
            Vortex.Shake shake = rule.unstable() ? Vortex.shake(frame.time(), 0) : new Vortex.Shake(frame.time(), 1, 0, 0);
            float span = 2 * rule.size(), slipA = shake.a() * rule.size(), slipB = shake.b() * rule.size();
            int count = rule.stages().size();

            // Through the hole, the End's starfield: once per wall (every face of a wall shares its tear), as
            // strips in the wall's plane around the point in front of the eyes
            List<Quad> walls = new ArrayList<>();
            for (Quad quad : quads) {
                if (quad.rule() != rule) continue;
                boolean known = false;
                for (Quad wall : walls) {
                    known |= wall.side() == quad.side() && Math.abs(across(wall) - across(quad)) < 0.01F;
                }
                if (!known) walls.add(quad);
            }
            collector.submitCustomGeometry(poseStack, RenderTypes.endPortal(), (pose, buffer) -> {
                for (Quad wall : walls) {
                    float open = rule.unstable() ? Tear.unstable(wall.open(), frame.time()) : wall.open();
                    float[] strips = Tear.hole(Tear.holeStage(rule.stages(), open));
                    for (int i = 0; i < strips.length; i += 4) {
                        float a0 = (strips[i] * 2 - 1) * rule.size() + slipA, a1 = (strips[i + 1] * 2 - 1) * rule.size() + slipA;
                        float b1 = (1 - strips[i + 2] * 2) * rule.size() + slipB, b0 = (1 - strips[i + 3] * 2) * rule.size() + slipB;
                        float[][] q = {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}};
                        for (int pass = 0; pass < 2; pass++) {
                            for (int k = 0; k < 4; k++) {
                                float[] c = q[pass == 0 ? k : 3 - k];
                                float[] at = inWorld(wall, c[0], c[1]);
                                buffer.addVertex(pose, (float) (at[0] - baseX), (float) (at[1] - baseY), (float) (at[2] - baseZ));
                            }
                        }
                    }
                }
            });

            for (int index = 0; index < count; index++) {
                int picture = index;
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(rule.stages().get(index).picture()), (pose, buffer) -> {
                    float[] u = new float[4], v = new float[4];
                    for (Quad quad : quads) {
                        if (quad.rule() != rule) continue;
                        float open = rule.unstable() ? Tear.unstable(quad.open(), frame.time()) : quad.open();
                        if (Tear.holeStage(rule.stages(), open) != rule.stages().get(picture)) continue;
                        float share = 1;
                        for (int i = 0; i < 4; i++) {
                            u[i] = 0.5F + (quad.plane()[i * 2] - slipA) / span;
                            v[i] = 0.5F - (quad.plane()[i * 2 + 1] - slipB) / span;
                        }
                        emit(pose, buffer, quad, picture + 1, baseX, baseY, baseZ, u, v, share);
                    }
                });
            }
        }
        poseStack.popPose();
    }

    /** Where a face's plane stands along its own axis (its first corner's coordinate there). */
    private static float across(Quad quad) {
        return quad.corners()[quad.side().getAxis().ordinal()];
    }

    /**
     * A point of a face's plane, given from the point in front of the eyes (a, b: the same measure as a quad's
     * {@code plane}), as a world position {x, y, z}.
     */
    private static float[] inWorld(Quad quad, float a, float b) {
        float[] c = quad.corners(), p = quad.plane();
        Direction side = quad.side();
        return switch (side.getAxis()) {
            case X -> new float[] {c[0], c[1] - p[1] + b, c[2] + (a - p[0]) * side.getStepX()};
            case Z -> new float[] {c[0] - (a - p[0]) * side.getStepZ(), c[1] - p[1] + b, c[2]};
            default -> new float[] {c[0] + a - p[0], c[1], c[2] + (b - p[1]) * side.getStepY()};
        };
    }

    private static void emit(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, Quad quad, int lift,
                             double baseX, double baseY, double baseZ, float[] u, float[] v, float share) {
        Direction side = quad.side();
        float out = lift * 0.002F;
        for (int pass = 0; pass < 2; pass++) { // both windings: seen whatever the culling
            for (int k = 0; k < 4; k++) {
                int i = pass == 0 ? k : 3 - k;
                int color = ((int) (quad.alpha()[i] * share * 255) << 24) | 0xFFFFFF;
                buffer.addVertex(pose, (float) (quad.corners()[i * 3] - baseX) + out * side.getStepX(),
                                (float) (quad.corners()[i * 3 + 1] - baseY) + out * side.getStepY(),
                                (float) (quad.corners()[i * 3 + 2] - baseZ) + out * side.getStepZ())
                        .setColor(color).setUv(u[i], v[i]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, side.getStepX(), side.getStepY(), side.getStepZ());
            }
        }
    }
}
