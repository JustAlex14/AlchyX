package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A mini structure: a small machine shown inside a bigger one (a tiny hatchery on the hatchery's roof, a nexus
 * showing what it absorbed). It plays its own animation, timed on its parent: one operation of the parent plays
 * {@code length} ticks of the mini's animation, so a faster recipe (a speed upgrade) plays it faster too.
 * <p>
 * A mini comes from one of three places:
 * <ul>
 *   <li>a multiblock that already exists: its formed look and its animation file, nothing to write;</li>
 *   <li>a mini file, {@code data/<namespace>/machine_mini/<name>.json} ({@link File}): blocks of its own or a
 *       multiblock's, and an animation of its own or a machine's;</li>
 *   <li>one a player built in the Machine Studio ({@link Own}), kept by the machine itself.</li>
 * </ul>
 * The parent places it with a "mini" anchor in the studio, or in an animation file with a figure effect:
 * {@code {"type": "figure", "figure": "mypack:tiny_hatchery", "at": {"anchor": "roof"}, "scale": 0.25}}.
 */
public final class MiniStructure {
    private MiniStructure() {}

    /** How many minis of its own one machine keeps. */
    public static final int MAX_OWN = 4;
    /** The most blocks across one mini of its own (an 8 x 8 x 8 box). */
    public static final int MAX_SIDE = 8;
    /** Sizes the studio offers (one mini block, in blocks of the machine). */
    public static final List<Float> SIZES = List.of(0.125F, 0.1875F, 0.25F, 0.375F, 0.5F);
    /** What one parent operation plays of a mini's animation, in ticks, unless its file says. */
    public static final int LENGTH = 40;

    /** The figure id that draws a machine's own mini number {@code index}. */
    public static Identifier ownId(int index) {
        return Identifier.fromNamespaceAndPath("alchyx", "own_mini/" + index);
    }

