package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.client.miniature.Miniatures;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;

import java.util.List;

/**
 * Draws the Machine Studio's 3D view into its picture (see {@link StudioRenderState}). The view is turned like the
 * screen's {@link StudioView} says, so what the mouse points at there is what shows here.
 */
public class StudioRenderer extends PictureInPictureRenderer<StudioRenderState> {
    public StudioRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<StudioRenderState> getRenderStateClass() {
        return StudioRenderState.class;
    }

    @Override
    protected void renderToTexture(StudioRenderState state, PoseStack poseStack) {
        // (the pose already puts the picture's middle at the origin, one unit = state.scale() gui pixels)
        StudioView.turn(poseStack, state.yaw(), state.pitch());
        poseStack.translate(-state.center().x, -state.center().y, -state.center().z);
        FeatureRenderDispatcher features = Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher();
        CameraRenderState camera = new CameraRenderState();
        int light = LightCoordsUtil.FULL_BRIGHT;
        Miniatures.submit(poseStack, features.getSubmitNodeStorage(), camera, state.still(), light, List.of());
        for (StudioRenderState.Piece piece : state.pieces()) {
            poseStack.pushPose();
            poseStack.translate(piece.at().x + 0.5, piece.at().y + 0.5, piece.at().z + 0.5);
            poseStack.mulPose(Axis.XP.rotationDegrees((float) piece.rotation().x));
            poseStack.mulPose(Axis.YP.rotationDegrees((float) piece.rotation().y));
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) piece.rotation().z));
            poseStack.scale(piece.size(), piece.size(), piece.size());
            poseStack.translate(-0.5, -1.5, -0.5); // (a piece's mesh holds its block one up)
            Miniatures.submit(poseStack, features.getSubmitNodeStorage(), camera, piece.mesh(), light, List.of());
            poseStack.popPose();
        }
        features.renderAllFeatures();
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F; // the view turns around the picture's middle
    }

    @Override
    protected String getTextureLabel() {
        return "alchyx machine studio";
    }
}
