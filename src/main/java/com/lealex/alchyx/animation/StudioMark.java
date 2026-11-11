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
 * {@code what} is the particle type, the entity type or the display. {@link Times}: when in the work it shows, and
 * when a traveling item leaves and arrives.
 */
public record StudioMark(BlockPos offset, Kind kind, String what, int yaw, Optional<BlockPos> to, Times times, List<Identifier> items) {

    /** The most items an item anchor goes through. */
    public static final int MAX_ITEMS = 16;

    /**
     * When things happen, in percent of the machine's operation: it shows from {@code showFrom} to {@code showUntil}
     * (0 to 100: all the time, even while the machine rests), and an item that travels leaves its spot at
     * {@code leave} and reaches the other at {@code arrive}.
     */
    public record Times(int showFrom, int showUntil, int leave, int arrive) {
        public static final Times ALWAYS = new Times(0, 100, 0, 100);

        public static final Codec<Times> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 100).optionalFieldOf("show_from", 0).forGetter(Times::showFrom),
                Codec.intRange(0, 100).optionalFieldOf("show_until", 100).forGetter(Times::showUntil),
                Codec.intRange(0, 100).optionalFieldOf("leave", 0).forGetter(Times::leave),
                Codec.intRange(0, 100).optionalFieldOf("arrive", 100).forGetter(Times::arrive)
        ).apply(instance, Times::new));

        public static final StreamCodec<ByteBuf, Times> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Times::showFrom, ByteBufCodecs.VAR_INT, Times::showUntil,
                ByteBufCodecs.VAR_INT, Times::leave, ByteBufCodecs.VAR_INT, Times::arrive, Times::new);

        /** The same times kept in 0 to 100, each pair in order. */
        public Times tidy() {
            int a = clamp(showFrom), b = clamp(showUntil), c = clamp(leave), d = clamp(arrive);
            return new Times(Math.min(a, b), Math.max(a, b), Math.min(c, d), Math.max(c, d));
        }

        private static int clamp(int percent) {
            return Math.max(0, Math.min(100, percent));
        }

        public boolean shownAlways() {
            return showFrom <= 0 && showUntil >= 100;
        }

        /** Whether it shows at this point (0 to 1) of the work. */
        public boolean shows(float progress) {
            return shownAlways() || (progress * 100 >= showFrom && progress * 100 <= showUntil);
        }

        /** How far along its way a traveling item is at this point (0 to 1) of the work. */
        public float travel(float progress) {
            float start = leave / 100.0F, end = arrive / 100.0F;
            if (end - start < 1e-4F) return progress >= end ? 1 : 0;
            return Math.max(0, Math.min(1, (progress - start) / (end - start)));
        }
    }
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
        this(offset, kind, what, yaw, Optional.empty(), Times.ALWAYS, List.of());
    }

    public StudioMark withWhat(String what) {
        return new StudioMark(offset, kind, what, yaw, to, times, items);
    }

    public StudioMark withYaw(int yaw) {
        return new StudioMark(offset, kind, what, yaw, to, times, items);
    }

    public StudioMark withTo(Optional<BlockPos> to) {
        return new StudioMark(offset, kind, what, yaw, to, times, items);
    }

    public StudioMark withTimes(Times times) {
        return new StudioMark(offset, kind, what, yaw, to, times.tidy(), items);
    }

    public StudioMark withItems(List<Identifier> items) {
        return new StudioMark(offset, kind, what, yaw, to, times, List.copyOf(items));
    }

    /** The same mark, its spots moved by this much. */
    public StudioMark shifted(BlockPos by) {
        return new StudioMark(offset.offset(by), kind, what, yaw, to.map(t -> t.offset(by)), times, items);
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
            BlockPos.CODEC.optionalFieldOf("to").forGetter(StudioMark::to),
            Times.CODEC.optionalFieldOf("times", Times.ALWAYS).forGetter(StudioMark::times),
            Identifier.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(StudioMark::items)
    ).apply(instance, StudioMark::new));

    public static final StreamCodec<ByteBuf, StudioMark> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StudioMark::offset,
            ByteBufCodecs.idMapper(i -> Kind.values()[Math.floorMod(i, Kind.values().length)], Kind::ordinal), StudioMark::kind,
            ByteBufCodecs.stringUtf8(256), StudioMark::what,
            ByteBufCodecs.VAR_INT, StudioMark::yaw,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), StudioMark::to,
            Times.STREAM_CODEC, StudioMark::times,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ITEMS)), StudioMark::items,
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
        float from = times.showFrom() / 100.0F, until = times.showUntil() / 100.0F;
        return switch (kind) {
            case PARTICLES -> {
                ParticleOptions particle = particle(id);
                if (particle == null) yield null;
                yield new Effect.Particle(new Timing(when, 4, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until), Optional.of(particle),
                        Optional.empty(), at, Optional.empty(), new Vec3(0.25, 0.2, 0.25), 2, 0.01, Effect.ParticleStyle.SPREAD, 0.91);
            }
            case FIGURE -> id == null ? null : new Effect.Figure(new Timing(Trigger.ALWAYS, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until),
                    Optional.of(id), Optional.empty(), Optional.empty(), at, Optional.empty(), Optional.of((float) yaw), false,
                    0.0F, 0.0F, 1.0F, false, Optional.empty(), Optional.empty());
            case ITEM -> new Effect.FloatingItem(new Timing(when, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until), what, at,
                    to.isPresent() ? Effect.ItemMotion.STILL : Effect.ItemMotion.SPIN, 1.0F, Optional.of(4.0F), 0.5F, 1, 0, 0.5F,
                    to.isPresent() ? 0.0F : 0.05F, 0.0F,
                    toAnchor == null ? Optional.empty() : Optional.of(new Target(Optional.of(toAnchor), Optional.empty(), Map.of(), Vec3.ZERO)),
                    List.of(times.leave() / 100.0F, times.arrive() / 100.0F),
                    items.stream().map(item -> BuiltInRegistries.ITEM.getOptional(item).orElse(null))
                            .filter(java.util.Objects::nonNull).toList());
        };
    }
}
