package com.lealex.alchyx.animation;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effects a mod draws with its own code (a rift portal, a glowing sigil...) that players may put at anchors with the
 * Machine Studio ("Mod" anchors). A mod registers the id here on both sides (in its constructor), so the server takes
 * the anchor, and draws it on the client with {@code client.animation.Figures.register(id, ...)}: the anchor plays
 * it as a coded figure ({@code "figure": "<id>"}), turned by the anchor's turn, through the anchor's time.
 */
public final class StudioEffects {
    private StudioEffects() {}

    private static final Map<Identifier, String> EFFECTS = new ConcurrentHashMap<>();

    /** Lets players put this coded figure at anchors; {@code name} is what the studio shows ("Rift portal"). */
    public static void register(Identifier id, String name) {
        EFFECTS.put(id, name);
    }

    public static boolean contains(Identifier id) {
        return EFFECTS.containsKey(id);
    }

    /** Its name in the studio, or its id. */
    public static String name(Identifier id) {
        return EFFECTS.getOrDefault(id, id.toString());
    }

    /** Every registered id, sorted. */
    public static List<Identifier> ids() {
        return EFFECTS.keySet().stream().sorted().toList();
    }
}
