package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.block.ChamberShellBlock;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Optional;

/**
 * One entry of a multiblock JSON's "result_key": what a character of the "result" grid turns into
 * once the chamber forms.
 * <ul>
 *   <li>{@code keep}: leave the block as the player placed it (the matrix, diamonds, air...)</li>
 *   <li>{@code hidden}: with block or same: the part keeps its shape and stays part of the machine, but draws
 *       nothing once formed (e.g. windows that vanish so the inside shows)</li>
 *   <li>{@code same}: become a chamber shell that looks exactly like the block that was there (same look, but
 *       now a machine part: no drops, opens the machine, pipes reach the core)</li>
 *   <li>{@code block}: become a chamber shell that looks like this block (any block id, vanilla or modded)</li>
 *   <li>{@code state}: optional block state values for the look, e.g. {"axis": "y"}</li>
 *   <li>{@code copy_state}: copy matching block state values from the block that was there
 *       (e.g. stairs keep their facing, half and corner shape). Applied before {@code state}.</li>
 *   <li>{@code real}: with block: place the real block instead of a shell (for blocks whose flames or particles
 *       must show, like candles or a spore blossom; usually on an air cell, so it "appears" on switch). Removed
 *       without drops when the chamber breaks.</li>
 *   <li>{@code restore}: with same: when the machine breaks, the original block comes back instead of being destroyed
 *       (for valuable blocks absorbed by a machine, like the machine cores inside the alchemical nexus; usually with
 *       hidden). Breaking the shell itself drops the original block.</li>
 * </ul>
 */
public record ResultKey(Optional<Block> block, Map<String, String> state, boolean copyState, boolean keep, boolean same,
                        boolean hidden, boolean real, boolean restore) {

    private static final Codec<String> STATE_VALUE = Codec.withAlternative(Codec.STRING,
            Codec.withAlternative(Codec.BOOL.xmap(String::valueOf, Boolean::parseBoolean),
                    Codec.INT.xmap(String::valueOf, Integer::parseInt)));

    public static final Codec<ResultKey> CODEC = RecordCodecBuilder.<ResultKey>create(instance -> instance.group(
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("block").forGetter(ResultKey::block),
            Codec.unboundedMap(Codec.STRING, STATE_VALUE).optionalFieldOf("state", Map.of()).forGetter(ResultKey::state),
            Codec.BOOL.optionalFieldOf("copy_state", false).forGetter(ResultKey::copyState),
            Codec.BOOL.optionalFieldOf("keep", false).forGetter(ResultKey::keep),
            Codec.BOOL.optionalFieldOf("same", false).forGetter(ResultKey::same),
            Codec.BOOL.optionalFieldOf("hidden", false).forGetter(ResultKey::hidden),
            Codec.BOOL.optionalFieldOf("real", false).forGetter(ResultKey::real),
            Codec.BOOL.optionalFieldOf("restore", false).forGetter(ResultKey::restore)
    ).apply(instance, ResultKey::new)).validate(ResultKey::validate);

    private static DataResult<ResultKey> validate(ResultKey key) {
        int kinds = (key.keep ? 1 : 0) + (key.same ? 1 : 0) + (key.block.isPresent() ? 1 : 0);
        if (kinds != 1) {
            return DataResult.error(() -> "each result_key entry needs exactly one of block, same or keep");
        }
        if (key.keep && key.hidden) {
            return DataResult.error(() -> "hidden doesn't work with keep (only chamber parts can be hidden)");
        }
        if (key.restore && !key.same) {
            return DataResult.error(() -> "restore only works with same (the original block is what comes back)");
        }
        if (key.real && (key.block.isEmpty() || key.hidden)) {
            return DataResult.error(() -> "real only works with block (and not with hidden)");
        }
        if ((key.keep || key.same) && (key.copyState || !key.state.isEmpty())) {
            return DataResult.error(() -> "state / copy_state only work with block");
        }
        if (key.block.isPresent()) {
            Block block = key.block.get();
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (block instanceof ChamberShellBlock || block.defaultBlockState().isAir()) {
                return DataResult.error(() -> "result block can't be " + id + " (use \"keep\": true to leave a position as is)");
            }
            for (Map.Entry<String, String> entry : key.state.entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                if (property == null) {
                    return DataResult.error(() -> "block " + id + " has no property \"" + entry.getKey() + "\"");
                }
                if (property.getValue(entry.getValue()).isEmpty()) {
                    return DataResult.error(() -> "\"" + entry.getValue() + "\" is not a valid value of " + entry.getKey() + " for " + id);
                }
            }
        }
        return DataResult.success(key);
    }

    /** The block state the shell will look like, given the block that was at this position. Only call when !keep. */
    public BlockState disguiseFor(BlockState original) {
        if (same) return original;
        BlockState result = block.orElseThrow().defaultBlockState();
        if (copyState) {
            for (Property<?> property : original.getProperties()) {
                Property<?> target = result.getBlock().getStateDefinition().getProperty(property.getName());
                if (target != null) result = withValue(result, target, valueName(original, property));
            }
        }
        for (Map.Entry<String, String> entry : state.entrySet()) {
            Property<?> target = result.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (target != null) result = withValue(result, target, entry.getValue());
        }
        return result;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }
}
