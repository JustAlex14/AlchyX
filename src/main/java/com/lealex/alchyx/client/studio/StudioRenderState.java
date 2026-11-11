package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.client.miniature.BoxMesh;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One frame of the Machine Studio's 3D view, for {@link StudioRenderer}: the still blocks as one mesh, each moving
 * block as its own small mesh with where it is now, and the marks over the chosen blocks. Positions are in the
 * view's own units (one per block), turned by {@code yaw} and {@code pitch} around {@code center}.
 */
public record StudioRenderState(BoxMesh still, List<Piece> pieces, Vec3 center, float yaw, float pitch,
                                int x0, int y0, int x1, int y1, float scale,
                                @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
        implements PictureInPictureRenderState {

    /**
     * One block drawn on its own: its mesh (the block at 0 1 0, over a cell of air), where its lowest corner is, its turn in degrees
     * around its middle, and its size.
     */
    public record Piece(BoxMesh mesh, Vec3 at, Vec3 rotation, float size) {}

    public StudioRenderState(BoxMesh still, List<Piece> pieces, Vec3 center, float yaw, float pitch,
                             int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea) {
        this(still, pieces, center, yaw, pitch, x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}
