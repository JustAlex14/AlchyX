package com.lealex.alchyx.multiblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * One entry of a multiblock JSON's "key": what the player must place at a character of the layer grid.
 * (What it turns into once the chamber forms is decided by the "result" grid, see {@link ResultKey}.)
 *
 * Exactly one of block / tag / air / core / any must be given.
 * <ul>
 *   <li>{@code block}: a block id, e.g. "minecraft:glowstone"</li>
 *   <li>{@code tag}: a block tag id, e.g. "alchymastery:distortion_chamber_frame"</li>
 *   <li>{@code state}: optional block state values that must match, e.g. {"lit": "true"}; several allowed values
 *       are separated by "|", e.g. {"level": "2|1"} (the structure builder places the first one)</li>
 *   <li>{@code air}: built empty (the builder and previews leave it empty), but anything the player puts there
 *       later is fine (a tap on a cauldron, a torch...): it never breaks the machine, and a result there only fills
 *       it while it's empty; {@code any}: anything goes</li>
 *   <li>{@code core}: the machine's core block, named by the pattern's top-level "core" (exactly one in the grid).
 *       {@code matrix} is an older name for the same thing.</li>
 *   <li>{@code consumed}: destroyed (no drops) when the chamber breaks, like the diamond blocks</li>
 *   <li>{@code stairs}: orientation rule for stairs (face_center, roof_edge, roof_corner; the edge rules work for
 *       any outer rim, e.g. a floor rim too)</li>
 *   <li>{@code facing}: for blocks with a "facing" property (pistons, wall torches, observers...): "center" =
 *       must point toward the core, "outward" = away from it</li>
 *   <li>{@code facing_check} (default true): false = the facing rule only orients what the Structure Builder
 *       places (and previews); the structure check accepts any facing</li>
 *   <li>{@code name}: how the block is called in the "... needed at" message</li>
 * </ul>
 */
