package com.lealex.alchyx.animation;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * When an effect runs. ALWAYS / IDLE / WORKING are continuous (every "interval" ticks while true); START and FINISH
 * fire once when the machine starts or finishes an operation. Nothing runs while the structure isn't formed.
 */
public enum Trigger implements StringRepresentable {
    ALWAYS("always"),
    IDLE("idle"),
    WORKING("working"),
    START("start"),
    FINISH("finish");

    public static final Codec<Trigger> CODEC = StringRepresentable.fromEnum(Trigger::values);

    private final String id;

    Trigger(String id) {
        this.id = id;
    }

    public boolean continuous() {
        return this == ALWAYS || this == IDLE || this == WORKING;
    }

    /** For continuous triggers: does it hold right now? */
    public boolean holds(boolean working) {
        return switch (this) {
            case ALWAYS -> true;
            case IDLE -> !working;
            case WORKING -> working;
            default -> false;
        };
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
