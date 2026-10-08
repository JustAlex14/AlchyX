package com.lealex.alchyx.client.miniature;

import com.lealex.alchyx.miniature.BlockBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A {@link BlockBox}'s picture, worked out once: every block face that can be seen (not against another full block,
 * not under the box), with its place, texture corner, color and shading already settled, sorted into the three ways
 * blocks are drawn. Drawing it is then only a matter of pushing those numbers through the current position, with no
 * model lookup, no lighting math and no hidden faces. Build it again only when the box changes: a fixed miniature is
 * built once and never again.
 *
 * A trimmed box's outer layer is left out. Blocks with an empty model (chests...) are kept aside as items. Flat
 * shading (no soft corner shadows), one light value for the whole picture.
 *
 * Draw it with {@link Miniatures#submit}.
 */
public final class BoxMesh {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<BlockState, Boolean> HAS_GEOMETRY = new HashMap<>();

    public final Layer solid = new Layer();
    public final Layer cutout = new Layer();
    public final Layer translucent = new Layer();
    public final List<ItemCell> items = new ArrayList<>();
    private int faces;
    private final int builtFor = reloads;

    private BoxMesh() {}

    /** Counts the times the game's models and textures were reloaded (F3+T, a resource pack change). */
    private static volatile int reloads;

    /** The game's models were reloaded: every mesh built before is stale. */
    public static void modelsReloaded() {
        reloads++;
        HAS_GEOMETRY.clear();
    }

    /**
     * Whether this mesh was built before the last reload of the game's models: its texture corners point into the
     * old texture sheet, so it must be built again. Whoever keeps a mesh checks this before drawing it.
     */
    public boolean isStale() {
        return builtFor != reloads;
    }

    /** A block shown as its item, at its place counted from the box's lowest corner, turned by {@code turn} degrees. */
    public record ItemCell(int x, int y, int z, ItemStackRenderState item, float turn) {}

    public static void clearCaches() {
        HAS_GEOMETRY.clear();
    }

    /** Faces drawn the same way. Per vertex: x, y, z, u, v and a color. Per face: its side and its own glow. */
    public static final class Layer {
        private final FloatArrayList vertices = new FloatArrayList();
        private final IntArrayList colors = new IntArrayList();
        private final IntArrayList sides = new IntArrayList();
        private final IntArrayList emissions = new IntArrayList();

        public boolean isEmpty() {
            return sides.isEmpty();
        }

        /** Pushes every face through {@code pose} into {@code buffer}, lit with {@code light}. */
        public void emit(PoseStack.Pose pose, VertexConsumer buffer, int light) {
            Matrix4f matrix = pose.pose();
            float[] vertex = vertices.elements();
            int[] color = colors.elements();
            Vector3f[] normals = new Vector3f[DIRECTIONS.length];
            for (Direction direction : DIRECTIONS) {
                normals[direction.ordinal()] = pose.transformNormal(direction.getUnitVec3f(), new Vector3f());
            }
            int count = sides.size();
            for (int face = 0; face < count; face++) {
                Vector3f normal = normals[sides.getInt(face)];
                int emission = emissions.getInt(face);
                int faceLight = emission == 0 ? light : LightCoordsUtil.lightCoordsWithEmission(light, emission);
                for (int corner = 0; corner < 4; corner++) {
                    int i = (face * 4 + corner) * 5;
                    float x = vertex[i], y = vertex[i + 1], z = vertex[i + 2];
                    buffer.addVertex(
                            matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30(),
                            matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31(),
                            matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32(),
                            color[face * 4 + corner], vertex[i + 3], vertex[i + 4], OverlayTexture.NO_OVERLAY, faceLight,
                            normal.x(), normal.y(), normal.z());
                }
            }
        }

        private void add(BakedQuad quad, int x, int y, int z, int tint, float shade) {
            for (int corner = 0; corner < 4; corner++) {
                Vector3fc position = quad.position(corner);
                long uv = quad.packedUV(corner);
                vertices.add(position.x() + x);
                vertices.add(position.y() + y);
                vertices.add(position.z() + z);
                vertices.add(UVPair.unpackU(uv));
                vertices.add(UVPair.unpackV(uv));
                int color = ARGB.multiply(tint, quad.bakedColors().color(corner));
                colors.add(ARGB.color(ARGB.alpha(color), (int) (ARGB.red(color) * shade), (int) (ARGB.green(color) * shade), (int) (ARGB.blue(color) * shade)));
            }
            sides.add(quad.direction().ordinal());
            emissions.add(quad.materialInfo().lightEmission());
        }
    }

    /**
     * Gives some blocks a color of their own (a cauldron of purple fluid): an ARGB tint for the faces the block
     * tints itself (the water, not the iron), or 0 to leave the block's own color.
     */
    @FunctionalInterface
    public interface Tints {
        int tint(BlockState state, int x, int y, int z);
    }

    /**
     * Works out a box's picture. {@code biomeAt} gives the biome for grass and leaf colors; faces beyond
     * {@code maxFaces} are dropped, the lowest blocks first.
     */
    public static BoxMesh build(ClientLevel level, BlockPos biomeAt, BlockBox box, int maxFaces) {
        return build(level, biomeAt, box, maxFaces, null);
    }

    /** As {@link #build(ClientLevel, BlockPos, BlockBox, int)}, with colors of the caller's own for some blocks. */
    public static BoxMesh build(ClientLevel level, BlockPos biomeAt, BlockBox box, int maxFaces, @Nullable Tints tints) {
        BoxMesh mesh = new BoxMesh();
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        CardinalLighting shading = level.cardinalLighting();
        // What tint sources read the biome from
        MovingBlockRenderState tintGetter = new MovingBlockRenderState();
        tintGetter.biome = level.getBiome(biomeAt);
        RandomSource random = RandomSource.create(0L);
        List<BlockStateModelPart> parts = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Top down, so a cut-off picture loses what lies low, not what stands tall
        int edge = box.trimmed ? 1 : 0; // a trimmed box's outer layer is left out
        for (int y = box.sizeY - 1 - edge; y >= 0 && mesh.faces < maxFaces; y--) {
            for (int x = edge; x < box.sizeX - edge; x++) {
                for (int z = edge; z < box.sizeZ - edge; z++) {
                    BlockState state = box.get(x, y, z);
                    if (state.isAir()) continue;
                    if (state.getRenderShape() != RenderShape.MODEL || !hasGeometry(models, state)) {
                        ItemCell cell = itemCell(level, state, x, y, z);
                        if (cell != null) mesh.items.add(cell);
                        continue;
                    }
                    pos.set(x, y, z);
                    int own = tints == null ? 0 : tints.tint(state, x, y, z);
                    random.setSeed(state.getSeed(pos));
                    parts.clear();
                    models.get(state).collectParts(random, parts);
                    for (BlockStateModelPart part : parts) {
                        for (Direction direction : DIRECTIONS) {
                            List<BakedQuad> quads = part.getQuads(direction);
                            if (quads.isEmpty() || hidden(box, state, x, y, z, direction)) continue;
                            mesh.add(quads, state, tintGetter, pos, shading, own);
                        }
                        mesh.add(part.getQuads(null), state, tintGetter, pos, shading, own);
                    }
                }
            }
        }
        return mesh;
    }

    private void add(List<BakedQuad> quads, BlockState state, MovingBlockRenderState tintGetter, BlockPos pos, CardinalLighting shading, int own) {
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tint = -1;
            if (material.isTinted()) {
                if (own != 0) {
                    tint = own;
                } else {
                    BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, material.tintIndex());
                    if (source != null) tint = ARGB.opaque(source.colorInWorld(state, tintGetter, pos));
                }
            }
            float shade = material.shade() ? shading.byFace(quad.direction()) : 1.0F;
            Layer layer = switch (material.layer()) {
                case SOLID -> solid;
                case CUTOUT -> cutout;
                case TRANSLUCENT -> translucent;
            };
            layer.add(quad, pos.getX(), pos.getY(), pos.getZ(), tint, shade);
            faces++;
        }
    }

    /** Whether the face of a block toward {@code direction} can't be seen: a full block stands there, or its own kind (glass against glass). */
    private static boolean hidden(BlockBox box, BlockState state, int x, int y, int z, Direction direction) {
        int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
        if (ny < 0) return true; // under the box
        // A trimmed box's outer layer is left out of the picture, so nothing hides what touches it
        if (box.trimmed && (nx <= 0 || nz <= 0 || nx >= box.sizeX - 1 || nz >= box.sizeZ - 1 || ny >= box.sizeY - 1)) return false;
        BlockState neighbor = box.get(nx, ny, nz);
        return neighbor.isSolidRender() || state.skipRendering(neighbor, direction);
    }

    /**
     * Whether a block's model draws anything. Chests and the like have a model with no faces at all (26.1 has no
     * "drawn by a renderer" render shape any more), so this is the only way to tell them apart.
     */
    private static boolean hasGeometry(BlockStateModelSet models, BlockState state) {
        return HAS_GEOMETRY.computeIfAbsent(state, s -> {
            List<BlockStateModelPart> parts = new ArrayList<>();
            models.get(s).collectParts(RandomSource.create(42L), parts);
            for (BlockStateModelPart part : parts) {
                if (!part.getQuads(null).isEmpty()) return true;
                for (Direction direction : DIRECTIONS) {
                    if (!part.getQuads(direction).isEmpty()) return true;
                }
            }
            return false;
        });
    }

    /** A block with an empty model, as its item (null for blocks without one: fluids, fire...). */
    private static @Nullable ItemCell itemCell(ClientLevel level, BlockState state, int x, int y, int z) {
        ItemStack stack = new ItemStack(state.getBlock());
        if (stack.isEmpty()) return null;
        ItemStackRenderState item = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(item, stack, ItemDisplayContext.NONE, level, null, 0);
        // Items of blocks with a front (chests, furnaces...) face south: turn them like the block
        float turn = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? -state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot() : 0;
        return new ItemCell(x, y, z, item, turn);
    }
}
