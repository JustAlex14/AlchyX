package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;

/**
 * A block to draw: "minecraft:piston_head" or {"block": "minecraft:piston_head", "state": {"short": true}}.
 * Unknown property names or values are ignored.
 */
public record BlockLook(Block block, Map<String, String> state) {

    /** A state value written as a string, a boolean or a number. */
    public static final Codec<String> STATE_VALUE = Codec.withAlternative(Codec.STRING,
            Codec.withAlternative(Codec.BOOL.xmap(String::valueOf, Boolean::parseBoolean),
                    Codec.INT.xmap(String::valueOf, Integer::parseInt)));
    public static final Codec<Map<String, String>> STATE = Codec.unboundedMap(Codec.STRING, STATE_VALUE);

    private static final Codec<BlockLook> OBJECT = RecordCodecBuilder.create(instance -> instance.group(
            BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(BlockLook::block),
            STATE.optionalFieldOf("state", Map.of()).forGetter(BlockLook::state)
    ).apply(instance, BlockLook::new));

    public static final Codec<BlockLook> CODEC = Codec.withAlternative(OBJECT,
            BuiltInRegistries.BLOCK.byNameCodec().xmap(block -> new BlockLook(block, Map.of()), BlockLook::block));

    public BlockState build() {
        return apply(block.defaultBlockState(), state);
    }

    /** The state with these property values set (by name; unknown names or values are skipped). */
    public static BlockState apply(BlockState state, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property != null) state = withValue(state, property, entry.getValue());
        }
        return state;
    }

    /** True if every listed property of the state has that value. */
    public static boolean matches(BlockState state, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !valueName(state, property).equals(entry.getValue())) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
