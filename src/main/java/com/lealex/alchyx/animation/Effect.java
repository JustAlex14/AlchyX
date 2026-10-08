package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One effect of a machine animation file. "type" picks the kind; every kind also takes the {@link Timing} fields
 * (when, interval, chance, delay). Positions are {@link Target}s. All effects run on the client only.
 */
public sealed interface Effect permits Effect.Particle, Effect.Sound, Effect.BlockMove, Effect.StateSteps, Effect.FloatingItem, Effect.Figure {

    Timing timing();

    String type();

    Codec<Effect> CODEC = Codec.STRING.partialDispatch("type",
            effect -> DataResult.success(effect.type()),
            Effect::codecFor);

    /** The map codec for one "type" value. */
    static DataResult<MapCodec<? extends Effect>> codecFor(String type) {
        MapCodec<? extends Effect> codec = switch (type) {
            case "particle" -> Particle.MAP_CODEC;
            case "sound" -> Sound.MAP_CODEC;
            case "block_move" -> BlockMove.MAP_CODEC;
            case "block_state" -> StateSteps.MAP_CODEC;
            case "item" -> FloatingItem.MAP_CODEC;
            case "figure" -> Figure.MAP_CODEC;
            default -> null;
        };
        return codec != null ? DataResult.success(codec)
                : DataResult.error(() -> "unknown effect type \"" + type + "\" (particle, sound, block_move, block_state, item, figure)");
    }

    /** "minecraft:end_rod", or a full particle object ({"type": "minecraft:dust", "color": [1, 0, 1], "scale": 1}). */
    Codec<ParticleOptions> PARTICLE = Codec.withAlternative(ParticleTypes.CODEC,
            BuiltInRegistries.PARTICLE_TYPE.byNameCodec().comapFlatMap(
                    type -> type instanceof SimpleParticleType simple ? DataResult.success((ParticleOptions) simple)
                            : DataResult.error(() -> "particle " + BuiltInRegistries.PARTICLE_TYPE.getKey(type)
                            + " needs options: write it as an object with \"type\""),
                    ParticleOptions::getType));

    // ---- particle ----

    /** How particles move: "spread" (random, "speed"), "toward" ("to", slowing with "drag"), "attract" (glyph-like particles drawn into "to"). */
    enum ParticleStyle implements StringRepresentable {
        SPREAD("spread"), TOWARD("toward"), ATTRACT("attract");

        public static final Codec<ParticleStyle> CODEC = StringRepresentable.fromEnum(ParticleStyle::values);
        private final String id;

        ParticleStyle(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    /**
     * Particles at "at" (+/- "spread" blocks), "count" per run. "particle" names the particle, or "item": "input"
     * crumbles the item the machine shows as that display. Styles: see {@link ParticleStyle}.
     */
    record Particle(Timing timing, Optional<ParticleOptions> particle, Optional<String> item, Target at,
                    Optional<Target> to, Vec3 spread, int count, double speed, ParticleStyle style, double drag) implements Effect {
        public static final MapCodec<Particle> MAP_CODEC = RecordCodecBuilder.<Particle>mapCodec(instance -> instance.group(
                Timing.MAP_CODEC.forGetter(Particle::timing),
                PARTICLE.optionalFieldOf("particle").forGetter(Particle::particle),
                Codec.STRING.optionalFieldOf("item").forGetter(Particle::item),
                Target.CODEC.optionalFieldOf("at", Target.CORE).forGetter(Particle::at),
                Target.CODEC.optionalFieldOf("to").forGetter(Particle::to),
                Vec3.CODEC.optionalFieldOf("spread", Vec3.ZERO).forGetter(Particle::spread),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(Particle::count),
                Codec.DOUBLE.optionalFieldOf("speed", 0.0).forGetter(Particle::speed),
                ParticleStyle.CODEC.optionalFieldOf("style", ParticleStyle.SPREAD).forGetter(Particle::style),
                Codec.doubleRange(0.0, 0.999).optionalFieldOf("drag", 0.91).forGetter(Particle::drag)
        ).apply(instance, Particle::new)).validate(p -> p.particle.isPresent() || p.item.isPresent() ? DataResult.success(p)
                : DataResult.error(() -> "a particle effect needs \"particle\" or \"item\""));

        @Override
        public String type() {
            return "particle";
        }
    }

    // ---- sound ----

    /** A sound at "at" (any sound id, vanilla or modded), with "volume", "pitch" and a random "pitch_variance". */
    record Sound(Timing timing, Identifier sound, Target at, float volume, float pitch, float pitchVariance) implements Effect {
        public static final MapCodec<Sound> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Timing.MAP_CODEC.forGetter(Sound::timing),
                Identifier.CODEC.fieldOf("sound").forGetter(Sound::sound),
                Target.CODEC.optionalFieldOf("at", Target.CORE).forGetter(Sound::at),
                Codec.FLOAT.optionalFieldOf("volume", 1.0F).forGetter(Sound::volume),
                Codec.FLOAT.optionalFieldOf("pitch", 1.0F).forGetter(Sound::pitch),
                Codec.FLOAT.optionalFieldOf("pitch_variance", 0.0F).forGetter(Sound::pitchVariance)
        ).apply(instance, Sound::new));

