package com.lealex.alchyx.client.scene;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of the camera during cinematics. A scene takes a shot (a camera type, maybe an entity to watch from,
 * maybe a distance rule) under its own id and releases it when done. Shots stack: the newest one is the camera; when
 * it is released the one under it comes back, and when the last one goes the camera returns to what the player had
 * before the first. So two scenes that meet (a pull into a rift, then a boss's entrance) never restore each other's
 * camera by mistake.
 */
public final class CameraShots {
    private CameraShots() {}

    /** How far the detached camera sits, from the distance it would have had. */
    @FunctionalInterface
    public interface Distance {
        float apply(float usual);
    }

    private record Shot(CameraType type, @Nullable Entity watch, @Nullable Distance distance) {}

    private static final Map<Identifier, Shot> SHOTS = new LinkedHashMap<>();
    private static @Nullable CameraType before;
    private static boolean watching;

    /** Takes (or retakes) a shot: the camera goes to {@code type} at once and is held there until released. */
    public static void take(Identifier id, CameraType type, @Nullable Distance distance) {
        Shot old = SHOTS.remove(id);
        if (SHOTS.isEmpty() && old == null) before = Minecraft.getInstance().options.getCameraType();
        SHOTS.put(id, new Shot(type, old == null ? null : old.watch(), distance));
        apply();
    }

    /** The shot looks from this entity (the boss being shown) instead of the player; null goes back to the player. */
    public static void watch(Identifier id, @Nullable Entity entity) {
        Shot shot = SHOTS.get(id);
        if (shot == null) return;
        SHOTS.put(id, new Shot(shot.type(), entity, shot.distance()));
        apply();
    }

    public static void release(Identifier id) {
        if (SHOTS.remove(id) == null) return;
        if (SHOTS.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (before != null) minecraft.options.setCameraType(before);
            before = null;
            backToPlayer(minecraft);
        } else {
            apply();
        }
    }

    public static boolean active(Identifier id) {
        return SHOTS.containsKey(id);
    }

    public static boolean any() {
        return !SHOTS.isEmpty();
    }

    private static @Nullable Shot top() {
        Shot last = null;
        for (Shot shot : SHOTS.values()) last = shot;
        return last;
    }

    private static void apply() {
        Shot shot = top();
        if (shot == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.getCameraType() != shot.type()) minecraft.options.setCameraType(shot.type());
        Entity watch = shot.watch();
        if (watch != null && !watch.isRemoved()) {
            if (minecraft.getCameraEntity() != watch) minecraft.setCameraEntity(watch);
            watching = true;
        } else {
            backToPlayer(minecraft);
        }
    }

    private static void backToPlayer(Minecraft minecraft) {
        if (!watching) return;
        watching = false;
        if (minecraft.player != null) minecraft.setCameraEntity(minecraft.player);
    }

    /** Holds the newest shot each tick; everything goes when the world does. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (SHOTS.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            SHOTS.clear();
            watching = false;
            if (before != null) minecraft.options.setCameraType(before);
            before = null;
            return;
        }
        apply();
    }

    /** HUD layers still drawn during a cutscene: titles (a boss's name), sound subtitles, screen effects, the scene fade. */
    private static final Set<Identifier> KEPT = new HashSet<>(Set.of(VanillaGuiLayers.TITLE, VanillaGuiLayers.SUBTITLE_OVERLAY,
            VanillaGuiLayers.CAMERA_OVERLAYS, Identifier.fromNamespaceAndPath("alchyx", "scene_fade")));

    /** A mod's HUD layer that should stay up through cutscenes (its own subtitles, a cutscene's own text). */
    public static void keepDuringScenes(Identifier layer) {
        KEPT.add(layer);
    }

    /** True while a cutscene plays: a camera shot is held or a scripted walk / hold runs. */
    public static boolean cutscene() {
        return !SHOTS.isEmpty() || SceneWalk.active();
    }

    /** Every other HUD layer is left out during a cutscene (chat stays while the player is typing). */
    public static void onRenderLayer(RenderGuiLayerEvent.Pre event) {
        if (!cutscene() || KEPT.contains(event.getName())) return;
        if (event.getName().equals(VanillaGuiLayers.CHAT) && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
        event.setCanceled(true);
    }

    public static void onCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        Shot shot = top();
        if (shot != null && shot.distance() != null) event.setDistance(shot.distance().apply(event.getDistance()));
    }
}
