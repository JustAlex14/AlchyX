package com.lealex.alchyx.client;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.AlchyXClientConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Something sent along a link, shown on the client: a head running straight from one point to another (eased: quick
 * off, slowing in), two strands of its particle winding round the line behind it, the line itself glimpsed now and
 * then, a small burst (and a sound) where it lands. A mod adds one when its own packet says something was sent; how
 * many particles follow AlchyXClientConfig's particle amount.
 */
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public final class GuidedStreams {
    private GuidedStreams() {}

    private static final class Stream {
        final Vec3 from, to, side, up;
        final ParticleOptions trail;
        final @Nullable ParticleOptions path;
        final @Nullable SoundEvent landing;
        final int ticks;
        int age = 0;

        Stream(Vec3 from, Vec3 to, ParticleOptions trail, @Nullable ParticleOptions path, @Nullable SoundEvent landing, int ticks) {
            this.from = from;
            this.to = to;
            this.trail = trail;
            this.path = path;
            this.landing = landing;
            this.ticks = Math.max(1, ticks);
            Vec3 dir = to.subtract(from).normalize();
            Vec3 across = dir.cross(Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
            this.side = across;
            this.up = dir.cross(across).normalize();
        }

        Vec3 at(float t) {
            float e = t * t * (3 - 2 * t);
            return from.lerp(to, e);
        }
    }

    private static final List<Stream> STREAMS = new ArrayList<>();

    /**
     * @param trail   the particle of the head and its strands
     * @param path    a particle glimpsing the whole line every few ticks (null: none)
     * @param landing played where it lands (null: silent)
     * @param ticks   how long it flies
     */
    public static void add(Vec3 from, Vec3 to, ParticleOptions trail, @Nullable ParticleOptions path, @Nullable SoundEvent landing, int ticks) {
        STREAMS.add(new Stream(from, to, trail, path, landing, ticks));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            STREAMS.clear();
            return;
        }
        if (minecraft.isPaused() || STREAMS.isEmpty()) return;
        RandomSource random = level.getRandom();
        float amount = AlchyXClientConfig.particleScale();
        for (Iterator<Stream> it = STREAMS.iterator(); it.hasNext(); ) {
            Stream stream = it.next();
            stream.age++;
            float t = Mth.clamp(stream.age / (float) stream.ticks, 0, 1);
            int behind = amount >= 1 ? 3 : amount >= 0.4F ? 2 : 1;
            for (int k = 0; k < 2; k++) { // the two strands, half a turn apart
                for (int i = 0; i < behind; i++) {
                    Vec3 p = stream.at(Math.max(0, t - i * 0.03F));
                    double a = stream.age * 0.9 - i * 0.5 + k * Math.PI;
                    double r = 0.14;
                    Vec3 q = p.add(stream.side.scale(Math.cos(a) * r)).add(stream.up.scale(Math.sin(a) * r));
                    level.addParticle(stream.trail, q.x, q.y, q.z, 0, 0, 0);
                }
            }
            if (stream.path != null && stream.age % 4 == 0 && amount >= 0.4F) { // the line it follows, glimpsed
                double length = stream.from.distanceTo(stream.to);
                double step = amount >= 1 ? 1.0 : 2.0;
                for (double d = 0; d < length; d += step) {
                    Vec3 p = stream.from.lerp(stream.to, (d + random.nextDouble() * 0.5) / Math.max(0.01, length));
                    level.addParticle(stream.path, p.x, p.y, p.z, 0, 0, 0);
                }
            }
            if (stream.age >= stream.ticks) {
                int burst = Math.max(1, Math.round(6 * amount));
                for (int i = 0; i < burst; i++) {
                    level.addParticle(stream.trail, stream.to.x, stream.to.y, stream.to.z,
                            (random.nextDouble() - 0.5) * 0.1, (random.nextDouble() - 0.5) * 0.1, (random.nextDouble() - 0.5) * 0.1);
                }
                if (stream.landing != null) {
                    level.playLocalSound(stream.to.x, stream.to.y, stream.to.z, stream.landing, SoundSource.BLOCKS, 0.5F, 0.8F + random.nextFloat() * 0.3F, false);
                }
                it.remove();
            }
        }
    }
}
