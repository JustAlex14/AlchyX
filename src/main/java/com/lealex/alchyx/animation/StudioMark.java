package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * An anchor a player put in a free-build machine with the Machine Studio: a spot (an offset from the core, often
 * an empty cell inside or beside the build) where something plays.
 * <ul>
 *   <li>{@link Kind#PARTICLES}: particles of that type rise there while the machine works (or rests, as the machine
 *       says);</li>
 *   <li>{@link Kind#FIGURE}: an entity of that type stands there, turned by {@code yaw} degrees (only drawn: no AI,
 *       no collision).</li>
 * </ul>
 */
public record StudioMark(BlockPos offset, Kind kind, Identifier id, int yaw) {
    public enum Kind implements StringRepresentable {
        PARTICLES("particles"), FIGURE("figure");

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** The most anchors one machine keeps. */
    public static final int MAX = 24;

    public static final Codec<StudioMark> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("offset").forGetter(StudioMark::offset),
            Kind.CODEC.fieldOf("kind").forGetter(StudioMark::kind),
            Identifier.CODEC.fieldOf("id").forGetter(StudioMark::id),
            Codec.INT.optionalFieldOf("yaw", 0).forGetter(StudioMark::yaw)
    ).apply(instance, StudioMark::new));

    public static final StreamCodec<ByteBuf, StudioMark> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StudioMark::offset,
            ByteBufCodecs.idMapper(i -> Kind.values()[Math.floorMod(i, Kind.values().length)], Kind::ordinal), StudioMark::kind,
            Identifier.STREAM_CODEC, StudioMark::id,
            ByteBufCodecs.VAR_INT, StudioMark::yaw,
            StudioMark::new);

    /** The particle to show, if the id names a plain particle type (one that needs no extra settings). */
    public static @Nullable ParticleOptions particle(Identifier id) {
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null) instanceof SimpleParticleType simple ? simple : null;
    }

    /** As an animation effect on the spot named by this anchor; particles play while {@code when} holds. */
    public @Nullable Effect effect(String anchor, Trigger when) {
        Target at = new Target(Optional.of(anchor), Optional.empty(), Map.of(), Vec3.ZERO);
        return switch (kind) {
            case PARTICLES -> {
                ParticleOptions particle = particle(id);
                if (particle == null) yield null;
                yield new Effect.Particle(new Timing(when, 4, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE), Optional.of(particle),
                        Optional.empty(), at, Optional.empty(), new Vec3(0.25, 0.2, 0.25), 2, 0.01, Effect.ParticleStyle.SPREAD, 0.91);
            }
            case FIGURE -> new Effect.Figure(new Timing(Trigger.ALWAYS, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE),
                    Optional.of(id), Optional.empty(), Optional.empty(), at, Optional.empty(), Optional.of((float) yaw), false,
                    0.0F, 0.0F, 1.0F, false, Optional.empty(), Optional.empty());
        };
    }
}
