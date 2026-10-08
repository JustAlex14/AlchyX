package com.lealex.alchyx.client.miniature;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.miniature.BoxWatch;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Leaving a world or a server: nothing of its miniatures is kept for the next one. Reloading the game's models:
 * every mesh built so far is stale.
 */
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public final class MiniatureClientEvents {
    private MiniatureClientEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BoxWatch.clientClear();
        BoxMesh.clearCaches();
        Puppets.clear();
    }

    @SubscribeEvent
    public static void onModelsBaked(ModelEvent.BakingCompleted event) {
        BoxMesh.modelsReloaded();
    }
}
