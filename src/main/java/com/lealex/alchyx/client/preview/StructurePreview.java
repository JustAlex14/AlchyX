package com.lealex.alchyx.client.preview;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.item.MachineTool;
import com.lealex.alchyx.menu.CoreMenu;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structure preview for machines that aren't formed: small ghost blocks show what's missing around the core, red
 * dust marks wrong blocks. Opening an unformed machine's GUI turns it on; a button on the GUI turns it on and off.
 * A core without a GUI shows it on right-click and hides it on sneak + right-click.
 * It switches itself off once the machine forms (or the core is gone). Client only, per player.
 */
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public final class StructurePreview {
    private StructurePreview() {}

    private static final float GHOST_SCALE = 0.5F;
    private static final int REFRESH_TICKS = 10;
    private static final double RANGE = 48.0;
    private static final DustParticleOptions WRONG = new DustParticleOptions(0xFF3030, 0.8F);
    private static final ContextKey<List<Ghost>> RENDER_KEY = new ContextKey<>(AlchyX.id("structure_preview"));

    private static final Set<BlockPos> SHOWN = new HashSet<>();
    private static final Set<BlockPos> HIDDEN = new HashSet<>();   // turned off by the player: don't auto-show again
    private static final Map<BlockPos, List<Ghost>> MISSING = new HashMap<>();
    private static final Map<BlockPos, List<BlockPos>> WRONG_BLOCKS = new HashMap<>();
    private static @Nullable ClientLevel lastLevel;

    private record Ghost(BlockPos pos, BlockState state) {}

    public static boolean isShown(BlockPos core) {
        return SHOWN.contains(core);
    }

    public static void toggle(BlockPos core) {
        if (SHOWN.remove(core)) {
            HIDDEN.add(core);
            MISSING.remove(core);
            WRONG_BLOCKS.remove(core);
        } else {
            HIDDEN.remove(core);
            SHOWN.add(core.immutable());
        }
    }

    // ---- The GUI button (any machine screen whose menu is a CoreMenu) ----

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof CoreMenu menu)) return;
        BlockPos pos = menu.getCorePos();
        Minecraft minecraft = Minecraft.getInstance();
        if (pos == null || minecraft.level == null || !(minecraft.level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core)) return;
        if (core.previewPatternId() == null) return;
        if (!HIDDEN.contains(pos)) SHOWN.add(pos.immutable()); // opening an unformed machine shows its structure

        int width = 90, height = 16;
        int x = screen.getLeftPos() + screen.getImageWidth() - width, y = screen.getTopPos() - height - 2;
        Button button = Button.builder(label(pos), b -> {
            toggle(pos);
            b.setMessage(label(pos));
        }).bounds(x, y, width, height).build();
        button.setTooltip(Tooltip.create(Component.literal(
                "The machine isn't formed yet. Ghost blocks show what's missing around the core; red dust marks wrong blocks.")));
        event.addListener(button);
    }

    private static Component label(BlockPos pos) {
        return Component.literal(isShown(pos) ? "Hide structure" : "Show structure");
    }

    // ---- Cores without a GUI: the click itself shows the structure ----

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getItemStack().getItem() instanceof MachineTool || event.getItemStack().getItem() instanceof BlockItem) return;
        BlockPos pos = event.getPos();
        if (!(event.getLevel().getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core)) return;
        if (core.previewPatternId() == null || core instanceof MenuProvider) return;
        if (event.getEntity().isShiftKeyDown()) {
            if (isShown(pos)) toggle(pos);
        } else if (!isShown(pos)) {
            toggle(pos);
        }
    }

    // ---- Keeping the missing blocks up to date ----

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != lastLevel) { // new world or dimension: start clean
            SHOWN.clear();
            HIDDEN.clear();
            MISSING.clear();
            WRONG_BLOCKS.clear();
            lastLevel = level;
        }
        if (level == null || SHOWN.isEmpty() || level.getGameTime() % REFRESH_TICKS != 0) return;
        for (BlockPos core : new ArrayList<>(SHOWN)) {
            if (!(level.getBlockEntity(core) instanceof MultiblockCoreBlockEntity entity) || entity.previewPatternId() == null) {
                SHOWN.remove(core); // formed (or gone): the preview's job is done
                MISSING.remove(core);
                WRONG_BLOCKS.remove(core);
                continue;
            }
            refresh(level, core, entity);
            if (minecraft.player != null && minecraft.player.distanceToSqr(Vec3.atCenterOf(core)) < RANGE * RANGE) {
                for (BlockPos wrong : WRONG_BLOCKS.getOrDefault(core, List.of())) {
                    level.addParticle(WRONG, wrong.getX() + 0.5, wrong.getY() + 0.5, wrong.getZ() + 0.5, 0, 0, 0);
                }
            }
        }
    }

    private static void refresh(ClientLevel level, BlockPos core, MultiblockCoreBlockEntity entity) {
        net.minecraft.resources.Identifier id = entity.previewPatternId();
        MultiblockPattern pattern = id == null ? null : MultiblockPatterns.client().get(id);
        if (pattern == null) return;
        Rotation rotation = pattern.rotationFor(entity.getBlockState());
        List<Ghost> missing = new ArrayList<>();
        List<BlockPos> wrong = new ArrayList<>();
        for (MultiblockPattern.PreviewCell cell : pattern.previewCells()) {
            if (cell.offset().equals(BlockPos.ZERO)) continue; // the core itself
            BlockPos pos = core.offset(cell.offset().rotate(rotation));
            BlockState world = level.getBlockState(pos);
            if (com.lealex.alchyx.multiblock.ShellParts.isPart(world)) continue; // already a part (a tier that grows)
            // A turned pattern: compare the block turned back to the written orientation, like the structure check
            BlockState asWritten = rotation == Rotation.NONE ? world : world.rotate(MultiblockPattern.inverse(rotation));
            if (cell.accepts().test(asWritten)) continue;
            if (world.isAir() || world.canBeReplaced()) {
                if (cell.display() != null) missing.add(new Ghost(pos, cell.display().rotate(rotation)));
            } else {
                wrong.add(pos);
            }
        }
        MISSING.put(core, missing);
        WRONG_BLOCKS.put(core, wrong);
    }

    // ---- Drawing the ghost blocks ----

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        if (MISSING.isEmpty()) {
            event.getRenderState().setRenderData(RENDER_KEY, null);
            return;
        }
        Vec3 camera = event.getCamera().position();
        List<Ghost> ghosts = new ArrayList<>();
        for (Map.Entry<BlockPos, List<Ghost>> entry : MISSING.entrySet()) {
            if (Vec3.atCenterOf(entry.getKey()).distanceToSqr(camera) < RANGE * RANGE) ghosts.addAll(entry.getValue());
        }
        event.getRenderState().setRenderData(RENDER_KEY, ghosts.isEmpty() ? null : ghosts);
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        List<Ghost> ghosts = event.getLevelRenderState().getRenderData(RENDER_KEY);
        Minecraft minecraft = Minecraft.getInstance();
        if (ghosts == null || minecraft.level == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        float time = minecraft.level.getGameTime() + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float pulse = GHOST_SCALE + 0.05F * (float) Math.sin(time / 6.0); // a gentle pulse, so ghosts read as hints
        for (Ghost ghost : ghosts) {
            MovingBlockRenderState state = new MovingBlockRenderState();
            state.randomSeedPos = ghost.pos();
            state.blockPos = ghost.pos();
            state.blockState = ghost.state();
            state.biome = minecraft.level.getBiome(ghost.pos());
            state.cardinalLighting = minecraft.level.cardinalLighting();
            state.lightEngine = minecraft.level.getLightEngine();
            poseStack.pushPose();
            poseStack.translate(ghost.pos().getX() - camera.x + 0.5, ghost.pos().getY() - camera.y + 0.5, ghost.pos().getZ() - camera.z + 0.5);
            poseStack.scale(pulse, pulse, pulse);
            poseStack.translate(-0.5, -0.5, -0.5);
            collector.submitMovingBlock(poseStack, state);
            poseStack.popPose();
        }
    }
}
