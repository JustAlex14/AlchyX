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

import java.util.List;
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
 *   <li>{@link Kind#ITEM}: the item one of the machine's displays shows ("input", "output", "result") floats there;
 *       with {@code to}, it travels from this spot to that one as the work goes on.</li>
 * </ul>
 * {@code what} is the particle type, the entity type or the display.
 */
public record StudioMark(BlockPos offset, Kind kind, String what, int yaw, Optional<BlockPos> to) {
    public enum Kind implements StringRepresentable {
        PARTICLES("particles"), FIGURE("figure"), ITEM("item");

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
    /** The displays an item anchor may show (what script machines and most mods set). */
    public static final List<String> DISPLAYS = List.of("input", "output", "result");

    public StudioMark(BlockPos offset, Kind kind, String what, int yaw) {
        this(offset, kind, what, yaw, Optional.empty());
    }

    public StudioMark withWhat(String what) {
        return new StudioMark(offset, kind, what, yaw, to);
    }

    public StudioMark withYaw(int yaw) {
        return new StudioMark(offset, kind, what, yaw, to);
    }

    public StudioMark withTo(Optional<BlockPos> to) {
        return new StudioMark(offset, kind, what, yaw, to);
    }

    /** The same mark, its spots moved by this much. */
    public StudioMark shifted(BlockPos by) {
        return new StudioMark(offset.offset(by), kind, what, yaw, to.map(t -> t.offset(by)));
    }

    /** The particle or entity type it names, if {@code what} is an id. */
    public @Nullable Identifier id() {
        return Identifier.tryParse(what);
    }

    public static final Codec<StudioMark> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("offset").forGetter(StudioMark::offset),
            Kind.CODEC.fieldOf("kind").forGetter(StudioMark::kind),
            Codec.STRING.fieldOf("id").forGetter(StudioMark::what),
            Codec.INT.optionalFieldOf("yaw", 0).forGetter(StudioMark::yaw),
            BlockPos.CODEC.optionalFieldOf("to").forGetter(StudioMark::to)
    ).apply(instance, StudioMark::new));

    public static final StreamCodec<ByteBuf, StudioMark> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StudioMark::offset,
            ByteBufCodecs.idMapper(i -> Kind.values()[Math.floorMod(i, Kind.values().length)], Kind::ordinal), StudioMark::kind,
            ByteBufCodecs.stringUtf8(256), StudioMark::what,
            ByteBufCodecs.VAR_INT, StudioMark::yaw,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), StudioMark::to,
            StudioMark::new);

    /** The particle to show, if the id names a plain particle type (one that needs no extra settings). */
    public static @Nullable ParticleOptions particle(@Nullable Identifier id) {
        if (id == null) return null;
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null) instanceof SimpleParticleType simple ? simple : null;
    }

    /**
     * As an animation effect on the spot named by {@code anchor} (and {@code toAnchor}, the spot an item travels to);
     * particles and items play while {@code when} holds.
     */
    public @Nullable Effect effect(String anchor, @Nullable String toAnchor, Trigger when) {
        Target at = new Target(Optional.of(anchor), Optional.empty(), Map.of(), Vec3.ZERO);
        Identifier id = id();
        return switch (kind) {
            case PARTICLES -> {
                ParticleOptions particle = particle(id);
                if (particle == null) yield null;
                yield new Effect.Particle(new Timing(when, 4, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE), Optional.of(particle),
                        Optional.empty(), at, Optional.empty(), new Vec3(0.25, 0.2, 0.25), 2, 0.01, Effect.ParticleStyle.SPREAD, 0.91);
            }
            case FIGURE -> id == null ? null : new Effect.Figure(new Timing(Trigger.ALWAYS, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE),
                    Optional.of(id), Optional.empty(), Optional.empty(), at, Optional.empty(), Optional.of((float) yaw), false,
                    0.0F, 0.0F, 1.0F, false, Optional.empty(), Optional.empty());
            case ITEM -> new Effect.FloatingItem(new Timing(when, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE), what, at,
                    to.isPresent() ? Effect.ItemMotion.STILL : Effect.ItemMotion.SPIN, 1.0F, Optional.of(4.0F), 0.5F, 1, 0, 0.5F,
                    to.isPresent() ? 0.0F : 0.05F, 0.0F,
                    toAnchor == null ? Optional.empty() : Optional.of(new Target(Optional.of(toAnchor), Optional.empty(), Map.of(), Vec3.ZERO)));
        };
    }
}
