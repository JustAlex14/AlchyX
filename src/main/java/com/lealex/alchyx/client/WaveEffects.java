package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyXClientConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Client effects for veiled waves (MultiblockCoreBlockEntity#veilsWave): phaseOut when a block turns into a veil,
 * materialize when its core's animation brings it back. (Ordinary waves just switch blocks: a puff from the server.)
 * Materialize: a block of a forming machine just turned into its new look.
 * Sparks trace the block's twelve edges and drift inward, a violet flash pops in its middle, and void puffs out of
 * its faces. (Nothing breaks: the old block simply phases into the new look.) Amount set in
 * alchyx-client.toml (formationWave).
 */
public final class WaveEffects {
    private WaveEffects() {}

    private static final int GLOW = 0xFFB98CFF;
    private static final int POINTS_PER_EDGE = 4;

    /** A veiled block phasing out of the world (its core draws it see-through until it comes back): a few motes. */
    public static void phaseOut(Level level, BlockPos pos) {
        float density = AlchyXClientConfig.waveParticles();
        RandomSource random = level.getRandom();
        int motes = Math.round(4 * density);
        for (int i = 0; i < motes; i++) {
            double x = pos.getX() + random.nextDouble(), y = pos.getY() + random.nextDouble(), z = pos.getZ() + random.nextDouble();
            level.addParticle(ParticleTypes.REVERSE_PORTAL, x, y, z, 0, 0.02, 0);
        }
    }

    public static void materialize(Level level, BlockPos pos, BlockState look, boolean hidden) {
        float density = AlchyXClientConfig.waveParticles();
        if (density <= 0) return;
        RandomSource random = level.getRandom();
        Vec3 center = Vec3.atCenterOf(pos);
        // the twelve edges of the block, traced with sparks drawn toward its middle
        for (int edge = 0; edge < 12; edge++) {
            int axis = edge / 4, a = edge % 2, b = edge / 2 % 2;
            for (int k = 0; k < POINTS_PER_EDGE; k++) {
                if (random.nextFloat() > density) continue;
                double along = (k + random.nextDouble()) / POINTS_PER_EDGE;
                double x = axis == 0 ? along : a, y = axis == 1 ? along : (axis == 0 ? a : b), z = axis == 2 ? along : b;
                Vec3 p = Vec3.atLowerCornerOf(pos).add(x, y, z);
                Vec3 v = center.subtract(p).scale(0.04);
                level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, v.x, v.y, v.z);
            }
        }
        if (hidden) return;
        level.addParticle(ColorParticleOption.create(ParticleTypes.FLASH, GLOW), center.x, center.y, center.z, 0, 0, 0);
        // void puffing out of the faces
        int puffs = Math.round(8 * density);
        for (int i = 0; i < puffs; i++) {
            net.minecraft.core.Direction face = net.minecraft.core.Direction.getRandom(random);
            Vec3 p = center.add(face.getStepX() * 0.52 + (face.getStepX() == 0 ? random.nextDouble() - 0.5 : 0),
                    face.getStepY() * 0.52 + (face.getStepY() == 0 ? random.nextDouble() - 0.5 : 0),
                    face.getStepZ() * 0.52 + (face.getStepZ() == 0 ? random.nextDouble() - 0.5 : 0));
            level.addParticle(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, face.getStepX() * 0.05, face.getStepY() * 0.05, face.getStepZ() * 0.05);
        }
    }
}
