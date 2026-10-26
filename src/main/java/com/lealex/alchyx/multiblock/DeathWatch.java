package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Deaths near a machine: a core whose {@link MultiblockCoreBlockEntity#deathReach()} is above 0 is told of every
 * living death within that many blocks of it while it is loaded and formed ({@link MultiblockCoreBlockEntity#onDeathNearby}),
 * and may take the dead's drops ({@link MultiblockCoreBlockEntity#takeDropsNearby}, the first core that takes them
 * stops the others). For machines fed by killing (a pit), opened by a kill (a boss slain beside it), or watching the
 * dying (ghosts). Server side; the core registers itself when it loads, so nothing is scanned for machines without it.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class DeathWatch {
    private DeathWatch() {}

    private static final Map<Level, Set<MultiblockCoreBlockEntity>> WATCHERS = new WeakHashMap<>();

    public static void add(MultiblockCoreBlockEntity core) {
        if (core.getLevel() == null || core.getLevel().isClientSide()) return;
        WATCHERS.computeIfAbsent(core.getLevel(), level -> Collections.newSetFromMap(new WeakHashMap<>())).add(core);
    }

    public static void remove(MultiblockCoreBlockEntity core) {
        Set<MultiblockCoreBlockEntity> set = core.getLevel() == null ? null : WATCHERS.get(core.getLevel());
        if (set != null) set.remove(core);
    }

    /** The cores that watch this death's place, nearest first. */
    private static List<MultiblockCoreBlockEntity> watching(LivingEntity dead) {
        Set<MultiblockCoreBlockEntity> set = WATCHERS.get(dead.level());
        if (set == null || set.isEmpty()) return List.of();
        List<MultiblockCoreBlockEntity> out = new ArrayList<>();
        for (MultiblockCoreBlockEntity core : List.copyOf(set)) {
            if (core.isRemoved() || !core.isFormed()) continue;
            double reach = core.deathReach();
            if (core.getBlockPos().getCenter().distanceToSqr(dead.position()) <= reach * reach) out.add(core);
        }
        out.sort((a, b) -> Double.compare(a.getBlockPos().getCenter().distanceToSqr(dead.position()),
                b.getBlockPos().getCenter().distanceToSqr(dead.position())));
        return out;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        for (MultiblockCoreBlockEntity core : watching(event.getEntity())) core.onDeathNearby(event.getEntity(), event.getSource());
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        for (MultiblockCoreBlockEntity core : watching(event.getEntity())) {
            if (core.takeDropsNearby(event.getEntity(), event.getDrops())) return;
        }
    }
}
