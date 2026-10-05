package com.lealex.alchyx.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * A block shown see-through: its shape's boxes, each face painted with the block's (particle) texture at low alpha
 * and tinted. For veiled blocks waiting for their core's animation to bring them back (see ChamberShellBlockEntity
 * #setClientHidden). Client only.
 */
public final class Veil {
    private Veil() {}

    /** Draws {@code state} as a veil at the pose's origin (the block's lower corner); color is ARGB (alpha = opacity). */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, BlockGetter level, BlockPos pos,
                              BlockState state, int color) {
        TextureAtlasSprite sprite;
        try {
            sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        } catch (Exception e) {
            return;
        }
        List<AABB> boxes = state.getShape(level, pos).toAabbs();
        if (boxes.isEmpty()) boxes = List.of(new AABB(0, 0, 0, 1, 1, 1));
        List<AABB> shape = boxes;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(sprite.atlasLocation()), (pose, buffer) -> {
            for (AABB box : shape) {
                for (Direction side : Direction.values()) face(pose, buffer, box, side, sprite, color);
            }
        });
    }

    private static void face(PoseStack.Pose pose, VertexConsumer buffer, AABB b, Direction side, TextureAtlasSprite sprite, int color) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ, x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        float[][] c = switch (side) {
            case DOWN -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}};
            case UP -> new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}};
            case NORTH -> new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
            case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
        };
        float[][] uv = {{sprite.getU0(), sprite.getV1()}, {sprite.getU1(), sprite.getV1()}, {sprite.getU1(), sprite.getV0()}, {sprite.getU0(), sprite.getV0()}};
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 4; k++) {
                int i = pass == 0 ? k : 3 - k;
                buffer.addVertex(pose, c[i][0], c[i][1], c[i][2]).setColor(color).setUv(uv[i][0], uv[i][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
                        .setNormal(pose, side.getStepX(), side.getStepY(), side.getStepZ());
            }
        }
    }
}
