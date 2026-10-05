package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The chamber shell's model: draws whatever block the shell is disguised as.
 *
 * It's a custom block state model type ("type": "alchyx:disguise" in the blockstate file). While a chunk is
 * meshed, it reads the disguise from the shell's model data and hands over to that block's own model, so the
 * shell gets the exact look, transparency and culling of the real block. Without data it draws "fallback".
 */
public record DisguiseModel(BlockStateModel fallback) implements DynamicBlockStateModel {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AlchyX.MODID, "disguise");

    private static @Nullable BlockState disguise(BlockAndTintGetter level, BlockPos pos) {
        BlockState state = level.getModelData(pos).get(ChamberShellBlockEntity.DISGUISE);
        // Never disguise as a shell: that would ask this model again forever
        return state == null || state.getBlock() instanceof ChamberShellBlock ? null : state;
    }

    private static BlockStateModel modelOf(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        if (Boolean.TRUE.equals(level.getModelData(pos).get(ChamberShellBlockEntity.HIDDEN))) return; // invisible part
        BlockState disguise = disguise(level, pos);
        if (disguise == null) {
            fallback.collectParts(level, pos, state, random, parts);
        } else {
            modelOf(disguise).collectParts(level, pos, disguise, random, parts);
        }
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        BlockState disguise = disguise(level, pos);
        return disguise == null
                ? fallback.particleMaterial(level, pos, state)
                : modelOf(disguise).particleMaterial(level, pos, disguise);
    }

    @Override
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        BlockState disguise = disguise(level, pos);
        return disguise == null
                ? fallback.materialFlags(level, pos, state)
                : modelOf(disguise).materialFlags(level, pos, disguise);
    }

    @Override
    @Deprecated
    public Material.Baked particleMaterial() {
        return fallback.particleMaterial();
    }

    @Override
    @Deprecated
    public int materialFlags() {
        return fallback.materialFlags();
    }

    /** What the blockstate file describes: {"type": "alchyx:disguise", "fallback": {"model": "..."}}. */
    public record Unbaked(BlockStateModel.Unbaked fallback) implements CustomUnbakedBlockStateModel {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BlockStateModel.Unbaked.CODEC.fieldOf("fallback").forGetter(Unbaked::fallback)
        ).apply(instance, Unbaked::new));

        @Override
        public MapCodec<Unbaked> codec() {
            return MAP_CODEC;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            fallback.resolveDependencies(resolver);
        }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            return new DisguiseModel(fallback.bake(baker));
        }
    }
}