public record PatternKey(
        Optional<Block> block,
        Optional<TagKey<Block>> tag,
        Map<String, String> state,
        boolean air,
        boolean matrix,
        boolean core,
        boolean any,
        boolean consumed,
        Optional<StairRule> stairs,
        Optional<FacingRule> facing,
        Optional<String> name,
        boolean facingCheck) {

    /** How stairs at this symbol must be oriented (see {@link MultiblockPattern}). */
    public enum StairRule implements StringRepresentable {
        FACE_CENTER("face_center"), ROOF_EDGE("roof_edge"), ROOF_CORNER("roof_corner");

        public static final Codec<StairRule> CODEC = StringRepresentable.fromEnum(StairRule::values);
        private final String id;

        StairRule(String id) { this.id = id; }

        @Override
        public String getSerializedName() { return id; }
    }

    /** Which way a block with a "facing" property must point. */
    public enum FacingRule implements StringRepresentable {
        CENTER("center"), OUTWARD("outward");

        public static final Codec<FacingRule> CODEC = StringRepresentable.fromEnum(FacingRule::values);
        private final String id;

        FacingRule(String id) { this.id = id; }

        @Override
        public String getSerializedName() { return id; }
    }

    /** State values may be written as strings ("true") or plain JSON booleans/numbers (true). */
    private static final Codec<String> STATE_VALUE = Codec.withAlternative(Codec.STRING,
            Codec.withAlternative(Codec.BOOL.xmap(String::valueOf, Boolean::parseBoolean),
                    Codec.INT.xmap(String::valueOf, Integer::parseInt)));

    public static final Codec<PatternKey> CODEC = RecordCodecBuilder.<PatternKey>create(instance -> instance.group(
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("block").forGetter(PatternKey::block),
            TagKey.codec(Registries.BLOCK).optionalFieldOf("tag").forGetter(PatternKey::tag),
            Codec.unboundedMap(Codec.STRING, STATE_VALUE).optionalFieldOf("state", Map.of()).forGetter(PatternKey::state),
            Codec.BOOL.optionalFieldOf("air", false).forGetter(PatternKey::air),
            Codec.BOOL.optionalFieldOf("matrix", false).forGetter(PatternKey::matrix),
            Codec.BOOL.optionalFieldOf("core", false).forGetter(PatternKey::core),
            Codec.BOOL.optionalFieldOf("any", false).forGetter(PatternKey::any),
            Codec.BOOL.optionalFieldOf("consumed", false).forGetter(PatternKey::consumed),
            StairRule.CODEC.optionalFieldOf("stairs").forGetter(PatternKey::stairs),
            FacingRule.CODEC.optionalFieldOf("facing").forGetter(PatternKey::facing),
            Codec.STRING.optionalFieldOf("name").forGetter(PatternKey::name),
            Codec.BOOL.optionalFieldOf("facing_check", true).forGetter(PatternKey::facingCheck)
    ).apply(instance, PatternKey::new)).validate(PatternKey::validate);

    private static DataResult<PatternKey> validate(PatternKey key) {
        int kinds = (key.block.isPresent() ? 1 : 0) + (key.tag.isPresent() ? 1 : 0)
                + (key.air ? 1 : 0) + (key.isCore() ? 1 : 0) + (key.any ? 1 : 0);
        if (kinds != 1) {
            return DataResult.error(() -> "each key entry needs exactly one of block, tag, air, core or any");
        }
        if (!key.state.isEmpty() && key.block.isEmpty() && key.tag.isEmpty()) {
            return DataResult.error(() -> "\"state\" only works with block or tag");
        }
        if ((key.consumed || key.stairs.isPresent() || key.facing.isPresent()) && key.block.isEmpty() && key.tag.isEmpty()) {
            return DataResult.error(() -> "consumed / stairs / facing only work with block or tag");
        }
        if (key.block.isPresent()) {
            for (String property : key.state.keySet()) {
                if (key.block.get().getStateDefinition().getProperty(property) == null) {
                    return DataResult.error(() -> "block " + BuiltInRegistries.BLOCK.getKey(key.block.get()) + " has no property \"" + property + "\"");
                }
            }
        }
        return DataResult.success(key);
    }

    /** True for the machine's core position ("core": true, or the older "matrix": true). */
    public boolean isCore() {
        return core || matrix;
    }

    /** True if the state is the block this entry asks for (not the chamber part it becomes). */
    public boolean matchesOriginal(BlockState state, Block coreBlock) {
        if (air) return true; // left empty when building; anything placed there later never breaks the machine
        if (any) return true;
        if (isCore()) return state.is(coreBlock);
        boolean blockOk = block.map(b -> state.is(b)).orElse(false) || tag.map(t -> state.is(t)).orElse(false);
        return blockOk && stateMatches(state);
    }

    private boolean stateMatches(BlockState state) {
        for (Map.Entry<String, String> wanted : this.state.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(wanted.getKey());
            if (property == null || !valueMatches(state, property, wanted.getValue())) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> boolean valueMatches(BlockState state, Property<T> property, String value) {
        for (String option : value.split("\\|")) {
            if (property.getValue(option.trim()).map(v -> v.equals(state.getValue(property))).orElse(false)) return true;
        }
        return false;
    }

    /** Text used in "... needed at x y z". */
    public String describe(Block coreBlock) {
        if (name.isPresent()) return name.get();
        if (air) return "air";
        if (isCore()) return BuiltInRegistries.BLOCK.getKey(coreBlock).toString();
        if (block.isPresent()) return BuiltInRegistries.BLOCK.getKey(block.get()).toString();
        return tag.map(t -> "a block from #" + t.location()).orElse("something");
    }

    /**
     * A block state that satisfies this entry, for the structure builder: the block (or the first block of the tag,
     * or air) with the stated state values. Null for core/any or an empty tag.
     */
    public @Nullable BlockState exampleState() {
        BlockState example;
        if (air) {
            example = Blocks.AIR.defaultBlockState();
        } else if (block.isPresent()) {
            example = block.get().defaultBlockState();
        } else if (tag.isPresent()) {
            Iterator<Holder<Block>> blocks = BuiltInRegistries.BLOCK.getTagOrEmpty(tag.get()).iterator();
            if (!blocks.hasNext()) return null;
            example = blocks.next().value().defaultBlockState();
        } else {
            return null;
        }
        for (Map.Entry<String, String> wanted : state.entrySet()) {
            Property<?> property = example.getBlock().getStateDefinition().getProperty(wanted.getKey());
            if (property != null) example = withValue(example, property, wanted.getValue());
        }
        return example;
    }

    /**
     * Every block state that satisfies this entry, for the structure builder in survival (which uses whichever the
     * player carries): the block, or each block of the tag, with the stated state values. Empty for core/any/air.
     */
    public java.util.List<BlockState> exampleStates() {
        java.util.List<BlockState> found = new java.util.ArrayList<>();
        if (block.isPresent()) {
            found.add(withStated(block.get().defaultBlockState()));
        } else if (tag.isPresent()) {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag.get())) found.add(withStated(holder.value().defaultBlockState()));
        }
        return found;
    }

    private BlockState withStated(BlockState example) {
        for (Map.Entry<String, String> wanted : state.entrySet()) {
            Property<?> property = example.getBlock().getStateDefinition().getProperty(wanted.getKey());
            if (property != null) example = withValue(example, property, wanted.getValue());
        }
        return example;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        String first = value.split("\\|")[0].trim();
        return property.getValue(first).map(v -> state.setValue(property, v)).orElse(state);
    }
}
