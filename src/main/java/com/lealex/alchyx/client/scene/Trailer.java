package com.lealex.alchyx.client.scene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A mod trailer: a camera flown along shots, with titles over it. Read from
 * {@code assets/<namespace>/trailers/<name>.json} (a resource pack can add or replace them) and played by
 * {@link TrailerPlayer} ({@code /trailer play <namespace:name>}). Every position is relative to the trailer's origin,
 * the block corner the command was run from (the same spot as a showcase map's build function), x east, y up, z south.
 *
 * <pre>
 * {
 *   "letterbox": true,                       black bars top and bottom (default true)
 *   "stand": [0, 0, 3],                      where the player stands, from the origin (default the origin itself):
 *                                            /trailer play measures the origin back from the player's block
 *   "shots": [
 *     { "ticks": 120,                        how long it lasts (20 = a second)
 *       "points": [[x, y, z], ...],          the camera's path, a smooth curve through every point
 *       "look": [x, y, z],                   what it looks at the whole shot (or "looks": a path of targets;
 *                                            neither: it looks the way it flies)
 *       "orbit": { "center": [x, y, z], "radius": 9, "height": 4, "from": 0, "to": 90, "look_height": 1 },
 *                                            in place of points: circles the centre (0 = south of it, 90 = west)
 *       "ease": "smooth",                    linear | smooth | in | out (default smooth)
 *       "fov": [70, 60], "roll": [0, -4],    from and to over the shot (optional)
 *       "fade_in": 10, "fade_out": 10 }      ticks of black at its start and end (default 0)
 *   ],
 *   "texts": [
 *     { "at": 20, "ticks": 80, "text": "AlchyForbidden", "style": "title", "color": "#C9A2FF" }
 *   ],                                       style title | subtitle | caption; text is a lang key or plain text
 *   "commands": [
 *     { "at": 0, "command": "alchyx showcase build mymod:machine ~3 ~ ~-8 south" }
 *   ]                                        run by the player at that tick (~ = from where they stand)
 * }
 * </pre>
 * A command that takes the player to another world (a void arena) is followed: the camera goes with them, and the
 * shots after it are measured from where they land (and again from where they come back to).
 * <pre>
 * </pre>
 */
public record Trailer(boolean letterbox, Vec3 stand, List<Shot> shots, List<Text> texts, List<Cue> commands) {

    public static final Codec<Trailer> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("letterbox", true).forGetter(Trailer::letterbox),
            Vec3.CODEC.optionalFieldOf("stand", Vec3.ZERO).forGetter(Trailer::stand),
            Shot.CODEC.listOf(1, 256).fieldOf("shots").forGetter(Trailer::shots),
            Text.CODEC.listOf().optionalFieldOf("texts", List.of()).forGetter(Trailer::texts),
            Cue.CODEC.listOf().optionalFieldOf("commands", List.of()).forGetter(Trailer::commands)
    ).apply(i, Trailer::new));

    /**
     * A command the player runs when the trailer reaches `at` (needs the permission the command asks for). Positions
     * written with ~ are from where the player stands (the origin, give or take a block). A cinematic a command starts
     * (a boss's entrance) holds the trailer's clock until it gives the camera back.
     */
    public record Cue(int at, String command) {
        public static final Codec<Cue> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("at").forGetter(Cue::at),
                Codec.STRING.fieldOf("command").forGetter(Cue::command)
        ).apply(i, Cue::new));
    }

    public int length() {
        int total = 0;
        for (Shot shot : shots) total += shot.ticks();
        return total;
    }

    public record Orbit(Vec3 center, double radius, double height, float from, float to, double lookHeight) {
        public static final Codec<Orbit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Vec3.CODEC.fieldOf("center").forGetter(Orbit::center),
                Codec.DOUBLE.fieldOf("radius").forGetter(Orbit::radius),
                Codec.DOUBLE.optionalFieldOf("height", 0.0).forGetter(Orbit::height),
                Codec.FLOAT.optionalFieldOf("from", 0F).forGetter(Orbit::from),
                Codec.FLOAT.optionalFieldOf("to", 90F).forGetter(Orbit::to),
                Codec.DOUBLE.optionalFieldOf("look_height", 0.0).forGetter(Orbit::lookHeight)
        ).apply(i, Orbit::new));

        Vec3 at(float k) {
            double a = Math.toRadians(Mth.lerp(k, from, to));
            return center.add(-Math.sin(a) * radius, height, Math.cos(a) * radius);
        }
    }

    public record Shot(int ticks, List<Vec3> points, Optional<Vec3> look, List<Vec3> looks, Optional<Orbit> orbit,
                       String ease, List<Float> fov, List<Float> roll, int fadeIn, int fadeOut) {
        public static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(1, 72000).fieldOf("ticks").forGetter(Shot::ticks),
                Vec3.CODEC.listOf().optionalFieldOf("points", List.of()).forGetter(Shot::points),
                Vec3.CODEC.optionalFieldOf("look").forGetter(Shot::look),
                Vec3.CODEC.listOf().optionalFieldOf("looks", List.of()).forGetter(Shot::looks),
                Orbit.CODEC.optionalFieldOf("orbit").forGetter(Shot::orbit),
                Codec.STRING.optionalFieldOf("ease", "smooth").forGetter(Shot::ease),
                Codec.FLOAT.listOf(1, 2).optionalFieldOf("fov", List.of()).forGetter(Shot::fov),
                Codec.FLOAT.listOf(1, 2).optionalFieldOf("roll", List.of()).forGetter(Shot::roll),
                Codec.INT.optionalFieldOf("fade_in", 0).forGetter(Shot::fadeIn),
                Codec.INT.optionalFieldOf("fade_out", 0).forGetter(Shot::fadeOut)
        ).apply(i, Shot::new));

        /** The shot's own progress (0..1) eased. */
        float eased(float k) {
            k = Mth.clamp(k, 0F, 1F);
            return switch (ease) {
                case "linear" -> k;
                case "in" -> k * k;
                case "out" -> 1F - (1F - k) * (1F - k);
                default -> k * k * (3F - 2F * k);
            };
        }

        Vec3 position(float k) {
            if (orbit.isPresent()) return orbit.get().at(k);
            return points.isEmpty() ? Vec3.ZERO : curve(points, k);
        }

        /** What the camera looks at, or null to look the way it flies. */
        Vec3 target(float k) {
            if (orbit.isPresent()) return orbit.get().center().add(0, orbit.get().lookHeight(), 0);
            if (!looks.isEmpty()) return curve(looks, k);
            return look.orElse(null);
        }

        static float span(List<Float> values, float k, float otherwise) {
            if (values.isEmpty()) return otherwise;
            return values.size() == 1 ? values.getFirst() : Mth.lerp(k, values.get(0), values.get(1));
        }
    }

    public record Text(int at, int ticks, String text, String style, String color) {
        public static final Codec<Text> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("at").forGetter(Text::at),
                Codec.intRange(1, 72000).fieldOf("ticks").forGetter(Text::ticks),
                Codec.STRING.fieldOf("text").forGetter(Text::text),
                Codec.STRING.optionalFieldOf("style", "title").forGetter(Text::style),
                Codec.STRING.optionalFieldOf("color", "#F0E6FF").forGetter(Text::color)
        ).apply(i, Text::new));

        int rgb() {
            try {
                return Integer.parseInt(color.startsWith("#") ? color.substring(1) : color, 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                return 0xF0E6FF;
            }
        }
    }

    /** A smooth curve through every point (Catmull-Rom, equal time per stretch). */
    static Vec3 curve(List<Vec3> points, float k) {
        int n = points.size();
        if (n == 1) return points.getFirst();
        float u = Mth.clamp(k, 0F, 1F) * (n - 1);
        int i = Math.min(n - 2, (int) Math.floor(u));
        double t = u - i;
        Vec3 p0 = points.get(Math.max(0, i - 1)), p1 = points.get(i), p2 = points.get(i + 1), p3 = points.get(Math.min(n - 1, i + 2));
        double t2 = t * t, t3 = t2 * t;
        return new Vec3(
                cr(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                cr(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                cr(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }

    private static double cr(double a, double b, double c, double d, double t, double t2, double t3) {
        return 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    }
}
