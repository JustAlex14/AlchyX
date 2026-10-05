package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * One animation file: {@code data/<namespace>/machine_animation/<name>.json}.
 * <pre>
 * {
 *   "machine": "alchymastery:destructuration_chamber",   (optional: the pattern id; default = this file's id)
 *   "replace": false,                                     (optional: true drops effects of earlier files for it)
 *   "effects": [ {"type": "sound", "when": "finish", "sound": "minecraft:block.grindstone.use"}, ... ]
 * }
 * </pre>
 * Several files may target the same machine (a datapack can add effects to a modded machine); their effects add up
 * unless one says "replace": true. See {@link Effect} for the effect kinds.
 */
public record MachineAnimation(Optional<Identifier> machine, boolean replace, List<Effect> effects) {
    public static final Codec<MachineAnimation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.optionalFieldOf("machine").forGetter(MachineAnimation::machine),
            Codec.BOOL.optionalFieldOf("replace", false).forGetter(MachineAnimation::replace),
            Effect.CODEC.listOf().fieldOf("effects").forGetter(MachineAnimation::effects)
    ).apply(instance, MachineAnimation::new));
}