    /** The index of a machine's own mini this figure id names, or -1. */
    public static int ownIndex(Identifier id) {
        if (!id.getNamespace().equals("alchyx") || !id.getPath().startsWith("own_mini/")) return -1;
        try {
            return Integer.parseInt(id.getPath().substring("own_mini/".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** What a "mini" anchor names: "#2" for the machine's own mini number 2, or a mini (or multiblock) id. */
    public static @Nullable Identifier figureFor(String what) {
        if (what.startsWith("#")) {
            try {
                return ownId(Integer.parseInt(what.substring(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return Identifier.tryParse(what);
    }

    /** One block of a mini: where (offset from the mini's core cell) and what. */
    public record Cell(BlockPos at, Block block, Map<String, String> state) {
        public static final Codec<Cell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("at").forGetter(Cell::at),
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(Cell::block),
                BlockLook.STATE.optionalFieldOf("state", Map.of()).forGetter(Cell::state)
        ).apply(instance, Cell::new));

        public BlockState look() {
            return BlockLook.apply(block.defaultBlockState(), state);
        }

        /** The cell for a block state: its values that differ from the block's default. */
        public static Cell of(BlockPos at, BlockState look) {
            Map<String, String> values = new LinkedHashMap<>();
            BlockState plain = look.getBlock().defaultBlockState();
            for (Property<?> property : look.getProperties()) {
                if (!look.getValue(property).equals(plain.getValue(property))) values.put(property.getName(), valueName(look, property));
            }
            return new Cell(at.immutable(), look.getBlock(), Map.copyOf(values));
        }

        private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }

    /**
     * A mini file ({@code data/<namespace>/machine_mini/<name>.json}). Everything is optional, but it needs blocks
     * from somewhere: {@code blocks}, or a {@code structure} to take them from.
     * <pre>
     * {
     *   "name": "Tiny hatchery",               (what the studio shows)
     *   "structure": "mypack:egg_hatchery",    (take this multiblock's formed look...)
     *   "blocks": [{"at": [0, 0, 0], "block": "minecraft:smoker", "state": {"lit": true}}],   (...or blocks of its own)
     *   "animation": "mypack:egg_hatchery",    (play this machine's animation file; default: the structure's)
     *   "anchors": {"top": [0, 1, 0]},          (and/or effects of its own, like an animation file's)
     *   "effects": [ ... ],
     *   "length": 40,                          (ticks of its animation one operation of the parent plays)
     *   "size": 0.25                           (one mini block, in blocks of the parent, unless the anchor says)
     * }
     * </pre>
     */
    public record File(Optional<String> name, Optional<Identifier> structure, List<Cell> blocks, Optional<Identifier> animation,
                       Map<String, BlockPos> anchors, List<Effect> effects, int length, float size) {
        public static final Codec<File> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name").forGetter(File::name),
                Identifier.CODEC.optionalFieldOf("structure").forGetter(File::structure),
                Cell.CODEC.listOf().optionalFieldOf("blocks", List.of()).forGetter(File::blocks),
                Identifier.CODEC.optionalFieldOf("animation").forGetter(File::animation),
                Codec.unboundedMap(Codec.STRING, BlockPos.CODEC).optionalFieldOf("anchors", Map.of()).forGetter(File::anchors),
                Effect.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(File::effects),
                Codec.intRange(1, 24000).optionalFieldOf("length", LENGTH).forGetter(File::length),
                Codec.floatRange(0.01F, 1.0F).optionalFieldOf("size", 0.25F).forGetter(File::size)
        ).apply(instance, File::new));
    }

    /**
     * A mini a player built in the Machine Studio, kept by the machine: its name, its blocks by offset from its
     * core cell, and the motion each moving block plays (while the parent works, timed on it like any mini).
     */
    public record Own(String name, Map<BlockPos, BlockState> blocks, Map<BlockPos, Motion> motions) {
        private record Moving(BlockPos at, Motion motion) {
            static final Codec<Moving> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    BlockPos.CODEC.fieldOf("at").forGetter(Moving::at),
                    Motion.CODEC.fieldOf("motion").forGetter(Moving::motion)
            ).apply(instance, Moving::new));
        }

        public static final Codec<Own> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "Mini").forGetter(Own::name),
                Cell.CODEC.listOf().fieldOf("blocks").forGetter(own -> {
                    List<Cell> cells = new ArrayList<>();
                    own.blocks().forEach((at, look) -> cells.add(Cell.of(at, look)));
                    return cells;
                }),
                Moving.CODEC.listOf().optionalFieldOf("motions", List.of()).forGetter(own -> {
                    List<Moving> list = new ArrayList<>();
                    own.motions().forEach((at, motion) -> list.add(new Moving(at, motion)));
                    return list;
                })
        ).apply(instance, (name, cells, moving) -> {
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (Cell cell : cells) blocks.put(cell.at(), cell.look());
            Map<BlockPos, Motion> motions = new LinkedHashMap<>();
            for (Moving m : moving) motions.put(m.at(), m.motion());
            return new Own(name, blocks, motions);
        }));

        public static final StreamCodec<ByteBuf, Own> STREAM_CODEC = StreamCodec.of(
                (buf, own) -> {
                    FriendlyByteBuf out = new FriendlyByteBuf(buf);
                    out.writeUtf(own.name(), 64);
                    out.writeVarInt(own.blocks().size());
                    own.blocks().forEach((at, look) -> {
                        out.writeBlockPos(at);
                        out.writeVarInt(Block.getId(look));
                    });
                    out.writeVarInt(own.motions().size());
                    own.motions().forEach((at, motion) -> {
                        out.writeBlockPos(at);
                        out.writeVarInt(motion.ordinal());
                    });
                },
                buf -> {
                    FriendlyByteBuf in = new FriendlyByteBuf(buf);
                    String name = in.readUtf(64);
                    int count = in.readVarInt();
                    if (count < 0 || count > MAX_SIDE * MAX_SIDE * MAX_SIDE) throw new IllegalArgumentException("Too many mini blocks: " + count);
                    Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
                    for (int i = 0; i < count; i++) blocks.put(in.readBlockPos(), Block.stateById(in.readVarInt()));
                    int moving = in.readVarInt();
                    if (moving < 0 || moving > count) throw new IllegalArgumentException("Too many mini motions: " + moving);
                    Map<BlockPos, Motion> motions = new LinkedHashMap<>();
                    Motion[] all = Motion.values();
                    for (int i = 0; i < moving; i++) {
                        BlockPos at = in.readBlockPos();
                        int index = in.readVarInt();
                        if (index >= 0 && index < all.length) motions.put(at, all[index]);
                    }
                    return new Own(name, blocks, motions);
                });

        /**
         * The same, kept to what a machine may hold: at most {@link #MAX_SIDE} blocks across, no air, motions only
         * on its blocks, that fit them and that a mini plays (no change of look, no mod motion); null if no block is left.
         */
        public @Nullable Own tidy() {
            Map<BlockPos, BlockState> kept = new LinkedHashMap<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isAir()) continue;
                minX = Math.min(minX, entry.getKey().getX());
                minY = Math.min(minY, entry.getKey().getY());
                minZ = Math.min(minZ, entry.getKey().getZ());
            }
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isAir()) continue;
                BlockPos at = entry.getKey();
                if (at.getX() - minX >= MAX_SIDE || at.getY() - minY >= MAX_SIDE || at.getZ() - minZ >= MAX_SIDE) continue;
                kept.put(at.immutable(), entry.getValue());
            }
            if (kept.isEmpty()) return null;
            Map<BlockPos, Motion> keptMotions = new LinkedHashMap<>();
            motions.forEach((at, motion) -> {
                BlockState look = kept.get(at);
                if (look != null && plays(motion) && motion.fits(look)) keptMotions.put(at.immutable(), motion);
            });
            String cleanName = name.isBlank() ? "Mini" : name.length() > 32 ? name.substring(0, 32) : name;
            return new Own(cleanName, Map.copyOf(kept), Map.copyOf(keptMotions));
        }

        /** Its animation: each moving block plays its motion while the parent works. */
        public ResolvedAnimation animation(Identifier id) {
            Map<String, BlockPos> anchors = new LinkedHashMap<>();
            List<Effect> effects = new ArrayList<>();
            int n = 0;
            for (Map.Entry<BlockPos, Motion> entry : motions.entrySet()) {
                BlockState look = blocks.get(entry.getKey());
                if (look == null) continue;
                String anchor = "mini_" + n++;
                anchors.put(anchor, entry.getKey());
                effects.addAll(Motion.effects(entry.getValue(), anchor, Trigger.WORKING, look, MotionSetup.ALWAYS));
            }
            BlockPos[] box = box(blocks.keySet());
            return new ResolvedAnimation(id, Optional.empty(), Map.copyOf(anchors), box[0], box[1], List.copyOf(effects));
        }

        /** As a mini file, to export. */
        public File file() {
            List<Cell> cells = new ArrayList<>();
            blocks.forEach((at, look) -> cells.add(Cell.of(at, look)));
            ResolvedAnimation animation = animation(Identifier.fromNamespaceAndPath("alchyx", "export"));
            return new File(Optional.of(name), Optional.empty(), cells, Optional.empty(), animation.anchors(), animation.effects(), LENGTH, 0.25F);
        }
    }

    /** Whether a mini plays this motion (one that changes the look picked by hand, or a mod's, needs the studio's setup). */
    public static boolean plays(Motion motion) {
        return motion != Motion.CHANGE && motion != Motion.MOD;
    }

    /** The lowest and highest cell of these. */
    public static BlockPos[] box(java.util.Collection<BlockPos> cells) {
        if (cells.isEmpty()) return new BlockPos[] {BlockPos.ZERO, BlockPos.ZERO};
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new BlockPos[] {new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    /** The middle of the bottom of these cells (where a mini stands on its anchor). */
    public static Vec3 foot(java.util.Collection<BlockPos> cells) {
        BlockPos[] box = box(cells);
        return new Vec3((box[0].getX() + box[1].getX() + 1) / 2.0, box[0].getY(), (box[0].getZ() + box[1].getZ() + 1) / 2.0);
    }
}
