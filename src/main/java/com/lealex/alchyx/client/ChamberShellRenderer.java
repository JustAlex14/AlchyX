package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyXApi;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Animates chamber shells disguised as pistons: while a shell is "striking" its disguise is the extended piston
 * base (no face plate) and this renderer slides the piston head out one block toward the core and back,
 * the same way vanilla draws a moving piston head. Shells that aren't striking pistons draw nothing here.
 */
public class ChamberShellRenderer implements BlockEntityRenderer<ChamberShellBlockEntity, ChamberShellRenderer.State> {

    public static class State extends BlockEntityRenderState {
        public @Nullable MovingBlockRenderState head;
        public double dx, dy, dz; // head offset from the base, in blocks
    }

    public ChamberShellRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ChamberShellBlockEntity shell, State state, float partialTicks,
                                   Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(shell, state, partialTicks, cameraPosition, breakProgress);
        state.head = null;
        BlockState disguise = shell.getDisguise();
        if (!(shell.getLevel() instanceof ClientLevel level)
                || !(disguise.getBlock() instanceof PistonBaseBlock)
                || !disguise.getValue(PistonBaseBlock.EXTENDED)) {
            return;
        }

        float elapsed = (level.getGameTime() - shell.getStrikeStart()) + partialTicks;
        float extension = ChamberShellBlockEntity.strikeExtension(elapsed);
        Direction facing = disguise.getValue(PistonBaseBlock.FACING);
        state.dx = facing.getStepX() * extension;
        state.dy = facing.getStepY() * extension;
        state.dz = facing.getStepZ() * extension;

        // Like vanilla: the arm is "short" while the head is still mostly inside the base
        // Custom piston looks get their matching head (AlchyXApi.registerPistonHead)
        BlockState headBase = AlchyXApi.pistonHeadFor(disguise);
        BlockState head = headBase
                .setValue(PistonHeadBlock.FACING, facing)
                .setValue(PistonHeadBlock.TYPE, disguise.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT)
                .setValue(PistonHeadBlock.SHORT, extension < 0.5F);

        BlockPos lightPos = shell.getBlockPos().relative(facing); // lit like the space the head moves into
        MovingBlockRenderState moving = new MovingBlockRenderState();
        moving.randomSeedPos = shell.getBlockPos();
        moving.blockPos = lightPos;
        moving.blockState = head;
        moving.biome = level.getBiome(lightPos);
        moving.cardinalLighting = level.cardinalLighting();
        moving.lightEngine = level.getLightEngine();
        state.head = moving;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.head == null) return;
        poseStack.pushPose();
        poseStack.translate(state.dx, state.dy, state.dz);
        collector.submitMovingBlock(poseStack, state.head);
        poseStack.popPose();
    }

    // The head moves one block out of the shell: keep it visible when only that block is on screen
    // Every visible shell is asked every frame: answer without allocating, and skip all but striking pistons
    // before a render state is even created
    @Override
    public boolean shouldRender(ChamberShellBlockEntity shell, Vec3 cameraPosition) {
        BlockState disguise = shell.getDisguise();
        return disguise.getBlock() instanceof PistonBaseBlock && disguise.getValue(PistonBaseBlock.EXTENDED)
                && BlockEntityRenderer.super.shouldRender(shell, cameraPosition);
    }

    @Override
    public AABB getRenderBoundingBox(ChamberShellBlockEntity shell) {
        return shell.renderBox();
    }
}
