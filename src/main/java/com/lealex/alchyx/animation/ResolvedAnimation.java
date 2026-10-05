package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a client needs to play a machine's animations: its effects plus the bits of its pattern they refer to
 * (front for rotation, anchors, structure box). Patterns are server data, so the server sends this.
 */
public record ResolvedAnimation(Identifier machine, Optional<Direction> front, Map<String, BlockPos> anchors,
                                BlockPos boxMin, BlockPos boxMax, List<Effect> effects) {
    public static final Codec<ResolvedAnimation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("machine").forGetter(ResolvedAnimation::machine),
            Direction.CODEC.optionalFieldOf("front").forGetter(ResolvedAnimation::front),
            Codec.unboundedMap(Codec.STRING, BlockPos.CODEC).fieldOf("anchors").forGetter(ResolvedAnimation::anchors),
            BlockPos.CODEC.fieldOf("box_min").forGetter(ResolvedAnimation::boxMin),
            BlockPos.CODEC.fieldOf("box_max").forGetter(ResolvedAnimation::boxMax),
            Effect.CODEC.listOf().fieldOf("effects").forGetter(ResolvedAnimation::effects)
    ).apply(instance, ResolvedAnimation::new));
}
