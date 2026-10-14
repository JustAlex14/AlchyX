package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Makes whatever an entity's own renderer draws look like a ghost: every model and model part it submits (its body and
 * all its layers: a sheep's wool, a saddle...) goes on see-through, glowing at full brightness, a little washed
 * toward white (vanilla's white overlay, the one a swelling creeper flashes with) and tinted (a mod picks the colour: AlchyFarm's haunted herd is a sickly green).
 * Everything else the renderer submits passes through unchanged.
 *
 * See-through needs a translucent render type, and the renderer only hands over its own (usually a cutout, which
 * ignores transparency). The same picture's translucent type is made from the texture the given type draws with.
 * A render type keeps that texture private, so it is read by reflection, once per type, and remembered. If that
 * ever fails (the game's classes changed), the types are left as they are: the ghosts are then tinted in colour
 * only, not see-through, and a line in the log says so once.
 */
public final class GhostCollector implements SubmitNodeCollector {
    private static final int LIGHT = LightCoordsUtil.FULL_BRIGHT;

    private static final Map<RenderType, RenderType> SEE_THROUGH = new IdentityHashMap<>();
    private static boolean cannotRead;
    private static @Nullable Field setupField, texturesField;
    private static @Nullable Method locationMethod;

    /** The translucent render type drawing the same texture as this one (itself if that can't be worked out). */
    private static RenderType seeThrough(RenderType type) {
        RenderType known = SEE_THROUGH.get(type);
        if (known != null) return known;
        RenderType made = type;
        if (!cannotRead) {
            try {
                if (setupField == null) {
                    setupField = RenderType.class.getDeclaredField("state");
                    setupField.setAccessible(true);
                    texturesField = Class.forName("net.minecraft.client.renderer.rendertype.RenderSetup").getDeclaredField("textures");
                    texturesField.setAccessible(true);
                    locationMethod = Class.forName("net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding").getDeclaredMethod("location");
                    locationMethod.setAccessible(true);
                }
                Object binding = ((Map<?, ?>) texturesField.get(setupField.get(type))).get("Sampler0");
                if (binding != null && locationMethod.invoke(binding) instanceof Identifier texture) made = RenderTypes.entityTranslucent(texture);
            } catch (ReflectiveOperationException | RuntimeException e) {
                cannotRead = true;
                AlchyX.LOGGER.warn("AlchyX: ghosts can't be drawn see-through here ({}): they keep their colour only", e.toString());
            }
        }
        if (SEE_THROUGH.size() > 256) SEE_THROUGH.clear(); // types from before a resource reload
        SEE_THROUGH.put(type, made);
        return made;
    }

    private final OrderedSubmitNodeCollector inner;
    private final int tint, wash;

    /**
     * @param tint the ghost's colour and how solid it is (ARGB), multiplied into whatever colour the renderer asks for
     * @param white how far it is washed toward white, 0 to 1 (vanilla's white overlay)
     */
    public GhostCollector(OrderedSubmitNodeCollector inner, int tint, float white) {
        this(inner, tint, OverlayTexture.pack(OverlayTexture.u(white), OverlayTexture.v(false)));
    }

    private GhostCollector(OrderedSubmitNodeCollector inner, int tint, int wash) {
        this.inner = inner;
        this.tint = tint;
        this.wash = wash;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return inner instanceof SubmitNodeCollector whole ? new GhostCollector(whole.order(order), tint, wash) : this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
                                int tintedColor, @Nullable TextureAtlasSprite sprite, int outlineColor,
                                ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
        inner.submitModel(model, state, poseStack, seeThrough(renderType), LIGHT, wash, ARGB.multiply(tintedColor, tint), sprite, outlineColor, crumblingOverlay);
    }

    @Override
    public void submitModelPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
                                @Nullable TextureAtlasSprite sprite, boolean sheeted, boolean hasFoil, int tintedColor,
                                ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay, int outlineColor) {
        inner.submitModelPart(modelPart, poseStack, seeThrough(renderType), LIGHT, wash, sprite, sheeted, hasFoil, ARGB.multiply(tintedColor, tint),
                crumblingOverlay, outlineColor);
    }

    // ---- Everything else: as it is ----

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        inner.submitShadow(poseStack, radius, pieces);
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords,
                              double distanceToCameraSq, CameraRenderState camera) {
        inner.submitNameTag(poseStack, nameTagAttachment, offset, name, seeThrough, lightCoords, distanceToCameraSq, camera);
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean dropShadow, Font.DisplayMode displayMode,
                           int lightCoords, int color, int backgroundColor, int outlineColor) {
        inner.submitText(poseStack, x, y, text, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        inner.submitFlame(poseStack, renderState, rotation);
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        inner.submitLeash(poseStack, leashState);
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {
        inner.submitMovingBlock(poseStack, movingBlockRenderState);
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tints, int lightCoords,
                                 int overlayCoords, int outlineColor) {
        inner.submitBlockModel(poseStack, renderType, parts, tints, LIGHT, overlayCoords, outlineColor);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {
        inner.submitBreakingBlockModel(poseStack, model, seed, progress);
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor,
                           int[] tintLayers, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
        inner.submitItem(poseStack, displayContext, LIGHT, overlayCoords, outlineColor, tintLayers, quads, foilType);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        inner.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
    }

    @Override
    public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
        inner.submitParticleGroup(particleGroupRenderer);
    }
}
