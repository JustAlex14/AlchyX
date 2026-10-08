package com.lealex.alchyx.client.miniature;

import com.lealex.alchyx.miniature.BoxWatch;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * Drawing a miniature, and asking the server for a live one.
 *
 * To draw: set the pose where the box's lowest corner goes, at the scale wanted (and any turn), then
 * {@link #submit}. A mod with a fixed miniature keeps its {@link BoxMesh} and calls this every frame; nothing else
 * is needed.
 */
public final class Miniatures {
    private Miniatures() {}

    /**
     * Draws a box's mesh, its item-shown blocks and its figures in the current pose (one unit = one block of the
     * box, origin = its lowest corner), all lit with {@code light}.
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
                              BoxMesh mesh, int light, List<Puppets.Prepared> figures) {
        if (!mesh.solid.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.solidMovingBlock(), (pose, buffer) -> mesh.solid.emit(pose, buffer, light));
        }
        if (!mesh.cutout.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.cutoutMovingBlock(), (pose, buffer) -> mesh.cutout.emit(pose, buffer, light));
        }
        if (!mesh.translucent.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), (pose, buffer) -> mesh.translucent.emit(pose, buffer, light));
        }
        for (BoxMesh.ItemCell cell : mesh.items) {
            poseStack.pushPose();
            poseStack.translate(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(cell.turn()));
            cell.item().submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        for (Puppets.Prepared figure : figures) {
            poseStack.pushPose();
            poseStack.translate(figure.x(), figure.y(), figure.z());
            Minecraft.getInstance().getEntityRenderDispatcher().submit(figure.state(), camera, 0, 0, 0, poseStack, collector);
            poseStack.popPose();
        }
    }

    /**
     * Asks the server what a key shows (see {@link BoxWatch}). Ask again every few seconds while the picture is
     * wanted: that is what keeps the server watching. Answers land in {@link BoxWatch#clientBox}.
     */
    public static void request(Identifier kind, GlobalPos key) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(new ServerboundCustomPayloadPacket(new BoxWatch.Request(kind, key, BoxWatch.clientBox(key) != null)));
        }
    }
}
