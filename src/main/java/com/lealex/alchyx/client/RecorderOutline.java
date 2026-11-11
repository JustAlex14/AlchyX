package com.lealex.alchyx.client;

import com.lealex.alchyx.item.StructureRecorderItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The box a Structure Recorder marks, drawn as lines of motes along its twelve edges while the tool is in hand (a
 * first corner alone shows as its own block). Client only.
 */
public final class RecorderOutline {
    private RecorderOutline() {}

    private static final int EVERY = 4; // ticks between two draws

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || minecraft.isPaused() || level.getGameTime() % EVERY != 0) return;
        ItemStack stack = LinkLines.inHand(minecraft.player, StructureRecorderItem.class);
        if (stack == null) return;
        BlockPos a = StructureRecorderItem.first(stack);
        if (a == null) return;
        BlockPos b = StructureRecorderItem.second(stack);
        if (b == null) b = a;
        BlockPos min = BlockPos.min(a, b), max = BlockPos.max(a, b);
        double x0 = min.getX(), y0 = min.getY(), z0 = min.getZ(), x1 = max.getX() + 1, y1 = max.getY() + 1, z1 = max.getZ() + 1;
        double[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
        for (int i = 0; i < 4; i++) {
            double[] p = corners[i], q = corners[(i + 1) % 4];
            edge(level, p[0], y0, p[2], q[0], y0, q[2]); // bottom
            edge(level, p[0], y1, p[2], q[0], y1, q[2]); // top
            edge(level, p[0], y0, p[2], p[0], y1, p[2]); // upright
        }
    }

    private static void edge(ClientLevel level, double ax, double ay, double az, double bx, double by, double bz) {
        LinkLines.line(level, new Vec3(ax, ay, az), new Vec3(bx, by, bz), ParticleTypes.ELECTRIC_SPARK, 64, EVERY);
    }
}
