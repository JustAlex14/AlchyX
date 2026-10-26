package com.lealex.alchyx.client.scene;

import com.lealex.alchyx.AlchyX;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pictures drawn over a player's skin (tattoos, veins, war paint, glyphs): a mod registers a {@link Source} that lists
 * the overlays a player wears this frame, each a texture in the player skin's layout (64 x 64), a tint (argb) and
 * whether it glows. They are drawn on copies of the player model a hair larger than the skin (as armour is), one shell
 * per {@link Overlay#shell()} (0 closest), so they never fight the skin for its pixels, and posed with it (walking,
 * a scene's pose, the inventory). Not drawn on an invisible player.
 * <p>
 * Tint goes through the long submitModel (light, overlay, TINT, sprite, outline): the short form's last int is an
 * outline colour, and passed a tint it draws the model as a white silhouette.
 */
public final class BodyOverlays {
    private BodyOverlays() {}

    /** One picture over the skin: texture (skin layout), tint (argb), glowing (full bright) or lit, shell (0..3). */
    public record Overlay(Identifier texture, int argb, boolean glowing, int shell) {}

    public interface Source {
        /** Add the overlays this player wears this frame (called for every player drawn, every frame). */
        void collect(Player player, float partialTick, List<Overlay> out);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    private static final ContextKey<List<Overlay>> OVERLAYS = new ContextKey<>(Identifier.fromNamespaceAndPath(AlchyX.MODID, "body_overlays"));
    private static final int SHELLS = 4;
    private static final float STEP = 0.015F;

    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** From the avatar state modifier (registered by AlchyX). */
    public static void apply(Player player, AvatarRenderState state) {
        if (SOURCES.isEmpty()) return;
        List<Overlay> out = new ArrayList<>();
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Source source : SOURCES) source.collect(player, partial, out);
        state.setRenderData(OVERLAYS, out.isEmpty() ? List.of() : out);
    }

    /** The layer added to every player renderer, slim or wide. */
    public static final class Layer extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final PlayerModel[] shells = new PlayerModel[SHELLS];

        public Layer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, boolean slim) {
            super(parent);
            for (int i = 0; i < SHELLS; i++) {
                var mesh = PlayerModel.createMesh(new CubeDeformation(STEP * (i + 1)), slim);
                shells[i] = new PlayerModel(LayerDefinition.create(mesh, 64, 64).bakeRoot(), slim);
            }
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
            List<Overlay> overlays = state.getRenderData(OVERLAYS);
            if (overlays == null || overlays.isEmpty() || state.isInvisible) return;
            for (Overlay overlay : overlays) {
                PlayerModel model = shells[Math.clamp(overlay.shell(), 0, SHELLS - 1)];
                var type = overlay.glowing() ? RenderTypes.entityTranslucentEmissive(overlay.texture()) : RenderTypes.entityTranslucent(overlay.texture());
                collector.submitModel(model, state, poseStack, type, light, OverlayTexture.NO_OVERLAY, overlay.argb(), null, 0, null);
            }
        }
    }

    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            var renderer = event.getPlayerRenderer(skin);
            if (renderer != null) renderer.addLayer(new Layer(renderer, skin == PlayerModelType.SLIM));
        }
    }
}
