package com.lealex.alchyx.client.animation;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Coded figures by id, for animation files' {@code "figure": "<id>"}. Client only. */
public final class Figures {
    private static final Map<Identifier, CodedFigure> FIGURES = new ConcurrentHashMap<>();

    private Figures() {}

    /** Call during client setup, e.g. {@code Figures.register(Identifier.fromNamespaceAndPath("mymod", "ghost"), ...)}. */
    public static void register(Identifier id, CodedFigure figure) {
        FIGURES.put(id, figure);
    }

    /** The figure registered under this id; else a mini structure of that id (see {@link MiniFigure}), if there is one. */
    public static @Nullable CodedFigure get(Identifier id) {
        CodedFigure figure = FIGURES.get(id);
        return figure != null ? figure : MiniFigure.forId(id);
    }
}
