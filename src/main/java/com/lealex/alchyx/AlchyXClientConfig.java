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
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The wave's particle amount, 0 when its effects are off (defaults while the config isn't loaded). */
    public static float waveParticles() {
        try {
            return WAVE_EFFECTS.get() ? WAVE_PARTICLES.get().floatValue() : 0F;
        } catch (IllegalStateException notLoadedYet) {
            return 1F;
        }
    }
}
