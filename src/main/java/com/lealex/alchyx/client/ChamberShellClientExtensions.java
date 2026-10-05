package com.lealex.alchyx.client;

import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

import java.util.List;

/**
 * Client extras for chamber shells. Tints: some models are grey textures colored by the game at render time
 * (water in a cauldron, grass, leaves, redstone...). NeoForge asks the shell for its tint values here, and the
 * shell hands back the colors its disguise would get at this position (e.g. the biome's water color).
 */
public final class ChamberShellClientExtensions implements IClientBlockExtensions {
    public static final ChamberShellClientExtensions INSTANCE = new ChamberShellClientExtensions();

    private ChamberShellClientExtensions() {}

    @Override
    public void collectDynamicTintValues(BlockState state, BlockAndTintGetter level, BlockPos pos, IntList tintValues) {
        BlockState disguise = ChamberShellBlock.disguiseAt(level, pos);
        if (disguise == null) return;
        List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(disguise);
        // A shell can replace the colors (a cauldron of distortion fluid uses the water texture, tinted purple)
        Integer override = level.getModelData(pos).get(ChamberShellBlockEntity.TINT);
        for (BlockTintSource source : sources) {
            if (override != null && override != 0) tintValues.add(override.intValue());
            else tintValues.add(source == null ? -1 : source.colorInWorld(disguise, level, pos));
        }
    }
}
