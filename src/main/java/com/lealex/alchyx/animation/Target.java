package com.lealex.alchyx.animation;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;

/**
 * Where an effect happens, in the pattern's own coordinates (turned with the machine like the pattern):
 * <ul>
 *   <li>{@code "core"} or an anchor name from the pattern file ({@code "wormhole"}): the center of that block;</li>
 *   <li>{@code [x, y, z]}: an offset in blocks from the center of the core block (decimals allowed);</li>
 *   <li>{@code {"block": "minecraft:cauldron"}} or {@code {"block": "#minecraft:campfires", "state": {...}}}:
 *       every block of the structure that looks like that (a shell's disguise counts);</li>
 *   <li>the object form takes {@code "anchor"} or {@code "block"} plus an optional {@code "offset": [x, y, z]}.</li>
 * </ul>
 */
public record Target(Optional<String> anchor, Optional<String> block, Map<String, String> state, Vec3 offset) {

    public static final Target CORE = new Target(Optional.of("core"), Optional.empty(), Map.of(), Vec3.ZERO);

    private static final Codec<Target> OBJECT = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("anchor").forGetter(Target::anchor),
            Codec.STRING.optionalFieldOf("block").forGetter(Target::block),
            BlockLook.STATE.optionalFieldOf("state", Map.of()).forGetter(Target::state),
            Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(Target::offset)
    ).apply(instance, Target::new));

    public static final Codec<Target> CODEC = Codec.withAlternative(OBJECT,
            Codec.either(Codec.STRING, Vec3.CODEC).xmap(
                    either -> either.map(
                            name -> new Target(Optional.of(name), Optional.empty(), Map.of(), Vec3.ZERO),
                            offset -> new Target(Optional.of("core"), Optional.empty(), Map.of(), offset)),
                    target -> Either.left(target.anchor().orElse("core"))));

    /** For block selectors: does this look match? */
    public boolean matches(BlockState look) {
        if (block.isEmpty()) return false;
        String name = block.get();
        boolean blockOk;
        if (name.startsWith("#")) {
            blockOk = look.is(TagKey.create(Registries.BLOCK, Identifier.parse(name.substring(1))));
        } else {
            blockOk = BuiltInRegistries.BLOCK.getKey(look.getBlock()).equals(Identifier.parse(name));
        }
        return blockOk && BlockLook.matches(look, state);
    }
}
