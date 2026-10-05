package com.lealex.alchyx.upgrade;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of upgrade types. Register yours once, early (a static field of your mod's registries class):
 * <pre>{@code
 * public static final UpgradeType SPEED = UpgradeTypes.register(new UpgradeType(
 *         Identifier.fromNamespaceAndPath(MODID, "speed"), 4, 0x3FD6E0,
 *         new UpgradeType.Emblem(Identifier.fromNamespaceAndPath(MODID, "textures/gui/upgrade_emblems.png"), 0, 0),
 *         (tier, lines) -> lines.accept(Component.literal("x" + tier + " speed"))));
 * }</pre>
 * then give machines the types they take ({@code createUpgrades(List.of(SPEED, ...))} in a core's block entity) and
 * register an {@link UpgradeItem} per tier.
 */
public final class UpgradeTypes {
    private UpgradeTypes() {}

    private static final Map<Identifier, UpgradeType> BY_ID = new ConcurrentHashMap<>();
    private static final List<UpgradeType> ALL = new CopyOnWriteArrayList<>();

    public static UpgradeType register(UpgradeType type) {
        if (BY_ID.putIfAbsent(type.id(), type) != null) throw new IllegalStateException("Upgrade type registered twice: " + type.id());
        ALL.add(type);
        return type;
    }

    public static @Nullable UpgradeType get(Identifier id) {
        return BY_ID.get(id);
    }

    /** Every registered type, in registration order. */
    public static List<UpgradeType> all() {
        return List.copyOf(ALL);
    }
}
