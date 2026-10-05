package com.lealex.alchyx.animation;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The animation definitions the server sent (client side; plain data, safe to reference from common code). */
public final class ClientAnimations {
    private ClientAnimations() {}

    private static volatile Map<Identifier, ResolvedAnimation> animations = Map.of();
    private static volatile int generation = 0;

    public static void set(List<ResolvedAnimation> list) {
        Map<Identifier, ResolvedAnimation> map = new HashMap<>();
        for (ResolvedAnimation animation : list) map.put(animation.machine(), animation);
        animations = Map.copyOf(map);
        generation++;
    }

    public static @Nullable ResolvedAnimation get(Identifier machine) {
        return animations.get(machine);
    }

    /** Bumps whenever new definitions arrive (cached per-machine state is reset). */
    public static int generation() {
        return generation;
    }
}
