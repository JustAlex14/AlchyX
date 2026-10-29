package com.lealex.alchyx.client.scene;

import com.lealex.alchyx.AlchyX;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import org.jspecify.annotations.Nullable;

/** Loads every {@code assets/<namespace>/trailers/<name>.json} (resource packs, F3+T). Broken files are logged and skipped. */
public final class Trailers extends SimpleJsonResourceReloadListener<Trailer> {

    private static volatile Map<Identifier, Trailer> loaded = Map.of();

    private Trailers() {
        super(Trailer.CODEC, FileToIdConverter.json("trailers"));
    }

    @Override
    protected void apply(Map<Identifier, Trailer> found, ResourceManager manager, ProfilerFiller profiler) {
        loaded = Map.copyOf(new TreeMap<>(found));
        if (!found.isEmpty()) AlchyX.LOGGER.info("AlchyX: loaded {} trailer(s): {}", found.size(), new TreeMap<>(found).keySet());
    }

    public static @Nullable Trailer get(Identifier id) {
        return loaded.get(id);
    }

    public static Map<Identifier, Trailer> all() {
        return loaded;
    }

    public static void onAddReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(AlchyX.MODID, "trailers"), new Trailers());
    }
}
