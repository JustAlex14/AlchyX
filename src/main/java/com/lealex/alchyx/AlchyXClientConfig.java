package com.lealex.alchyx;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side settings (visuals only), saved in config/alchyx-client.toml. */
public final class AlchyXClientConfig {
    private AlchyXClientConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("The transformation wave: each block of a forming machine shatters, and its new look",
                "materializes with sparks tracing its edges, a flash and a puff of void.").push("formationWave");
    }

    public static final ModConfigSpec.BooleanValue WAVE_EFFECTS = BUILDER
            .comment("Play the materializing effect on each converted block.")
            .define("effects", true);
    public static final ModConfigSpec.DoubleValue WAVE_PARTICLES = BUILDER
            .comment("Particle amount (0: none, 1: normal, 2: double).")
            .defineInRange("particles", 1.0, 0.0, 4.0);

    static {
        BUILDER.pop();
        BUILDER.comment("How many particles the AlchyX mods' effects use: streams, links, rituals, idle motes.").push("particles");
    }

    public enum Particles { FULL, REDUCED, MINIMAL }

    public static final ModConfigSpec.EnumValue<Particles> PARTICLES = BUILDER
            .comment("FULL: as designed. REDUCED: about 40%. MINIMAL: about 15%, only what shows something is happening.")
            .defineEnum("amount", Particles.FULL);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The share of particles to spawn: 1, 0.4 or 0.15 (1 while the config isn't loaded). */
    public static float particleScale() {
        try {
            return switch (PARTICLES.get()) {
                case FULL -> 1F;
                case REDUCED -> 0.4F;
                case MINIMAL -> 0.15F;
            };
        } catch (IllegalStateException notLoadedYet) {
            return 1F;
        }
    }

    /** Whether to spawn this one particle (a draw against the particle share): wrap a lone addParticle with it. */
    public static boolean spawn(net.minecraft.util.RandomSource random) {
        float scale = particleScale();
        return scale >= 1F || random.nextFloat() < scale;
    }

    /** How many of {@code count} particles to spawn (rounded by chance, so small counts still show sometimes). */
    public static int count(int count, net.minecraft.util.RandomSource random) {
        float exact = count * particleScale();
        int whole = (int) exact;
        return whole + (random.nextFloat() < exact - whole ? 1 : 0);
    }

    /** The wave's particle amount, 0 when its effects are off (defaults while the config isn't loaded). */
    public static float waveParticles() {
        try {
            return WAVE_EFFECTS.get() ? WAVE_PARTICLES.get().floatValue() : 0F;
        } catch (IllegalStateException notLoadedYet) {
            return 1F;
        }
    }
}
