package com.lealex.alchyx.multiblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A multiblock shape loaded from a datapack JSON ({@code data/<namespace>/multiblock/<name>.json}).
 *
 * <ul>
 *   <li>"layers" + "key": what the player must build. Layers go from the bottom to the top; each layer is a map
 *       seen from above (rows north to south, characters west to east). Every character must be in "key" and
 *       exactly one must be the core ("core": true): all positions are measured from it, so the shape needn't
 *       be centered.</li>
 *   <li>"core" (top level): the machine's central block, e.g. "alchymastery:distortion_matrix" (required).
 *       Every machine uses the same recognition; only its core block and its pattern file differ.</li>
 *   <li>"result" + "result_key" (optional): what each position turns into once the chamber forms. Same size as
 *       "layers", its own characters. Without a result grid nothing is converted.</li>
 *   <li>"front" (optional): the side the grid's front faces as written, e.g. "east". When the core block has a
 *       facing (it faces the player who places it), the whole pattern is turned so its front matches the core's
 *       facing: the machine can be built in any of the 4 directions. Without "front", or with a core that has no
 *       facing, the pattern is used as written.</li>
 *   <li>"anchors" (optional): named spots as [x, y, z] offsets from the core in the written grid, e.g.
 *       "input_shelf": [1, 1, 1]; machines use them for their displays.</li>
 * </ul>
 */
