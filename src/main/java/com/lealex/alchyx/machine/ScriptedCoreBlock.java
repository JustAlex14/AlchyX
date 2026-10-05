package com.lealex.alchyx.machine;

import com.lealex.alchyx.block.FacingCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The core block of a machine defined from a KubeJS startup script ({@code alchyx:machine_core} block type).
 * It faces the player who places it; the structure, slots and recipes come from its {@link MachineSpec}.
 */
public class ScriptedCoreBlock extends FacingCoreBlock {
    private final MachineSpec spec;

    public ScriptedCoreBlock(Properties properties, MachineSpec spec) {
        super(properties);
        this.spec = spec;
    }

    public MachineSpec spec() {
        return spec;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScriptedCoreBlockEntity(pos, state);
    }
}
