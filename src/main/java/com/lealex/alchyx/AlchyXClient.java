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
        // Scenes: scripted walks, the screen fade, whole-body poses (client/scene)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.SceneWalk::onPlayerTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.SceneWalk::onPlayerTickPost);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.SceneWalk::onRenderFrame);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.ScreenFade::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.CameraShots::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.CameraShots::onCameraDistance);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.CameraShots::onRenderLayer);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, com.lealex.alchyx.client.scene.BodyPoses::onRenderPre);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, com.lealex.alchyx.client.scene.BodyPoses::onRenderPost);
        com.lealex.alchyx.client.scene.BodyPoses.init();
        // Mod trailers: a camera flown along a file's shots (/trailer play <id>; client/scene/TrailerPlayer)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.TrailerPlayer::onRenderFrame);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.TrailerPlayer::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.TrailerPlayer::onCameraAngles);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.TrailerPlayer::onFov);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.TrailerPlayer::onBlockOverlay);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, com.lealex.alchyx.client.scene.TrailerPlayer::onRenderLiving);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.scene.TrailerPlayer::onRegisterCommands);
        com.lealex.alchyx.scene.WalkScenes.clientStart = com.lealex.alchyx.client.scene.SceneWalk::start;
        // Growing machines' parts flying in, sinking away, rising out of the ground (client/growth/GrowthParts)
        com.lealex.alchyx.growth.GrowthPayload.clientReceive = com.lealex.alchyx.client.growth.GrowthParts::receive;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.growth.GrowthParts::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.growth.GrowthParts::onExtract);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.lealex.alchyx.client.growth.GrowthParts::onSubmit);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) -> {
            net.minecraft.world.item.component.CustomData kept = event.getItemStack().get(com.lealex.alchyx.registry.AlchyXRegistries.KEPT_CORE.get());
            if (kept == null) return;
            int upgrades = kept.copyTag().getIntOr("alchyx_upgrade_count", 0);
            if (upgrades > 0) {
                event.getToolTip().add(Math.min(1, event.getToolTip().size()), net.minecraft.network.chat.Component.translatable(
                        "tooltip.alchyx.kept_upgrades", upgrades).withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
            }
        });
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

    // Scenes: the fade over the screen, pictures over players' skins, the pose and overlay data on players' render states
    @SubscribeEvent
    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        com.lealex.alchyx.client.scene.ScreenFade.onRegisterLayers(event);
        com.lealex.alchyx.client.scene.TrailerPlayer.onRegisterLayers(event); // over the fade: titles show on black
        com.lealex.alchyx.client.LookMeters.onRegisterLayers(event); // what a looked-at machine holds
    }

    @SubscribeEvent
    static void addReloadListeners(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent event) {
        com.lealex.alchyx.client.scene.Trailers.onAddReloadListeners(event); // assets/<ns>/trailers/*.json
    }

    @SubscribeEvent
    static void addLayers(EntityRenderersEvent.AddLayers event) {
        com.lealex.alchyx.client.scene.BodyOverlays.onAddLayers(event);
    }

    @SubscribeEvent
    static void registerRenderStateModifiers(net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier() {
            @Override
            public <T extends net.minecraft.world.entity.Avatar & net.minecraft.client.entity.ClientAvatarEntity> void accept(T entity,
                    net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
                if (!(entity instanceof net.minecraft.world.entity.player.Player player)) return;
                com.lealex.alchyx.client.scene.BodyPoses.apply(player, state);
                com.lealex.alchyx.client.scene.BodyOverlays.apply(player, state);
            }
        });
    }

    // Shell animations (piston strikes)
    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AlchyXRegistries.CHAMBER_SHELL_BE.get(), ChamberShellRenderer::new);
    }
}