public record MultiblockPattern(Optional<Block> core, Optional<Direction> front, List<List<String>> layers,
                                Map<String, PatternKey> key, Optional<List<List<String>>> result,
                                Map<String, ResultKey> resultKey, Map<String, BlockPos> anchors,
                                int[] coreCell) {

    /**
     * From the file's fields; the core's cell [layer, row, col] is found once here, since the structure checks, the
     * core tracker (every block change near a machine) and the offsets all need it. Not part of the JSON.
     */
    public MultiblockPattern(Optional<Block> core, Optional<Direction> front, List<List<String>> layers,
                             Map<String, PatternKey> key, Optional<List<List<String>>> result,
                             Map<String, ResultKey> resultKey, Map<String, BlockPos> anchors) {
        this(core, front, layers, key, result, resultKey, anchors, findCoreCell(layers, key));
    }

    private static int[] findCoreCell(List<List<String>> layers, Map<String, PatternKey> key) {
        for (int layer = 0; layer < layers.size(); layer++) {
            for (int row = 0; row < layers.get(layer).size(); row++) {
                String line = layers.get(layer).get(row);
                for (int col = 0; col < line.length(); col++) {
                    PatternKey entry = key.get(String.valueOf(line.charAt(col)));
                    if (entry != null && entry.isCore()) return new int[]{layer, row, col};
                }
            }
        }
        return new int[]{-1, -1, -1};
    }

    private static final Codec<List<List<String>>> GRID = Codec.STRING.listOf().listOf();

    public static final Codec<MultiblockPattern> CODEC = RecordCodecBuilder.<MultiblockPattern>create(instance -> instance.group(
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("core").forGetter(MultiblockPattern::core),
            Direction.CODEC.optionalFieldOf("front").forGetter(MultiblockPattern::front),
            GRID.fieldOf("layers").forGetter(MultiblockPattern::layers),
            Codec.unboundedMap(Codec.STRING, PatternKey.CODEC).fieldOf("key").forGetter(MultiblockPattern::key),
            GRID.optionalFieldOf("result").forGetter(MultiblockPattern::result),
            Codec.unboundedMap(Codec.STRING, ResultKey.CODEC).optionalFieldOf("result_key", Map.of()).forGetter(MultiblockPattern::resultKey),
            Codec.unboundedMap(Codec.STRING, BlockPos.CODEC).optionalFieldOf("anchors", Map.of()).forGetter(MultiblockPattern::anchors)
    ).apply(instance, (core, front, layers, key, result, resultKey, anchors)
            -> new MultiblockPattern(core, front, layers, key, result, resultKey, anchors))).validate(MultiblockPattern::validate);

    /** The machine's central block ("core" in the file; required). */
    public Block coreBlock() {
        return core.orElseThrow();
    }

    /** One position the transformation wave converts, what it becomes, and how the pattern is turned. */
    /**
     * {@code optional}: the grid asks for air there, so the player may have put something in it (a tap, a torch...):
     * the wave leaves a non-air block alone instead of replacing it.
     */
    public record Conversion(BlockPos pos, ResultKey result, Rotation rotation, boolean optional) {
        /** The look of the part replacing {@code original}, turned with the pattern (explicit result states included). */
        public BlockState disguise(BlockState original) {
            if (rotation == Rotation.NONE) return result.disguiseFor(original);
            return result.disguiseFor(original.rotate(inverse(rotation))).rotate(rotation);
        }

        /** True once the position holds what it becomes: a chamber shell, or the real block for "real" results. */
        public boolean isDone(BlockState current) {
            return result.real() ? current.is(result.block().orElseThrow()) : ShellParts.isShell(current);
        }

        /** True if the wave should leave this position as it is: an air cell the player filled. */
        public boolean skipped(BlockState current) {
            return optional && !current.isAir() && !isDone(current);
        }
    }

    // ---- Rotation: the pattern turned to match the core's facing ----

    /** How to turn the written pattern for a core in this state (NONE without "front" or a core facing). */
    public Rotation rotationFor(BlockState coreState) {
        return rotationFor(front, coreState);
    }

    /** How a pattern written facing {@code front} is turned for a core in this state (also used by the client). */
    public static Rotation rotationFor(Optional<Direction> front, BlockState coreState) {
        if (front.isEmpty() || !coreState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return Rotation.NONE;
        Direction facing = coreState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // 2D values go S=0, W=1, N=2, E=3: each step is a quarter turn clockwise
        int steps = Math.floorMod(facing.get2DDataValue() - front.get().get2DDataValue(), 4);
        return switch (steps) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Rotation inverse(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }

    /** World position of a named anchor for a core at {@code core}, or null if the pattern doesn't define it. */
    public @Nullable BlockPos anchor(BlockPos core, Rotation rotation, String name) {
        BlockPos offset = anchors.get(name);
        return offset == null ? null : core.offset(offset.rotate(rotation));
    }


    // ---- Validation (errors are logged with the file name by the reload listener) ----

    private static DataResult<MultiblockPattern> validate(MultiblockPattern pattern) {
        List<List<String>> layers = pattern.layers;
        if (pattern.core.isEmpty()) return DataResult.error(() -> "\"core\" (the machine's core block id) is missing");
        if (layers.isEmpty() || layers.getFirst().isEmpty()) return DataResult.error(() -> "\"layers\" is empty");
        int rows = layers.getFirst().size();
        int cols = layers.getFirst().getFirst().length();

        String shapeError = checkShape("layers", layers, layers.size(), rows, cols, pattern.key.keySet());
        if (shapeError != null) return DataResult.error(() -> shapeError);
        for (String symbol : pattern.key.keySet()) {
            if (symbol.length() != 1) return DataResult.error(() -> "key \"" + symbol + "\" must be a single character");
        }
        int matrices = 0;
        for (List<String> grid : layers) {
            for (String line : grid) {
                for (char c : line.toCharArray()) {
                    if (pattern.key.get(String.valueOf(c)).isCore()) matrices++;
                }
            }
        }
        int found = matrices;
        if (found != 1) return DataResult.error(() -> "the grid must contain exactly one core (\"core\": true), found " + found);

        if (pattern.result.isPresent()) {
            String resultError = checkShape("result", pattern.result.get(), layers.size(), rows, cols, pattern.resultKey.keySet());
            if (resultError != null) return DataResult.error(() -> resultError);
            for (String symbol : pattern.resultKey.keySet()) {
                if (symbol.length() != 1) return DataResult.error(() -> "result_key \"" + symbol + "\" must be a single character");
            }
            int[] m = pattern.matrixCell();
            if (pattern.resultAt(m[0], m[1], m[2]) != null) {
                return DataResult.error(() -> "the result at the core position must be \"keep\"");
            }
        }
        return DataResult.success(pattern);
    }

    /** Checks a grid has the expected size and that every character is in the given key. Returns an error or null. */
    private static @Nullable String checkShape(String name, List<List<String>> grid, int layers, int rows, int cols, Set<String> symbols) {
        if (grid.size() != layers) return "\"" + name + "\" has " + grid.size() + " layers, expected " + layers;
        for (int layer = 0; layer < grid.size(); layer++) {
            List<String> lines = grid.get(layer);
            int l = layer + 1;
            if (lines.size() != rows) return "\"" + name + "\" layer " + l + " has " + lines.size() + " rows, expected " + rows;
            for (String line : lines) {
                if (line.length() != cols) {
                    return "\"" + name + "\" row \"" + line + "\" in layer " + l + " is not " + cols + " characters long";
                }
                for (char c : line.toCharArray()) {
                    if (!symbols.contains(String.valueOf(c))) {
                        return "character '" + c + "' in \"" + name + "\" layer " + l + " has no entry in its key";
                    }
                }
            }
        }
        return null;
    }

    // ---- Geometry: everything is measured from the matrix ----

    private int[] matrixCell() {
        return coreCell; // found once, in the constructor
    }

    private PatternKey entryAt(int layer, int row, int col) {
        return key.get(String.valueOf(layers.get(layer).get(row).charAt(col)));
    }

    /** What this cell turns into, or null if it is kept as is (or there is no result grid). */
    private @Nullable ResultKey resultAt(int layer, int row, int col) {
        if (result.isEmpty()) return null;
        ResultKey entry = resultKey.get(String.valueOf(result.get().get(layer).get(row).charAt(col)));
        return entry == null || entry.keep() ? null : entry;
    }

    private int height() { return layers.size(); }
    private int depth() { return layers.getFirst().size(); }
    private int width() { return layers.getFirst().getFirst().length(); }

    /** Lowest corner of the structure, relative to the matrix. */
    public BlockPos minOffset() {
        int[] m = matrixCell();
        return new BlockPos(-m[2], -m[0], -m[1]);
    }

    /** Highest corner of the structure, relative to the matrix. */
    public BlockPos maxOffset() {
        int[] m = matrixCell();
        return new BlockPos(width() - 1 - m[2], height() - 1 - m[0], depth() - 1 - m[1]);
    }

    /** Every position of the structure's bounding box as built (mutable positions, as {@code BlockPos.betweenClosed}). */
    public Iterable<BlockPos> allPositions(BlockPos matrix) {
        return allPositions(matrix, Rotation.NONE);
    }

    public Iterable<BlockPos> allPositions(BlockPos matrix, Rotation rotation) {
        BlockPos a = minOffset().rotate(rotation), b = maxOffset().rotate(rotation);
        return BlockPos.betweenClosed(matrix.offset(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                matrix.offset(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
    }

    /** True if pos is inside the structure's box as built with this rotation. */
    public boolean contains(BlockPos matrix, Rotation rotation, BlockPos pos) {
        BlockPos a = minOffset().rotate(rotation), b = maxOffset().rotate(rotation);
        int dx = pos.getX() - matrix.getX(), dy = pos.getY() - matrix.getY(), dz = pos.getZ() - matrix.getZ();
        return dx >= Math.min(a.getX(), b.getX()) && dx <= Math.max(a.getX(), b.getX())
                && dy >= Math.min(a.getY(), b.getY()) && dy <= Math.max(a.getY(), b.getY())
                && dz >= Math.min(a.getZ(), b.getZ()) && dz <= Math.max(a.getZ(), b.getZ());
    }

    /**
     * The positions the structure really occupies (every block, tag and core cell; not "air" or "any" cells), as
     * built with this rotation. Two machines side by side can share bounding-box space: this says which one a block
     * belongs to.
     */
    public Set<BlockPos> occupiedPositions(BlockPos matrix, Rotation rotation) {
        Set<BlockPos> found = new java.util.HashSet<>();
        forEachCell(matrix, rotation, (entry, converted, pos, dx, dy, dz, layer) -> {
            if (!entry.air() && !entry.any()) found.add(pos.immutable());
            return null;
        });
        return found;
    }

    /** True if pos could belong to the structure in any rotation (a square box around the core): used by the tracker. */
    public boolean isWithinStructure(BlockPos matrix, BlockPos pos) {
        BlockPos min = minOffset(), max = maxOffset();
        int reach = Math.max(Math.max(-min.getX(), max.getX()), Math.max(-min.getZ(), max.getZ()));
        if (front.isEmpty()) return contains(matrix, Rotation.NONE, pos);
        return Math.abs(pos.getX() - matrix.getX()) <= reach && Math.abs(pos.getZ() - matrix.getZ()) <= reach
                && pos.getY() - matrix.getY() >= min.getY() && pos.getY() - matrix.getY() <= max.getY();
    }

    private interface CellVisitor {
        /** Return a non-null value to stop the walk. {@code converted} is null when the cell is kept as is. */
        @Nullable String visit(PatternKey entry, @Nullable ResultKey converted, BlockPos pos, int dx, int dy, int dz, int layer);
    }

    private @Nullable String forEachCell(BlockPos matrix, CellVisitor visitor) {
        return forEachCell(matrix, Rotation.NONE, visitor);
    }

    /** Walks the written grid; {@code pos} is the world position (turned), dx/dy/dz the offsets as written. */
    private @Nullable String forEachCell(BlockPos matrix, Rotation rotation, CellVisitor visitor) {
        int[] m = matrixCell();
        for (int layer = 0; layer < height(); layer++) {
            for (int row = 0; row < depth(); row++) {
                for (int col = 0; col < width(); col++) {
                    int dx = col - m[2], dy = layer - m[0], dz = row - m[1];
                    String found = visitor.visit(entryAt(layer, row, col), resultAt(layer, row, col),
                            matrix.offset(new BlockPos(dx, dy, dz).rotate(rotation)), dx, dy, dz, layer);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    // ---- Checks ----

    /** Returns null when the structure is complete, otherwise a short description of the first problem found. */
    public @Nullable String findProblem(Level level, BlockPos matrix) {
        return findProblem(level, matrix, Rotation.NONE);
    }

    /** With a turned pattern, each block is turned back into the written orientation before the checks. */
    public @Nullable String findProblem(Level level, BlockPos matrix, Rotation rotation) {
        BlockPos min = minOffset(), max = maxOffset();
        Block coreBlock = coreBlock();
        Rotation back = inverse(rotation);
        return forEachCell(matrix, rotation, (entry, converted, pos, dx, dy, dz, layer) -> {
            BlockState worldState = level.getBlockState(pos);
            String where = " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (layer " + (layer + 1) + ")";
            // A converted position may already hold the chamber part it became; it was checked before converting
            if (converted != null && ShellParts.isPart(worldState)) return null;
            // ...or the real block a "real" result placed (a candle where the grid asks for air)
            if (converted != null && converted.real() && worldState.is(converted.block().orElseThrow())) return null;
            BlockState state = rotation == Rotation.NONE ? worldState : worldState.rotate(back);
            if (!entry.matchesOriginal(state, coreBlock)) return entry.describe(coreBlock) + " needed" + where;
            if (entry.facing().isPresent() && entry.facingCheck()) {
                Direction expected = expectedFacing(entry.facing().get(), dx, dy, dz);
                Direction actual = facingOf(state);
                if (actual != null && actual != expected) {
                    return entry.describe(coreBlock) + " must point " + (entry.facing().get() == PatternKey.FacingRule.CENTER
                            ? "toward the center" : "away from the center") + where;
                }
            }
            if (entry.stairs().isEmpty()) return null;
            if (!state.hasProperty(StairBlock.FACING) || !state.hasProperty(StairBlock.HALF) || !state.hasProperty(StairBlock.SHAPE)) {
                return "stairs needed" + where;
            }
            String problem = switch (entry.stairs().get()) {
                case FACE_CENTER -> checkFaceCenter(state, dx, dy, dz);
                case ROOF_EDGE -> checkRoofEdge(state, dx, dz, min, max);
                case ROOF_CORNER -> checkRoofCorner(state, dx, dz, min, max);
            };
            return problem == null ? null : problem + where;
        });
    }

    /** The direction a "facing" rule asks for at this offset from the core (horizontal unless straight above/below). */
    private static Direction expectedFacing(PatternKey.FacingRule rule, int dx, int dy, int dz) {
        Direction toCenter = (dx != 0 || dz != 0)
                ? Direction.getApproximateNearest(-dx, 0, -dz)
                : (dy > 0 ? Direction.DOWN : Direction.UP);
        return rule == PatternKey.FacingRule.CENTER ? toCenter : toCenter.getOpposite();
    }

    /** The block's "facing" property (pistons, torches, furnaces...), or null if it has none. */
    private static @Nullable Direction facingOf(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        return property != null && state.getValue(property) instanceof Direction direction ? direction : null;
    }

    /** The state turned to face {@code direction}, if its "facing" property allows that direction. */
    @SuppressWarnings("unchecked")
    private static BlockState withFacing(BlockState state, Direction direction) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        if (property instanceof EnumProperty<?> enumProperty && enumProperty.getValueClass() == Direction.class
                && enumProperty.getPossibleValues().contains(direction)) {
            return state.setValue((EnumProperty<Direction>) enumProperty, direction);
        }
        return state;
    }

    /**
     * face_center: the tall side faces the matrix column. Below the matrix the stairs sit on the floor,
     * above it they hang upside down.
     */
    private static @Nullable String checkFaceCenter(BlockState state, int dx, int dy, int dz) {
        if (dx != 0 || dz != 0) {
            // A stair's FACING is the side of its tall back part
            Direction towardCenter = Direction.getApproximateNearest(-dx, 0, -dz);
            if (state.getValue(StairBlock.FACING) != towardCenter) {
                return "stairs must face the center (tall side toward it)";
            }
        }
        Half expectedHalf = dy < 0 ? Half.BOTTOM : Half.TOP;
        if (state.getValue(StairBlock.HALF) != expectedHalf) {
            return dy < 0 ? "stairs must sit on the floor (not upside down)" : "stairs must be upside down, attached to the roof";
        }
        return null;
    }

    /** roof_edge: upright stairs on the outer edge, tall side toward the middle of the roof. */
    private static @Nullable String checkRoofEdge(BlockState state, int dx, int dz, BlockPos min, BlockPos max) {
        if (state.getValue(StairBlock.HALF) != Half.BOTTOM) return "roof stairs must not be upside down";
        Direction towardCenter;
        if (dx == min.getX()) towardCenter = Direction.EAST;
        else if (dx == max.getX()) towardCenter = Direction.WEST;
        else if (dz == min.getZ()) towardCenter = Direction.SOUTH;
        else if (dz == max.getZ()) towardCenter = Direction.NORTH;
        else return null; // not on an edge: only the half is checked
        if (state.getValue(StairBlock.FACING) != towardCenter) {
            return "roof stairs must have their tall side toward the middle of the roof";
        }
        return null;
    }

    /** roof_corner: upright stairs facing inward along either edge, joined into an outer corner. */
    private static @Nullable String checkRoofCorner(BlockState state, int dx, int dz, BlockPos min, BlockPos max) {
        if (state.getValue(StairBlock.HALF) != Half.BOTTOM) return "roof corner stairs must not be upside down";
        Direction alongX = dx == min.getX() ? Direction.EAST : Direction.WEST;
        Direction alongZ = dz == min.getZ() ? Direction.SOUTH : Direction.NORTH;
        Direction facing = state.getValue(StairBlock.FACING);
        if (facing != alongX && facing != alongZ) return "roof corner stairs must face the middle of the roof";
        StairsShape shape = state.getValue(StairBlock.SHAPE);
        if (shape != StairsShape.OUTER_LEFT && shape != StairsShape.OUTER_RIGHT) {
            return "roof corner stairs must join both edges (outer corner)";
        }
        return null;
    }

    // ---- Positions used by the matrix ----

    /** Positions the transformation wave converts (result is not "keep"), nearest to the matrix first. */
    public List<Conversion> conversions(BlockPos matrix) {
        return conversions(matrix, Rotation.NONE);
    }

    public List<Conversion> conversions(BlockPos matrix, Rotation rotation) {
        List<Conversion> found = new ArrayList<>();
        forEachCell(matrix, rotation, (entry, converted, pos, dx, dy, dz, layer) -> {
            if (converted != null) found.add(new Conversion(pos, converted, rotation, entry.air()));
            return null;
        });
        found.sort(Comparator.comparingDouble(c -> c.pos().distSqr(matrix)));
        return found;
    }

    /** Positions whose original blocks are destroyed when the chamber breaks ("consumed": true), with their test. */
    public List<Map.Entry<BlockPos, Predicate<BlockState>>> consumed(BlockPos matrix) {
        return consumed(matrix, Rotation.NONE);
    }

    public List<Map.Entry<BlockPos, Predicate<BlockState>>> consumed(BlockPos matrix, Rotation rotation) {
        List<Map.Entry<BlockPos, Predicate<BlockState>>> found = new ArrayList<>();
        Rotation back = inverse(rotation);
        forEachCell(matrix, rotation, (entry, converted, pos, dx, dy, dz, layer) -> {
            if (entry.consumed()) {
                Block coreBlock = coreBlock();
                Predicate<BlockState> test = state -> entry.matchesOriginal(state.rotate(back), coreBlock);
                found.add(Map.entry(pos, test));
            }
            return null;
        });
        return found;
    }

    // ---- Blueprint, used by the structure builder ----

    /**
     * One block the structure builder places: {@code state} (the first choice), every acceptable choice in
     * {@code options} (each block of a tag, turned the same way), and {@code accepts}, the structure check's test.
     * {@code oriented}: the entry has a stairs or facing rule, so a block it accepts may still be turned wrong.
     */
    public record Placement(BlockPos pos, BlockState state, List<BlockState> options, Predicate<BlockState> accepts, boolean oriented) {
        public Placement(BlockPos pos, BlockState state) {
            this(pos, state, List.of(state), candidate -> candidate == state, false);
        }

        /** True if {@code current} needs no change: one of the options, or accepted when orientation doesn't matter. */
        public boolean satisfiedBy(BlockState current) {
            return options.contains(current) || (!oriented && accepts.test(current));
        }
    }

    /**
     * The blocks a player would place to build this structure around a core at {@code core}: the first block of
     * each tag, the stated block state values, and stairs turned the way their rule asks. The core itself and
     * "any" positions are left out.
     */
    public List<Placement> blueprint(BlockPos core) {
        return blueprint(core, Rotation.NONE);
    }

    public List<Placement> blueprint(BlockPos core, Rotation rotation) {
        BlockPos min = minOffset(), max = maxOffset();
        Rotation back = inverse(rotation);
        Block coreBlock = coreBlock();
        List<Placement> found = new ArrayList<>();
        forEachCell(core, rotation, (entry, converted, pos, dx, dy, dz, layer) -> {
            if (entry.isCore() || entry.any()) return null;
            BlockState first = entry.exampleState();
            if (first == null) return null;
            List<BlockState> options = new ArrayList<>();
            options.add(first);
            for (BlockState other : entry.exampleStates()) if (!options.contains(other)) options.add(other);
            List<BlockState> shaped = new ArrayList<>();
            for (BlockState option : options) {
                BlockState state = option;
                if (entry.stairs().isPresent() && state.hasProperty(StairBlock.FACING) && state.hasProperty(StairBlock.HALF)) {
                    state = orientStairs(state, entry.stairs().get(), dx, dy, dz, min, max);
                }
                if (entry.facing().isPresent()) {
                    state = withFacing(state, expectedFacing(entry.facing().get(), dx, dy, dz));
                }
                shaped.add(state.rotate(rotation));
            }
            boolean oriented = entry.stairs().isPresent() || entry.facing().isPresent();
            found.add(new Placement(pos.immutable(), shaped.get(0), List.copyOf(shaped),
                    state -> entry.matchesOriginal(state.rotate(back), coreBlock), oriented));
            return null;
        });
        return found;
    }

    // ---- Previews (guide books) ----

    /**
     * One position of the structure for previews such as Patchouli's multiblock pages: its offset from the core as
     * written, the block to show (null = must be air), and what the structure check accepts there.
     */
    public record PreviewCell(BlockPos offset, @Nullable BlockState display, Predicate<BlockState> accepts) {}

    /**
     * Every position to build, as written (not turned): the Structure Builder's blocks with stair corners joined,
     * air where air is required, the core facing the front. "any" positions are left out.
     */
    public List<PreviewCell> previewCells() {
        List<LookCell> built = new ArrayList<>();
        for (Placement placement : blueprint(BlockPos.ZERO)) built.add(new LookCell(placement.pos(), placement.state()));
        Map<BlockPos, BlockState> shown = new HashMap<>();
        for (LookCell cell : joinStairs(built)) shown.put(cell.offset(), cell.state());
        BlockState coreState = coreBlock().defaultBlockState();
        if (front.isPresent()) coreState = withFacing(coreState, front.get());
        BlockState core = coreState;
        Block coreBlock = coreBlock();
        List<PreviewCell> cells = new ArrayList<>();
        forEachCell(BlockPos.ZERO, (entry, converted, pos, dx, dy, dz, layer) -> {
            if (entry.any()) return null;
            BlockState display = entry.isCore() ? core : entry.air() ? null : shown.get(pos);
            if (display == null && !entry.air()) return null; // no example block for this entry
            // Shown with its preview look (blocks drawn by a renderer only would be invisible); still matched as is
            cells.add(new PreviewCell(pos.immutable(), display == null ? null : PreviewLooks.apply(display),
                    state -> entry.matchesOriginal(state, coreBlock)));
            return null;
        });
        return cells;
    }

    // ---- The formed look, for miniature models ----

    /** One block of a miniature: its offset from the core (as written, not turned) and what it looks like. */
    public record LookCell(BlockPos offset, BlockState state) {
        public static final Codec<LookCell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("o").forGetter(LookCell::offset),
                BlockState.CODEC.fieldOf("s").forGetter(LookCell::state)
        ).apply(instance, LookCell::new));
    }

    /**
     * What the formed machine looks like, block by block, facing its written "front": the result looks of
     * converted positions, the blocks that are kept, and the core. Air, liquids and hidden parts are left out.
     */
    public List<LookCell> formedLook() {
        BlockPos origin = BlockPos.ZERO;
        Map<BlockPos, Conversion> converted = new HashMap<>();
        for (Conversion conversion : conversions(origin)) converted.put(conversion.pos(), conversion);
        List<LookCell> cells = new ArrayList<>();
        for (Placement placement : blueprint(origin)) {
            BlockState look = placement.state();
            Conversion conversion = converted.get(placement.pos());
            if (conversion != null) {
                if (conversion.result().hidden()) continue;
                look = conversion.disguise(look);
            }
            if (look.isAir()) continue; // liquids stay: they have no block model, the renderer shows them its own way
            cells.add(new LookCell(placement.pos(), look));
        }
        BlockState core = coreBlock().defaultBlockState();
        if (front.isPresent()) core = withFacing(core, front.get()); // the core faces the front, like when placed
        cells.add(new LookCell(origin, core));
        return joinStairs(cells);
    }

    /**
     * Gives stairs their corner shapes from their neighbors, like Minecraft does when they're placed in the world
     * (the blueprint leaves every stair straight, so a miniature's roof corners wouldn't join otherwise).
     */
    private static List<LookCell> joinStairs(List<LookCell> cells) {
        Map<BlockPos, BlockState> looks = new HashMap<>();
        for (LookCell cell : cells) looks.put(cell.offset(), cell.state());
        List<LookCell> joined = new ArrayList<>();
        for (LookCell cell : cells) {
            BlockState state = cell.state();
            if (state.getBlock() instanceof StairBlock && state.hasProperty(StairBlock.SHAPE)) {
                state = state.setValue(StairBlock.SHAPE, stairsShape(state, looks, cell.offset()));
            }
            joined.add(new LookCell(cell.offset(), state));
        }
        return joined;
    }

    /** Vanilla's StairBlock.getStairsShape, reading the neighbors from a map instead of a level. */
    private static StairsShape stairsShape(BlockState state, Map<BlockPos, BlockState> looks, BlockPos pos) {
        Direction facing = state.getValue(StairBlock.FACING);
        Half half = state.getValue(StairBlock.HALF);
        BlockState behind = looks.get(pos.relative(facing));
        if (isStair(behind) && behind.getValue(StairBlock.HALF) == half) {
            Direction behindFacing = behind.getValue(StairBlock.FACING);
            if (behindFacing.getAxis() != facing.getAxis() && canTakeShape(state, looks, pos, behindFacing.getOpposite())) {
                return behindFacing == facing.getCounterClockWise() ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
            }
        }
        BlockState front = looks.get(pos.relative(facing.getOpposite()));
        if (isStair(front) && front.getValue(StairBlock.HALF) == half) {
            Direction frontFacing = front.getValue(StairBlock.FACING);
            if (frontFacing.getAxis() != facing.getAxis() && canTakeShape(state, looks, pos, frontFacing)) {
                return frontFacing == facing.getCounterClockWise() ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
            }
        }
        return StairsShape.STRAIGHT;
    }

    private static boolean canTakeShape(BlockState state, Map<BlockPos, BlockState> looks, BlockPos pos, Direction neighbour) {
        BlockState other = looks.get(pos.relative(neighbour));
        return !isStair(other) || other.getValue(StairBlock.FACING) != state.getValue(StairBlock.FACING)
                || other.getValue(StairBlock.HALF) != state.getValue(StairBlock.HALF);
    }

    private static boolean isStair(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof StairBlock && state.hasProperty(StairBlock.FACING);
    }

    /** Facing and half matching the checks above (the corner shape is left to Minecraft's neighbor logic). */
    private static BlockState orientStairs(BlockState state, PatternKey.StairRule rule, int dx, int dy, int dz, BlockPos min, BlockPos max) {
        Direction facing = state.getValue(StairBlock.FACING);
        Half half = Half.BOTTOM;
        switch (rule) {
            case FACE_CENTER -> {
                if (dx != 0 || dz != 0) facing = Direction.getApproximateNearest(-dx, 0, -dz);
                half = dy < 0 ? Half.BOTTOM : Half.TOP;
            }
            case ROOF_EDGE -> {
                if (dx == min.getX()) facing = Direction.EAST;
                else if (dx == max.getX()) facing = Direction.WEST;
                else if (dz == min.getZ()) facing = Direction.SOUTH;
                else if (dz == max.getZ()) facing = Direction.NORTH;
            }
            case ROOF_CORNER -> facing = dx == min.getX() ? Direction.EAST : Direction.WEST;
        }
        return state.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
    }
}