        @Override
        public String type() {
            return "sound";
        }
    }

    // ---- block move ----

    /**
     * Moves a drawn block at every "targets" position along "direction" following "keyframes".
     * <ul>
     *   <li>"draw": the block to draw (default: a copy of what the target looks like);</li>
     *   <li>"direction": "facing" (the target's own facing, default), "toward_core", "outward", or a direction
     *       ("up", "north"...) in pattern coordinates;</li>
     *   <li>"align": turn the drawn block's facing to the direction (default true);</li>
     *   <li>"swap": state values the target shows while the move lasts, e.g. {"extended": true} for a piston.</li>
     * </ul>
     */
    record BlockMove(Timing timing, Target targets, Optional<BlockLook> draw, String direction, boolean align,
                     Map<String, String> swap, List<Keyframe> keyframes) implements Effect {
        public static final MapCodec<BlockMove> MAP_CODEC = RecordCodecBuilder.<BlockMove>mapCodec(instance -> instance.group(
                Timing.MAP_CODEC.forGetter(BlockMove::timing),
                Target.CODEC.fieldOf("targets").forGetter(BlockMove::targets),
                BlockLook.CODEC.optionalFieldOf("draw").forGetter(BlockMove::draw),
                Codec.STRING.optionalFieldOf("direction", "facing").forGetter(BlockMove::direction),
                Codec.BOOL.optionalFieldOf("align", true).forGetter(BlockMove::align),
                BlockLook.STATE.optionalFieldOf("swap", Map.of()).forGetter(BlockMove::swap),
                Keyframe.CODEC.listOf(1, 64).fieldOf("keyframes").forGetter(BlockMove::keyframes)
        ).apply(instance, BlockMove::new)).xmap(
                move -> new BlockMove(move.timing, move.targets, move.draw, move.direction, move.align, move.swap,
                        move.keyframes.stream().sorted(Comparator.comparingDouble(Keyframe::t)).toList()),
                move -> move);

        @Override
        public String type() {
            return "block_move";
        }
    }

    // ---- floating item ----

    /** How a floating item moves: "spin" in place, "orbit" around the spot, "converge" (orbit closing in as the operation progresses), "still". */
    enum ItemMotion implements StringRepresentable {
        SPIN("spin"), ORBIT("orbit"), CONVERGE("converge"), STILL("still");

        public static final Codec<ItemMotion> CODEC = StringRepresentable.fromEnum(ItemMotion::values);
        private final String id;

        ItemMotion(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    /**
     * Draws the item of one of the machine's displays ("display": "input"...) floating at "at", while "when"
     * holds (always / idle / working). "speed" in degrees per tick ("working_speed" while working), "copies"
     * evenly spaced (or one per "per_items" items in the stack, at most "copies"), "radius" for orbits,
     * "scale", "bob" (up-down amplitude in blocks), "phase" (degrees added to the angle: several items sharing an
     * orbit, e.g. 0 / 120 / 240, stay apart).
     */
    record FloatingItem(Timing timing, String display, Target at, ItemMotion motion, float speed,
                        Optional<Float> workingSpeed, float radius, int copies, int perItems, float scale,
                        float bob, float phase) implements Effect {
        public static final MapCodec<FloatingItem> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Timing.MAP_CODEC.forGetter(FloatingItem::timing),
                Codec.STRING.fieldOf("display").forGetter(FloatingItem::display),
                Target.CODEC.optionalFieldOf("at", Target.CORE).forGetter(FloatingItem::at),
                ItemMotion.CODEC.optionalFieldOf("motion", ItemMotion.SPIN).forGetter(FloatingItem::motion),
                Codec.FLOAT.optionalFieldOf("speed", 1.0F).forGetter(FloatingItem::speed),
                Codec.FLOAT.optionalFieldOf("working_speed").forGetter(FloatingItem::workingSpeed),
                Codec.FLOAT.optionalFieldOf("radius", 0.5F).forGetter(FloatingItem::radius),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("copies", 1).forGetter(FloatingItem::copies),
                ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("per_items", 0).forGetter(FloatingItem::perItems),
                Codec.FLOAT.optionalFieldOf("scale", 0.5F).forGetter(FloatingItem::scale),
                Codec.FLOAT.optionalFieldOf("bob", 0.05F).forGetter(FloatingItem::bob),
                Codec.FLOAT.optionalFieldOf("phase", 0.0F).forGetter(FloatingItem::phase)
        ).apply(instance, FloatingItem::new));

