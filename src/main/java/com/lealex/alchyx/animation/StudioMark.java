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

import java.util.ArrayList;
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
 *   <li>{@link Kind#ITEM}: items float there. {@code items} lists them (item ids, or a display's name: "input",
 *       "output", "result"), each with its own {@link ItemShow}: when in the work it shows and how it moves (spins,
 *       stays still, bobs, or travels to {@code to} through its time). Without items, the display {@code what}
 *       floats there (and travels to {@code to}, leaving and arriving as {@link Times} say).</li>
 * </ul>
 * {@code what} is the particle type, the entity type or the display. {@link Times}: when in the work it shows, and
 * when a traveling item leaves and arrives.
 */
public record StudioMark(BlockPos offset, Kind kind, String what, int yaw, Optional<BlockPos> to, Times times, List<String> items,
                         List<ItemShow> shows) {

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
            int a = clampStatic(showFrom), b = clampStatic(showUntil), c = clampStatic(leave), d = clampStatic(arrive);
            return new Times(Math.min(a, b), Math.max(a, b), Math.min(c, d), Math.max(c, d));
        }

        static int clampStatic(int percent) {
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
            return Times.along(progress, leave, arrive);
        }

        /** How far from {@code from} to {@code until} (percent) this point (0 to 1) of the work is, 0 to 1. */
        static float along(float progress, int from, int until) {
            float start = from / 100.0F, end = until / 100.0F;
            if (end - start < 1e-4F) return progress >= end ? 1 : 0;
            return Math.max(0, Math.min(1, (progress - start) / (end - start)));
        }
    }

    /** How one item of an item anchor moves while it shows. */
    public enum Style implements StringRepresentable {
        SPIN("spin"), STILL("still"), BOB("bob"), TRAVEL("travel");

        public static final Codec<Style> CODEC = StringRepresentable.fromEnum(Style::values);
        private final String name;

        Style(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * One item's time and way of moving: it shows from {@code from} to {@code until} (percent of the work) and
     * {@code style} says how it moves; a traveling one goes from the anchor to its "to" spot through that time.
     */
    public record ItemShow(int from, int until, Style style) {
        public static final Codec<ItemShow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 100).optionalFieldOf("from", 0).forGetter(ItemShow::from),
                Codec.intRange(0, 100).optionalFieldOf("until", 100).forGetter(ItemShow::until),
                Style.CODEC.optionalFieldOf("style", Style.SPIN).forGetter(ItemShow::style)
        ).apply(instance, ItemShow::new));

        public static final StreamCodec<ByteBuf, ItemShow> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ItemShow::from, ByteBufCodecs.VAR_INT, ItemShow::until,
                ByteBufCodecs.idMapper(i -> Style.values()[Math.floorMod(i, Style.values().length)], Style::ordinal), ItemShow::style,
                ItemShow::new);

        public ItemShow tidy() {
            int a = Times.clampStatic(from), b = Times.clampStatic(until);
            return new ItemShow(Math.min(a, b), Math.max(a, b), style);
        }

        public boolean shows(float progress) {
            return progress * 100 >= from && progress * 100 <= until;
        }

        /** How far along its way a traveling item is at this point (0 to 1) of the work. */
        public float travel(float progress) {
            return Times.along(progress, from, until);
        }

        public ItemShow withFrom(int from) {
            return new ItemShow(from, until, style);
        }

        public ItemShow withUntil(int until) {
            return new ItemShow(from, until, style);
        }

        public ItemShow withStyle(Style style) {
            return new ItemShow(from, until, style);
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
        this(offset, kind, what, yaw, Optional.empty(), Times.ALWAYS, List.of(), List.of());
    }

    public StudioMark withWhat(String what) {
        return new StudioMark(offset, kind, what, yaw, to, times, items, shows);
    }

    public StudioMark withYaw(int yaw) {
        return new StudioMark(offset, kind, what, yaw, to, times, items, shows);
    }

    public StudioMark withTo(Optional<BlockPos> to) {
        return new StudioMark(offset, kind, what, yaw, to, times, items, shows);
    }

    public StudioMark withTimes(Times times) {
        return new StudioMark(offset, kind, what, yaw, to, times.tidy(), items, shows);
    }

    /** With these items, each with its show (as many of them). */
    public StudioMark withItems(List<String> items, List<ItemShow> shows) {
        return new StudioMark(offset, kind, what, yaw, to, times, List.copyOf(items),
                shows.size() == items.size() ? List.copyOf(shows) : evenShows(items.size(), to.isPresent()));
    }

    /** The item at this place in the list's show (its own, or its even share of the work). */
    public ItemShow show(int index) {
        return shows.size() == items.size() ? shows.get(index) : evenShows(items.size(), to.isPresent()).get(index);
    }

    /** {@code count} items sharing the work one after another. */
    public static List<ItemShow> evenShows(int count, boolean travels) {
        List<ItemShow> even = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            even.add(new ItemShow(Math.round(100F * i / count), Math.round(100F * (i + 1) / count), travels ? Style.TRAVEL : Style.SPIN));
        }
        return List.copyOf(even);
    }

    /** The items that show at this point (0 to 1) of the work (their places in the list). */
    public List<Integer> shownItems(float progress) {
        List<Integer> shown = new ArrayList<>();
        if (!times.shows(progress)) return shown;
        for (int i = 0; i < items.size(); i++) if (show(i).shows(progress)) shown.add(i);
        return shown;
    }

    /** The same mark with only usable items (at most {@link #MAX_ITEMS}), each with a tidy show. */
    public StudioMark cleaned() {
        if (kind != Kind.ITEM) return new StudioMark(offset, kind, what, yaw, Optional.empty(), times.tidy(), List.of(), List.of());
        List<String> kept = new ArrayList<>();
        List<ItemShow> keptShows = new ArrayList<>();
        for (int i = 0; i < items.size() && kept.size() < MAX_ITEMS; i++) {
            if (!validEntry(items.get(i))) continue;
            kept.add(items.get(i));
            keptShows.add(show(i).tidy());
        }
        return new StudioMark(offset, kind, what, yaw, to, times.tidy(), List.copyOf(kept), List.copyOf(keptShows));
    }

    /** The same mark, its spots moved by this much. */
    public StudioMark shifted(BlockPos by) {
        return new StudioMark(offset.offset(by), kind, what, yaw, to.map(t -> t.offset(by)), times, items, shows);
    }

    /** Whether an item anchor's entry is usable: a display's name ("input"), or an item that exists. */
    public static boolean validEntry(String entry) {
        if (!entry.contains(":")) return DISPLAYS.contains(entry);
        Identifier id = Identifier.tryParse(entry);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
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
            Codec.STRING.listOf().optionalFieldOf("items", List.of()).forGetter(StudioMark::items),
            ItemShow.CODEC.listOf().optionalFieldOf("shows", List.of()).forGetter(StudioMark::shows)
    ).apply(instance, StudioMark::new));

    public static final StreamCodec<ByteBuf, StudioMark> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StudioMark::offset,
            ByteBufCodecs.idMapper(i -> Kind.values()[Math.floorMod(i, Kind.values().length)], Kind::ordinal), StudioMark::kind,
            ByteBufCodecs.stringUtf8(256), StudioMark::what,
            ByteBufCodecs.VAR_INT, StudioMark::yaw,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), StudioMark::to,
            Times.STREAM_CODEC, StudioMark::times,
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MAX_ITEMS)), StudioMark::items,
            ItemShow.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ITEMS)), StudioMark::shows,
            StudioMark::new);

    /** The particle to show, if the id names a plain particle type (one that needs no extra settings). */
    public static @Nullable ParticleOptions particle(@Nullable Identifier id) {
        if (id == null) return null;
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null) instanceof SimpleParticleType simple ? simple : null;
    }

    /**
     * As animation effects on the spot named by {@code anchor} (and {@code toAnchor}, the spot an item travels to);
     * particles and items play while {@code when} holds. An item anchor with items gives one effect per item.
     */
    public List<Effect> effects(String anchor, @Nullable String toAnchor, Trigger when) {
        Target at = new Target(Optional.of(anchor), Optional.empty(), Map.of(), Vec3.ZERO);
        Optional<Target> toTarget = toAnchor == null ? Optional.empty() : Optional.of(new Target(Optional.of(toAnchor), Optional.empty(), Map.of(), Vec3.ZERO));
        Identifier id = id();
        float from = times.showFrom() / 100.0F, until = times.showUntil() / 100.0F;
        Timing base = new Timing(when, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE);
        return switch (kind) {
            case PARTICLES -> {
                ParticleOptions particle = particle(id);
                if (particle == null) yield List.of();
                yield List.of(new Effect.Particle(new Timing(when, 4, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until), Optional.of(particle),
                        Optional.empty(), at, Optional.empty(), new Vec3(0.25, 0.2, 0.25), 2, 0.01, Effect.ParticleStyle.SPREAD, 0.91));
            }
            case FIGURE -> id == null ? List.of() : List.of(new Effect.Figure(new Timing(Trigger.ALWAYS, 1, 1.0F, 0, Map.of(), Timing.Where.EVERYWHERE).between(from, until),
                    Optional.of(id), Optional.empty(), Optional.empty(), at, Optional.empty(), Optional.of((float) yaw), false,
                    0.0F, 0.0F, 1.0F, false, Optional.empty(), Optional.empty()));
            case ITEM -> {
                if (items.isEmpty()) {
                    yield List.of(new Effect.FloatingItem(base.between(from, until), what, at,
                            to.isPresent() ? Effect.ItemMotion.STILL : Effect.ItemMotion.SPIN, 1.0F, Optional.of(4.0F), 0.5F, 1, 0, 0.5F,
                            to.isPresent() ? 0.0F : 0.05F, 0.0F, toTarget, List.of(times.leave() / 100.0F, times.arrive() / 100.0F),
                            List.of(), List.of()));
                }
                List<Effect> each = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    ItemShow show = show(i);
                    float start = Math.max(from, show.from() / 100.0F), end = Math.min(until, show.until() / 100.0F);
                    if (end < start) continue;
                    boolean travels = show.style() == Style.TRAVEL && toTarget.isPresent();
                    Effect.ItemMotion motion = show.style() == Style.SPIN || (show.style() == Style.TRAVEL && !travels)
                            ? Effect.ItemMotion.SPIN : Effect.ItemMotion.STILL;
                    float bob = switch (show.style()) {
                        case BOB -> 0.12F;
                        case SPIN -> 0.05F;
                        case STILL -> 0.0F;
                        case TRAVEL -> travels ? 0.0F : 0.05F;
                    };
                    each.add(new Effect.FloatingItem(base.between(start, end), "", at, motion, 1.0F, Optional.of(4.0F), 0.5F, 1, 0, 0.5F,
                            bob, 0.0F, travels ? toTarget : Optional.empty(), List.of(show.from() / 100.0F, show.until() / 100.0F),
                            List.of(items.get(i)), List.of()));
                }
                yield List.copyOf(each);
            }
        };
    }
}
