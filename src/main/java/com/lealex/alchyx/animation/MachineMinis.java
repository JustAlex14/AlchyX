package com.lealex.alchyx.animation;

import com.lealex.alchyx.AlchyX;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

/**
 * Loads every {@code data/<namespace>/machine_mini/<name>.json} (see {@link MiniStructure.File}) on world load and
 * {@code /reload}; {@link AnimationSync} sends them to players with the animations. Broken files are logged and skipped.
 */
public final class MachineMinis extends SimpleJsonResourceReloadListener<MiniStructure.File> {
    private static volatile Map<Identifier, MiniStructure.File> files = Map.of();

    MachineMinis() {
        super(MiniStructure.File.CODEC, FileToIdConverter.json("machine_mini"));
    }

    @Override
    protected void apply(Map<Identifier, MiniStructure.File> loaded, ResourceManager manager, ProfilerFiller profiler) {
        files = Map.copyOf(new TreeMap<>(loaded));
        if (!loaded.isEmpty()) AlchyX.LOGGER.info("AlchyX: loaded {} mini structure(s): {}", loaded.size(), files.keySet());
    }

    public static Map<Identifier, MiniStructure.File> all() {
        return files;
    }

    public static MiniStructure.@Nullable File get(Identifier id) {
        return files.get(id);
    }
}