        @Override
        public String type() {
            return "item";
        }
    }

    // ---- block state ----

    /**
     * Changes the look of every "targets" block while "when" holds (always / idle / working), step by step through
     * the operation: "steps" is a list of state values, the first shown as the operation begins, the last as it ends
     * (a brewing stand whose bottles fill one by one). A single step is simply that look while "when" holds. The rest
     * of the time the blocks look as they are.
     */
    record StateSteps(Timing timing, Target targets, List<Map<String, String>> steps) implements Effect {
        public static final MapCodec<StateSteps> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Timing.MAP_CODEC.forGetter(StateSteps::timing),
                Target.CODEC.fieldOf("targets").forGetter(StateSteps::targets),
                BlockLook.STATE.listOf(1, 64).fieldOf("steps").forGetter(StateSteps::steps)
        ).apply(instance, StateSteps::new));

        /** Which step shows at this point (0 to 1) of the operation. */
        public int index(float progress) {
            return Math.max(0, Math.min(steps.size() - 1, (int) (progress * steps.size())));
        }

        /** Whether the steps show now ("start" and "finish" make no sense here: they count as "working"). */
        public boolean holds(boolean working) {
            return timing.when().continuous() ? timing.when().holds(working) : working;
        }

        @Override
        public String type() {
            return "block_state";
        }
    }

    // ---- figure ----

    /**
     * A figure standing (or sitting) in the machine, drawn every frame while "when" holds (always / idle / working):
     * <ul>
     *   <li>"entity": any entity type ("minecraft:cat", "minecraft:vindicator", a modded mob), drawn by its own
     *       renderer; "nbt": optional SNBT read into it, e.g. {@code "{variant:\"minecraft:all_black\",Sitting:1b}"}
     *       (variants, sitting, baby, armor...). It's only drawn: no AI, no collision, no sounds;</li>
     *   <li>or "figure": a figure coded by a mod (registered with {@code Figures.register}), e.g. a posed ghost;</li>
     *   <li>"at": where its feet are (the bottom of that block, plus the target's offset);</li>
     *   <li>"face": a target it turns toward, or "yaw": a fixed heading in degrees in the pattern's frame
     *       (0 = toward +z, 90 = toward -x, -90 = toward +x; turned with the machine);</li>
     *   <li>"look_at_player": its head follows the camera (entities);</li>
     *   <li>"hover": blocks above the floor, "bob": up-down amplitude in blocks, "scale";</li>
     *   <li>"ghost": drawn see-through (entities: vanilla's invisible-mob look);</li>
     *   <li>"display" / "unless_display": only while the machine's display of that name holds an item / is empty
     *       (e.g. a linked player).</li>
     * </ul>
     */
    record Figure(Timing timing, Optional<Identifier> entity, Optional<String> nbt, Optional<Identifier> figure, Target at,
                  Optional<Target> face, Optional<Float> yaw, boolean lookAtPlayer, float hover, float bob, float scale,
                  boolean ghost, Optional<String> display, Optional<String> unlessDisplay) implements Effect {
        public static final MapCodec<Figure> MAP_CODEC = RecordCodecBuilder.<Figure>mapCodec(instance -> instance.group(
                Timing.MAP_CODEC.forGetter(Figure::timing),
                Identifier.CODEC.optionalFieldOf("entity").forGetter(Figure::entity),
                Codec.STRING.optionalFieldOf("nbt").forGetter(Figure::nbt),
                Identifier.CODEC.optionalFieldOf("figure").forGetter(Figure::figure),
                Target.CODEC.optionalFieldOf("at", Target.CORE).forGetter(Figure::at),
                Target.CODEC.optionalFieldOf("face").forGetter(Figure::face),
                Codec.FLOAT.optionalFieldOf("yaw").forGetter(Figure::yaw),
                Codec.BOOL.optionalFieldOf("look_at_player", false).forGetter(Figure::lookAtPlayer),
                Codec.FLOAT.optionalFieldOf("hover", 0.0F).forGetter(Figure::hover),
                Codec.FLOAT.optionalFieldOf("bob", 0.0F).forGetter(Figure::bob),
                Codec.floatRange(0.01F, 16.0F).optionalFieldOf("scale", 1.0F).forGetter(Figure::scale),
                Codec.BOOL.optionalFieldOf("ghost", false).forGetter(Figure::ghost),
                Codec.STRING.optionalFieldOf("display").forGetter(Figure::display),
                Codec.STRING.optionalFieldOf("unless_display").forGetter(Figure::unlessDisplay)
        ).apply(instance, Figure::new)).validate(f -> f.entity.isPresent() != f.figure.isPresent() ? DataResult.success(f)
                : DataResult.error(() -> "a figure needs exactly one of \"entity\" or \"figure\""));

        @Override
        public String type() {
            return "figure";
        }
    }
}
