package com.lealex.alchyx.animation;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Machine cores loaded on the client (they add themselves in onLoad), for the animation engine. */
public final class LoadedCores {
    private LoadedCores() {}

    private static final Set<MultiblockCoreBlockEntity> CORES = Collections.newSetFromMap(new WeakHashMap<>());

    public static synchronized void add(MultiblockCoreBlockEntity core) {
        CORES.add(core);
    }

    public static synchronized void remove(MultiblockCoreBlockEntity core) {
        CORES.remove(core);
    }

    public static synchronized List<MultiblockCoreBlockEntity> snapshot() {
        return new ArrayList<>(CORES);
    }
}
