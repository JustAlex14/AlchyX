package com.lealex.alchyx.client.scene;

import com.lealex.alchyx.AlchyX;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * A full-screen dark veil for scenes (above every HUD layer, under screens): a scene says how dark it wants it each
 * tick ({@link #show}); a tick without a word lets it lift over {@link #LIFT} ticks; {@link #blink} is a quick dark
 * in and out, peaking after `half` ticks (to move the player while nothing shows). Drawn eased between ticks.
 */
public final class ScreenFade {
    private ScreenFade() {}

    /** Ticks the veil takes to lift once no scene asks for it. */
    public static final int LIFT = 10;
    private static final int DEFAULT_COLOR = 0x0A0612;
    private static final float MAX_ALPHA = 0xD0 / 255F;

    private static float dark, darkPrev, wanted = -1F;
    private static int color = DEFAULT_COLOR, blinkHalf, blinkTicks = -1;

    /** How dark (0..1) the scene wants the screen this tick, and its colour (rgb). */
    public static void show(float darkness, int rgb) {
        wanted = Math.max(wanted, Mth.clamp(darkness, 0F, 1F));
        color = rgb & 0xFFFFFF;
    }

    public static void show(float darkness) {
        show(darkness, DEFAULT_COLOR);
    }

    /** A quick dark in and out, darkest after `half` ticks. */
    public static void blink(int half) {
        blinkHalf = Math.max(1, half);
        blinkTicks = 0;
    }

    /** Whether a blink is at its darkest this tick. */
    public static boolean blinkPeak() {
        return blinkTicks == blinkHalf;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        darkPrev = dark;
        float target = Math.max(wanted, 0F);
        if (blinkTicks >= 0) {
            target = Math.max(target, 1F - Math.abs(blinkTicks - blinkHalf) / (float) blinkHalf);
            if (++blinkTicks > blinkHalf * 2) blinkTicks = -1;
        }
        dark = wanted >= 0F || blinkTicks >= 0 ? target : Math.max(0F, dark - 1F / LIFT);
        wanted = -1F;
    }

    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(AlchyX.MODID, "scene_fade"), ScreenFade::draw);
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        float now = Mth.lerp(delta.getGameTimeDeltaPartialTick(false), darkPrev, dark);
        if (now <= 0.001F) return;
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), Math.round(now * MAX_ALPHA * 255) << 24 | color);
    }
}
