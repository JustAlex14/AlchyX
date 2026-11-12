package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.animation.ClientAnimations;
import com.lealex.alchyx.animation.MiniStructure;
import com.lealex.alchyx.animation.ResolvedAnimation;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.client.miniature.BoxMesh;
import com.lealex.alchyx.client.miniature.Miniatures;
import com.lealex.alchyx.miniature.BlockBox;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a mini structure ({@link MiniStructure}) as a coded figure: its blocks, and its own animation played by a
 * {@link MiniatureAnimation} timed on the machine it stands in. While that machine works, one of its operations
 * plays the mini's "length" ticks, so a faster recipe (a speed upgrade) plays the mini faster too; while it rests,
 * the mini's idle effects play at the usual pace.
 * <p>
 * {@link Figures#get} finds it for any id that names a mini: a mini file, a multiblock, or a machine's own mini.
 */
public final class MiniFigure implements CodedFigure {
    private static final Map<Identifier, MiniFigure> FIGURES = new ConcurrentHashMap<>();
    /** Each owner's minis as they are drawn now, by figure id. */
    private static final Map<Object, Map<Identifier, Player>> PLAYERS = new WeakHashMap<>();
    /** How deep minis inside minis go. */
    private static final int MAX_DEPTH = 2;
    private static int depth = 0;

    private final Identifier id;

    private MiniFigure(Identifier id) {
        this.id = id;
    }

    /** The mini figure of this id, or null if nothing by that id can be shown as a mini. */
    public static @Nullable MiniFigure forId(Identifier id) {
        boolean known = MiniStructure.ownIndex(id) >= 0 || ClientAnimations.mini(id) != null || MultiblockPatterns.client().containsKey(id);
        return known ? FIGURES.computeIfAbsent(id, MiniFigure::new) : null;
    }

    /** What a mini shows: its blocks by cell (from its core cell), its animation, and the ticks one operation plays. */
    public record Def(Map<BlockPos, BlockState> blocks, @Nullable ResolvedAnimation animation, int length) {}

    /** The mini this id names, as seen by this owner (its own minis are the owner's), or null. */
    public static @Nullable Def def(Identifier id, @Nullable Object owner) {
        int own = MiniStructure.ownIndex(id);
        if (own >= 0) {
            List<MiniStructure.Own> list = owner instanceof MultiblockCoreBlockEntity core ? core.ownMinis()
                    : owner instanceof MiniHost host ? host.miniOwn() : List.of();
            if (own >= list.size()) return null;
            MiniStructure.Own mini = list.get(own);
            return new Def(mini.blocks(), mini.motions().isEmpty() ? null : mini.animation(id), MiniStructure.LENGTH);
        }
        MiniStructure.File file = ClientAnimations.mini(id);
        if (file != null) {
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (MiniStructure.Cell cell : file.blocks()) blocks.put(cell.at(), cell.look());
            if (blocks.isEmpty() && file.structure().isPresent()) blocks = formed(file.structure().get());
            ResolvedAnimation animation;
            if (!file.effects().isEmpty()) {
                BlockPos[] box = MiniStructure.box(blocks.keySet());
                animation = new ResolvedAnimation(id, java.util.Optional.empty(), file.anchors(), box[0], box[1], file.effects());
            } else {
                Identifier from = file.animation().orElse(file.structure().orElse(null));
                animation = from == null ? null : ClientAnimations.get(from);
            }
            return blocks.isEmpty() ? null : new Def(blocks, animation, file.length());
        }
        Map<BlockPos, BlockState> blocks = formed(id);
        return blocks.isEmpty() ? null : new Def(blocks, ClientAnimations.get(id), MiniStructure.LENGTH);
    }

    /** A multiblock's formed look, by offset from its core as written. */
    private static Map<BlockPos, BlockState> formed(Identifier structure) {
        MultiblockPattern pattern = MultiblockPatterns.client().get(structure);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        if (pattern == null) return blocks;
        for (MultiblockPattern.LookCell cell : pattern.formedLook()) blocks.put(cell.offset(), cell.state());
        return blocks;
    }

    /** One mini as one owner draws it: its fixed picture and its animation, made again when the mini changes. */
    private static final class Player {
        final Object source;
        final Def def;
        final BlockPos low;
        final Vec3 foot;
        final @Nullable MiniatureAnimation animation;
        @Nullable BoxMesh mesh;

        Player(Object source, Def def) {
            this.source = source;
            this.def = def;
            BlockPos[] box = MiniStructure.box(def.blocks().keySet());
            this.low = box[0];
            this.foot = MiniStructure.foot(def.blocks().keySet());
            this.animation = def.animation() == null ? null
                    : new MiniatureAnimation(def.animation(), def.blocks(), null, Rotation.NONE, MiniatureAnimation.Plays.ALL);
        }

        BoxMesh mesh(ClientLevel level, BlockPos biomeAt) {
            if (mesh == null || mesh.isStale()) {
                BlockPos[] box = MiniStructure.box(def.blocks().keySet());
                Set<BlockPos> apart = animation == null ? Set.of() : animation.changingCells();
                int sx = box[1].getX() - box[0].getX() + 1, sy = box[1].getY() - box[0].getY() + 1, sz = box[1].getZ() - box[0].getZ() + 1;
                mesh = BoxMesh.build(level, biomeAt, BlockBox.of(Math.min(sx, BlockBox.MAX_SIDE), Math.min(sy, BlockBox.MAX_SIDE),
                        Math.min(sz, BlockBox.MAX_SIDE), false, (x, y, z) -> {
                            BlockPos cell = box[0].offset(x, y, z);
                            BlockState look = def.blocks().get(cell);
                            return look == null || apart.contains(cell) ? Blocks.AIR.defaultBlockState() : look;
                        }), 60_000);
            }
            return mesh;
        }
    }

    /** What the mini is timed on: the machine it stands in. */
    private record Timed(@Nullable MultiblockCoreBlockEntity core, @Nullable MiniHost host, float time) implements MiniatureAnimation.State {
        @Override
        public boolean working() {
            return core != null ? core.isAnimationWorking() : host != null && host.miniWorking();
        }

        @Override
        public float progress() {
            return core != null ? core.getAnimationProgress(time) : host != null ? host.miniProgress(time) : 0;
        }

        @Override
        public long operationStart() {
            return core != null ? core.getAnimationOperationStart() : host != null ? host.miniOperationStart() : 0;
        }

        @Override
        public int operationCount() {
            return core != null ? core.getAnimationOperationCount() : host != null ? host.miniOperations() : 0;
        }

        @Override
        public ItemStack display(String name) {
            return core != null ? core.getAnimationDisplay(name) : host != null ? host.miniDisplay(name) : ItemStack.EMPTY;
        }

        @Override
        public int level(String name) {
            return core != null ? core.getAnimationLevel(name) : 0;
        }

        int operationTicks() {
            return core != null ? core.getAnimationOperationTicks() : host != null ? host.miniOperationTicks() : 0;
        }
    }

    @Override
    public void submit(Context context, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || depth >= MAX_DEPTH) return;
        Object owner = context.owner();
        // The same mini as last frame (the same own mini, or the same definitions from the server) keeps its player
        Object source;
        int own = MiniStructure.ownIndex(id);
        if (own >= 0) {
            List<MiniStructure.Own> list = owner instanceof MultiblockCoreBlockEntity core ? core.ownMinis()
                    : owner instanceof MiniHost host ? host.miniOwn() : List.of();
            if (own >= list.size()) return;
            source = list.get(own);
        } else {
            source = ClientAnimations.generation();
        }
        Player player;
        synchronized (PLAYERS) {
            Map<Identifier, Player> mine = PLAYERS.computeIfAbsent(owner, o -> new HashMap<>());
            player = mine.get(id);
            if (player == null || !player.source.equals(source)) {
                Def def = def(id, owner);
                if (def == null) return;
                player = new Player(source, def);
                mine.put(id, player);
            }
        }
        Def def = player.def;
        BlockPos lightAt = BlockPos.containing(context.at());
        Timed timed = new Timed(owner instanceof MultiblockCoreBlockEntity core ? core : null, owner instanceof MiniHost host ? host : null,
                context.time());

        poseStack.pushPose();
        // Back to the block frame, then turned by the anchor's heading; its feet are the middle of its bottom
        poseStack.mulPose(Axis.YP.rotationDegrees(context.yaw() - 180.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(-context.yaw()));
        poseStack.translate(-player.foot.x, -player.foot.y, -player.foot.z);
        poseStack.pushPose();
        poseStack.translate(player.low.getX(), player.low.getY(), player.low.getZ());
        Miniatures.submit(poseStack, collector, camera, player.mesh(level, lightAt), context.light(), List.of());
        poseStack.popPose();
        if (player.animation != null) {
            int ticks = timed.operationTicks();
            player.animation.setSpeed(timed.working() && ticks > 0 ? (float) def.length() / ticks : 1.0F);
            MiniatureAnimation.Placement placement = null;
            if (timed.core() != null) { // particles and sounds where the mini stands in the world
                double turn = Math.toRadians(-context.yaw());
                double cos = Math.cos(turn), sin = Math.sin(turn);
                Vec3 feet = context.at(), foot = player.foot;
                float scale = context.scale();
                placement = new MiniatureAnimation.Placement(point -> {
                    Vec3 local = point.subtract(foot).scale(scale);
                    return feet.add(local.x * cos + local.z * sin, local.y, -local.x * sin + local.z * cos);
                }, scale);
            }
            depth++;
            try {
                MiniatureAnimation.Frame frame = player.animation.prepare(level, player, timed, lightAt, context.time(),
                        context.partialTicks(), placement);
                MiniatureAnimation.submit(frame, poseStack, collector, camera);
            } finally {
                depth--;
            }
        }
        poseStack.popPose();
    }
}
