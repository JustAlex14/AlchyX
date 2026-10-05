package com.lealex.alchyx.animation;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads every {@code data/<namespace>/machine_animation/<name>.json} (world load and {@code /reload}) and builds
 * what clients receive ({@link #resolved()}), sent by {@link AnimationSync}. Broken files are logged and skipped.
 */
public final class MachineAnimations extends SimpleJsonResourceReloadListener<MachineAnimation> {

    private static volatile Map<Identifier, List<Effect>> effects = Map.of();

    MachineAnimations() {
        super(MachineAnimation.CODEC, FileToIdConverter.json("machine_animation"));
    }

    @Override
    protected void apply(Map<Identifier, MachineAnimation> loaded, ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, List<Effect>> byMachine = new LinkedHashMap<>();
        // Sorted by file id so "replace" and the effect order don't depend on hash order
        for (Map.Entry<Identifier, MachineAnimation> file : new TreeMap<>(loaded).entrySet()) {
            Identifier machine = file.getValue().machine().orElse(file.getKey());
            List<Effect> list = byMachine.computeIfAbsent(machine, id -> new ArrayList<>());
            if (file.getValue().replace()) list.clear();
            list.addAll(file.getValue().effects());
        }
        effects = Map.copyOf(byMachine);
        AlchyX.LOGGER.info("AlchyX: loaded animations for {} machine(s): {}", byMachine.size(), byMachine.keySet());
    }

    /** Animations of every machine whose pattern is loaded, with the pattern data clients need. */
    public static List<ResolvedAnimation> resolved() {
        List<ResolvedAnimation> list = new ArrayList<>();
        for (Map.Entry<Identifier, List<Effect>> entry : effects.entrySet()) {
            MultiblockPattern pattern = MultiblockPatterns.get(entry.getKey());
            if (pattern == null) {
                AlchyX.LOGGER.warn("AlchyX: animation for {} skipped: no multiblock pattern with that id", entry.getKey());
                continue;
            }
            list.add(new ResolvedAnimation(entry.getKey(), pattern.front(), pattern.anchors(),
                    pattern.minOffset(), pattern.maxOffset(), entry.getValue()));
        }
        return list;
    }
}
