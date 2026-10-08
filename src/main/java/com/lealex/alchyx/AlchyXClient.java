package com.lealex.alchyx;

import com.lealex.alchyx.client.ChamberShellClientExtensions;
import com.lealex.alchyx.client.ChamberShellRenderer;
import com.lealex.alchyx.client.DisguiseModel;
import com.lealex.alchyx.client.machine.MachineScreen;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import com.lealex.alchyx.registry.AlchyXRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Client-only registrations (never loaded on a dedicated server). */
@Mod(value = AlchyX.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public class AlchyXClient {

    public AlchyXClient() {
        // Patchouli entries opened -> "read:<entry>" advancement criteria on the server (compat/patchouli/BookReading)
        if (net.neoforged.fml.ModList.get().isLoaded("patchouli")) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    com.lealex.alchyx.compat.patchouli.BookReadingClient::onScreenInit);
            // Books whose paper changes with the chapter (client/BookPapers)
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    com.lealex.alchyx.compat.patchouli.BookPapersClient::onClientTick);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    com.lealex.alchyx.compat.patchouli.BookPapersClient::onScreenInit);
        }
    }

    // Shells take their disguise's tint colors (water in a cauldron, grass...)
    @SubscribeEvent
    static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerBlock(ChamberShellClientExtensions.INSTANCE, AlchyXRegistries.CHAMBER_SHELL.get());
    }

    // The shell's model type: "type": "alchyx:disguise" in blockstates/chamber_shell.json
    @SubscribeEvent
    static void registerBlockStateModels(RegisterBlockStateModels event) {
        event.registerModel(DisguiseModel.ID, DisguiseModel.Unbaked.MAP_CODEC);
    }

    // The generic GUI of script-defined machines
    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AlchyXRegistries.MACHINE_MENU.get(), MachineScreen::new);
    }

    // Shell animations (piston strikes)
    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AlchyXRegistries.CHAMBER_SHELL_BE.get(), ChamberShellRenderer::new);
    }
}
