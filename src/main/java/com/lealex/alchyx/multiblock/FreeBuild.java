package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.animation.Trigger;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A machine whose shape the player chooses: the "free_build" object of a pattern file. Instead of building a fixed
 * grid, the player builds anything inside a box (within the rules below), marks the box with a Structure Recorder and
 * clicks the core: the core checks the rules and, if they hold, records exactly what stands there as its own pattern
 * ({@link #record}), kept on that one core. From then on that recorded pattern is the machine's structure: breaking
 * a block of it unforms the machine, putting it back forms it again.
 * <ul>
 *   <li>{@code max_width} (default 7): the most blocks along x and along z. {@code max_height} (default 7) along y.</li>
 *   <li>{@code min_blocks} (default 1): the fewest blocks (not counting air and the core).</li>
 *   <li>{@code allowed}: block ids or #tags the build may use. Empty (the default): any block.</li>
 *   <li>{@code forbidden}: block ids or #tags it may never use.</li>
 *   <li>{@code requires}: blocks it must contain, each {@code {"block": "id or #tag", "count": n, "name": "..."}}.</li>
 *   <li>{@code formed}: {@code "same"} (default): the blocks become machine parts that look the same, and come back as
 *       they were when the machine breaks; {@code "keep"}: they stay as they are.</li>
 *   <li>{@code motions}: the motions ({@link Motion}) players may give the parts with the Recorder. Default: all of
 *       them; an empty list: none. {@code motion_when} (default {@code "working"}): when they play
 *       ({@code always}, {@code idle} or {@code working}).</li>
 *   <li>{@code particles} and {@code figures}: the particle types and entity types players may put at anchors with
 *       the Machine Studio. Defaults: {@link #DEFAULT_PARTICLES}, {@link #DEFAULT_FIGURES}; an empty list: none.</li>
 * </ul>
 * The pattern file still needs its usual one-cell grid with the core, so the rest of AlchyX knows the machine.
 */
public record FreeBuild(int maxWidth, int maxHeight, int minBlocks, List<BlockMatch> allowed, List<BlockMatch> forbidden,
                        List<Requirement> requires, Formed formed, List<Motion> motions, Trigger motionWhen,
                        List<Identifier> particles, List<Identifier> figures) {

    /** The particles players may put at anchors with the Machine Studio, unless a file says otherwise. */
    public static final List<Identifier> DEFAULT_PARTICLES = ids("smoke", "large_smoke", "campfire_cosy_smoke", "flame",
            "soul_fire_flame", "small_flame", "enchant", "portal", "end_rod", "electric_spark", "happy_villager", "witch",
            "cloud", "bubble_pop", "dripping_water", "dripping_lava", "glow", "wax_on", "white_ash", "note", "heart", "crit");
    /** The entities players may stand at anchors with the Machine Studio, unless a file says otherwise. */
    public static final List<Identifier> DEFAULT_FIGURES = ids("villager", "wandering_trader", "witch", "iron_golem",
            "snow_golem", "allay", "cat", "wolf", "fox", "parrot", "chicken", "bee", "axolotl", "frog", "armor_stand",
            "zombie", "skeleton", "piglin", "blaze", "enderman");

    private static List<Identifier> ids(String... names) {
        return java.util.Arrays.stream(names).map(Identifier::withDefaultNamespace).toList();
    }

    /**
     * What players may do with the Machine Studio on a machine with a fixed shape (an AlchyMastery machine...): every
     * motion, the default particles and figures, playing while it works.
     */
    public static final FreeBuild STUDIO = new FreeBuild(FreeBuild.HARD_LIMIT, FreeBuild.HARD_LIMIT, 1, List.of(), List.of(), List.of(), Formed.SAME,
            List.of(Motion.values()), Trigger.WORKING, DEFAULT_PARTICLES, DEFAULT_FIGURES);

    /** No box may be larger than this along any axis, whatever a file says (block change lookups stay cheap). */
    public static final int HARD_LIMIT = 32;

    /** A block id ("minecraft:glass") or a block tag ("#c:glass_blocks"), written as one string. */
    public record BlockMatch(Optional<Block> block, Optional<TagKey<Block>> tag) {
        public static final Codec<BlockMatch> CODEC = Codec.STRING.comapFlatMap(BlockMatch::parse, BlockMatch::write);

        private static DataResult<BlockMatch> parse(String text) {
            if (text.startsWith("#")) {
                Identifier id = Identifier.tryParse(text.substring(1));
                if (id == null) return DataResult.error(() -> "\"" + text + "\" is not a valid tag");
                return DataResult.success(new BlockMatch(Optional.empty(), Optional.of(TagKey.create(Registries.BLOCK, id))));
            }
            Identifier id = Identifier.tryParse(text);
            if (id == null) return DataResult.error(() -> "\"" + text + "\" is not a valid block id");
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty()) return DataResult.error(() -> "no block called " + text);
            return DataResult.success(new BlockMatch(block, Optional.empty()));
        }

        private String write() {
            return tag.map(t -> "#" + t.location()).orElseGet(() -> BuiltInRegistries.BLOCK.getKey(block.orElseThrow()).toString());
        }

        public boolean test(BlockState state) {
            return block.map(state::is).orElse(false) || tag.map(state::is).orElse(false);
        }

        /** For players: the block's name, or the tag's id. */
        public String describe() {
            return block.map(b -> b.getName().getString()).orElseGet(() -> "any #" + tag.orElseThrow().location());
        }
    }

    /** At least {@code count} blocks of a kind. */
    public record Requirement(BlockMatch block, int count, Optional<String> name) {
        public static final Codec<Requirement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockMatch.CODEC.fieldOf("block").forGetter(Requirement::block),
                Codec.intRange(1, HARD_LIMIT * HARD_LIMIT * HARD_LIMIT).optionalFieldOf("count", 1).forGetter(Requirement::count),
                Codec.STRING.optionalFieldOf("name").forGetter(Requirement::name)
        ).apply(instance, Requirement::new));

        public String describe() {
            return name.orElseGet(block::describe);
        }
    }

    /** What the recorded blocks become once the machine forms. */
    public enum Formed implements net.minecraft.util.StringRepresentable {
        SAME("same"), KEEP("keep");

        public static final Codec<Formed> CODEC = net.minecraft.util.StringRepresentable.fromEnum(Formed::values);
        private final String id;

        Formed(String id) { this.id = id; }

        @Override
        public String getSerializedName() { return id; }
    }

    public static final Codec<FreeBuild> CODEC = RecordCodecBuilder.<FreeBuild>create(instance -> instance.group(
            Codec.intRange(1, HARD_LIMIT).optionalFieldOf("max_width", 7).forGetter(FreeBuild::maxWidth),
            Codec.intRange(1, HARD_LIMIT).optionalFieldOf("max_height", 7).forGetter(FreeBuild::maxHeight),
            Codec.intRange(0, HARD_LIMIT * HARD_LIMIT * HARD_LIMIT).optionalFieldOf("min_blocks", 1).forGetter(FreeBuild::minBlocks),
            BlockMatch.CODEC.listOf().optionalFieldOf("allowed", List.of()).forGetter(FreeBuild::allowed),
            BlockMatch.CODEC.listOf().optionalFieldOf("forbidden", List.of()).forGetter(FreeBuild::forbidden),
            Requirement.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(FreeBuild::requires),
            Formed.CODEC.optionalFieldOf("formed", Formed.SAME).forGetter(FreeBuild::formed),
            Motion.CODEC.listOf().optionalFieldOf("motions", List.of(Motion.values())).forGetter(FreeBuild::motions),
            Trigger.CODEC.validate(when -> when.continuous() ? com.mojang.serialization.DataResult.success(when)
                            : com.mojang.serialization.DataResult.error(() -> "motion_when must be always, idle or working"))
                    .optionalFieldOf("motion_when", Trigger.WORKING).forGetter(FreeBuild::motionWhen),
            Identifier.CODEC.listOf().optionalFieldOf("particles", DEFAULT_PARTICLES).forGetter(FreeBuild::particles),
            Identifier.CODEC.listOf().optionalFieldOf("figures", DEFAULT_FIGURES).forGetter(FreeBuild::figures)
    ).apply(instance, FreeBuild::new));

    /** The rules in a few words, for the Recorder's message: "Up to 7 wide and 7 high. Needs 6 Glass, 1 Cauldron." */
    public String describe() {
        StringBuilder text = new StringBuilder("Up to " + maxWidth + " wide and " + maxHeight + " high");
        if (minBlocks > 1) text.append(", at least ").append(minBlocks).append(" blocks");
        text.append('.');
        if (!requires.isEmpty()) {
            text.append(" Needs ");
            for (int i = 0; i < requires.size(); i++) {
                if (i > 0) text.append(", ");
                text.append(requires.get(i).count()).append(' ').append(requires.get(i).describe());
            }
            text.append('.');
        }
        return text.toString();
    }

    // ---- Checking a build ----

    /**
     * What is wrong with the build in the box from {@code a} to {@code b} (any two opposite corners) around this core,
     * or null when it follows every rule. Only the first problem is named, except for missing required blocks, which
     * are all listed.
     */
    public @Nullable String check(Level level, BlockPos core, BlockPos a, BlockPos b) {
        BlockPos min = BlockPos.min(a, b), max = BlockPos.max(a, b);
        if (core.getX() < min.getX() || core.getY() < min.getY() || core.getZ() < min.getZ()
                || core.getX() > max.getX() || core.getY() > max.getY() || core.getZ() > max.getZ()) {
            return "the marked box must contain the core";
        }
        int width = Math.max(max.getX() - min.getX(), max.getZ() - min.getZ()) + 1, height = max.getY() - min.getY() + 1;
        if (width > maxWidth || height > maxHeight) {
            return "the marked box is " + (max.getX() - min.getX() + 1) + " x " + height + " x " + (max.getZ() - min.getZ() + 1)
                    + ", this machine allows at most " + maxWidth + " wide and " + maxHeight + " high";
        }
        int blocks = 0;
        int[] found = new int[requires.size()];
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (pos.equals(core)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            String where = " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            if (ShellParts.isPart(state)) return "the block" + where + " is already part of a machine";
            if (level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity) return "another machine's core is in the box" + where;
            if (state.getDestroySpeed(level, pos) < 0) return state.getBlock().getName().getString() + where + " can't be used";
            if (CoreTracker.formedCoreAt(level, pos) != null) return "the block" + where + " belongs to another machine";
            if (!allowed.isEmpty() && allowed.stream().noneMatch(match -> match.test(state))
                    || forbidden.stream().anyMatch(match -> match.test(state))) {
                return state.getBlock().getName().getString() + where + " can't be part of this machine";
            }
            blocks++;
            for (int i = 0; i < requires.size(); i++) if (requires.get(i).block().test(state)) found[i]++;
        }
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < requires.size(); i++) {
            int short_ = requires.get(i).count() - found[i];
            if (short_ <= 0) continue;
            missing.append(missing.isEmpty() ? "still needs " : ", ").append(short_).append(" more ").append(requires.get(i).describe());
        }
        if (!missing.isEmpty()) return missing.toString();
        if (blocks < minBlocks) return "it needs at least " + minBlocks + " blocks, the box holds " + blocks;
        return null;
    }

    // ---- Recording a build as a pattern ----

    // The state values a recorded block must keep (the way it's turned); the rest (a crop's age, a leaf's distance,
    // a stair's joined shape) may change by itself without breaking the machine
    private static final Set<String> KEPT_PROPERTIES = Set.of("facing", "axis", "half", "type", "rotation", "hinge", "face",
            "attachment", "vertical_direction", "orientation");

    // Characters for the recorded grid: readable ones first, then any from a quiet Unicode block
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static char symbol(int index) {
        return index < SYMBOLS.length() ? SYMBOLS.charAt(index) : (char) (0x100 + index - SYMBOLS.length());
    }

    /**
     * The build in the box as a pattern of its own: every block exactly as it stands (turned the same way), air as
     * "anything goes", the core as the core. Call {@link #check} first. The pattern is written in world directions
     * (no "front"), since it belongs to this one core.
     */
    public MultiblockPattern record(Level level, BlockPos core, BlockPos a, BlockPos b, Block coreBlock) {
        BlockPos min = BlockPos.min(a, b), max = BlockPos.max(a, b);
        Map<String, PatternKey> key = new LinkedHashMap<>();
        Map<String, ResultKey> resultKey = new LinkedHashMap<>();
        key.put(".", entry(Optional.empty(), Map.of(), false, true));
        key.put("@", entry(Optional.empty(), Map.of(), true, false));
        ResultKey keep = new ResultKey(Optional.empty(), Map.of(), false, true, false, false, false, false);
        ResultKey same = new ResultKey(Optional.empty(), Map.of(), false, false, true, false, false, true);
        resultKey.put(".", keep);
        resultKey.put("s", same);

        Map<String, Character> symbols = new HashMap<>(); // block + kept state -> its character
        List<List<String>> layers = new ArrayList<>(), result = new ArrayList<>();
        for (int y = min.getY(); y <= max.getY(); y++) {
            List<String> rows = new ArrayList<>(), resultRows = new ArrayList<>();
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                StringBuilder row = new StringBuilder(), resultRow = new StringBuilder();
                for (int x = min.getX(); x <= max.getX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (pos.equals(core)) {
                        row.append('@');
                        resultRow.append('.');
                        continue;
                    }
                    if (state.isAir()) {
                        row.append('.');
                        resultRow.append('.');
                        continue;
                    }
                    Map<String, String> kept = keptState(state);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString() + kept;
                    Character c = symbols.get(id);
                    if (c == null) {
                        c = symbol(symbols.size());
                        symbols.put(id, c);
                        key.put(String.valueOf(c), entry(Optional.of(state.getBlock()), kept, false, false));
                    }
                    row.append(c);
                    // Blocks holding things (chests, tanks, batteries...) and liquids stay real: a part would lose what
                    // they hold. Other blocks with data (a spawner, a banner, a sign...) become parts that keep it to show
                    boolean real = formed == Formed.KEEP || holdsThings(level, pos, state) || state.getBlock() instanceof LiquidBlock;
                    resultRow.append(real ? '.' : 's');
                }
                rows.add(row.toString());
                resultRows.add(resultRow.toString());
            }
            layers.add(rows);
            result.add(resultRows);
        }
        return new MultiblockPattern(Optional.of(coreBlock), Optional.empty(), layers, key, Optional.of(result), resultKey, Map.of());
    }

    /** Whether the block holds items, fluid or energy (its own inventory, or what other mods can reach). */
    private static boolean holdsThings(Level level, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) return false;
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container) return true;
        return level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, pos, null) != null
                || level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK, pos, null) != null
                || level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Energy.BLOCK, pos, null) != null;
    }

    private static PatternKey entry(Optional<Block> block, Map<String, String> state, boolean core, boolean any) {
        return new PatternKey(block, Optional.empty(), state, false, false, core, any, false, Optional.empty(), Optional.empty(),
                Optional.empty(), true);
    }

    private static Map<String, String> keptState(BlockState state) {
        Map<String, String> kept = new java.util.TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            if (KEPT_PROPERTIES.contains(property.getName())) kept.put(property.getName(), valueName(state, property));
        }
        return kept;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
