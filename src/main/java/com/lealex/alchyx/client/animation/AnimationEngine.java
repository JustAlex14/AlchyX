package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.BlockLook;
import com.lealex.alchyx.animation.ClientAnimations;
import com.lealex.alchyx.animation.Effect;
import com.lealex.alchyx.animation.Keyframe;
import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.animation.ResolvedAnimation;
import com.lealex.alchyx.animation.Target;
import com.lealex.alchyx.animation.Timing;
import com.lealex.alchyx.animation.Trigger;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Plays the machine animation files on the client: every tick it checks each loaded, formed core against its
 * effects (firing particles, sounds and block moves), and every frame it draws floating items and moving blocks
 * through NeoForge's custom geometry events, so no machine needs its own renderer for them.
 */
@EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
public final class AnimationEngine {
    private AnimationEngine() {}

    private static final double PLAY_RANGE = 64.0;       // blocks: farther machines don't animate
    private static final int SELECTOR_REFRESH = 40;      // ticks between re-scans of block selectors
    private static final double ATTRACT_DROP = 1.2;      // glyph-like particles end this far below their target

    private static final ContextKey<List<Entry>> RENDER_KEY = new ContextKey<>(AlchyX.id("machine_animations"));
    private static final Map<MultiblockCoreBlockEntity, Runtime> RUNTIMES = new WeakHashMap<>();

    /** Per-core state between ticks. */
    private static final class Runtime {
        final ResolvedAnimation def;
        final int generation;
        boolean seen;
        long lastOpStart;
        int lastOpCount;
        final int[] counters;
        final int[] steps; // per "block_state" effect: the step its blocks show now, -1 for their own look
        final List<Pending> pending = new ArrayList<>();
        final List<Move> moves = new ArrayList<>();
        final Map<Target, List<BlockPos>> selectors = new HashMap<>();
        long selectorsTime = Long.MIN_VALUE / 2; // (MIN_VALUE itself overflows "now - selectorsTime")

        Runtime(ResolvedAnimation def, int generation) {
            this.def = def;
            this.generation = generation;
            this.counters = new int[def.effects().size()];
            this.steps = new int[def.effects().size()];
            Arrays.fill(steps, -1);
        }
    }

    private record Pending(long at, Effect effect) {}

    private record Move(Effect.BlockMove effect, BlockPos pos, Direction direction, long start) {}

    private sealed interface Entry permits ItemEntry, BlockEntry, FigureEntry {}

    private record ItemEntry(Vec3 pos, float yRot, float scale, ItemStackRenderState state) implements Entry {}

    private record BlockEntry(Vec3 pos, Vec3 rotation, float scale, MovingBlockRenderState state) implements Entry {}

    private record FigureEntry(Vec3 feet, FigureDrawer.Prepared figure) implements Entry {}

    // ---- Ticking: triggers, particles, sounds, block moves ----

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) return;
        long now = level.getGameTime();
        for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
            if (core.isRemoved() || core.getLevel() != level) {
                Runtime old = RUNTIMES.remove(core);
                if (old != null && core.getLevel() == level) clearMoves(level, old);
                if (core.getLevel() != level) LoadedCores.remove(core);
                continue;
            }
            Runtime runtime = runtime(level, core);
            if (runtime != null) tick(minecraft, level, core, runtime, now);
        }
    }

    /** The core's runtime, rebuilt when new definitions arrive; null if its machine has no animation file. */
    private static @Nullable Runtime runtime(ClientLevel level, MultiblockCoreBlockEntity core) {
        ResolvedAnimation def = ClientAnimations.get(core.patternId());
        Runtime runtime = RUNTIMES.get(core);
        if (runtime != null && (runtime.def != def || runtime.generation != ClientAnimations.generation())) {
            clearMoves(level, runtime);
            runtime = null;
            RUNTIMES.remove(core);
        }
        if (runtime == null && def != null) {
            runtime = new Runtime(def, ClientAnimations.generation());
            RUNTIMES.put(core, runtime);
        }
        return runtime;
    }

    private static void tick(Minecraft minecraft, ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, long now) {
        if (!core.isFormed()) {
            clearMoves(level, rt);
            rt.pending.clear();
            Arrays.fill(rt.counters, 0);
            Arrays.fill(rt.steps, -1);
            rt.seen = false;
            return;
        }
        boolean working = core.isAnimationWorking();
        long opStart = core.getAnimationOperationStart();
        int opCount = core.getAnimationOperationCount();
        boolean started = rt.seen && opStart != rt.lastOpStart;
        boolean finished = rt.seen && opCount != rt.lastOpCount;
        rt.seen = true;
        rt.lastOpStart = opStart;
        rt.lastOpCount = opCount;

        Player player = minecraft.player;
        boolean near = player != null && player.distanceToSqr(Vec3.atCenterOf(core.getBlockPos())) <= PLAY_RANGE * PLAY_RANGE;
        RandomSource random = level.getRandom();
        List<Effect> effects = rt.def.effects();
        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
            if (effect instanceof Effect.FloatingItem) continue; // drawn every frame, see onExtract
            Timing timing = effect.timing();
            if (!timing.where().machine()) continue; // only for miniatures of this machine
            if (effect instanceof Effect.StateSteps steps) {
                stateSteps(level, core, rt, i, steps, working, now);
                continue;
            }
            boolean fire;
            if (timing.when().continuous()) {
                if (timing.when().holds(working)) {
                    fire = rt.counters[i] % timing.interval() == 0;
                    rt.counters[i]++;
                } else {
                    rt.counters[i] = 0;
                    fire = false;
                }
            } else {
                fire = timing.when() == Trigger.START ? started : finished;
            }
            if (!fire || !near) continue;
            if (!timing.levelsMet(core::getAnimationLevel)) continue; // "min_level"
            if (timing.chance() < 1.0F && random.nextFloat() >= timing.chance()) continue;
            if (timing.delay() > 0) rt.pending.add(new Pending(now + timing.delay(), effect));
            else fire(level, core, rt, effect, now);
        }
        for (Iterator<Pending> it = rt.pending.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (pending.at() <= now) {
                it.remove();
                fire(level, core, rt, pending.effect(), now);
            }
        }
        rt.moves.removeIf(move -> {
            if (now - move.start() <= Keyframe.duration(move.effect().keyframes())) return false;
            if (level.getBlockEntity(move.pos()) instanceof ChamberShellBlockEntity shell) shell.setClientLook(null);
            return true;
        });
    }

    private static void fire(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Effect effect, long now) {
        Rotation rotation = MultiblockPattern.rotationFor(rt.def.front(), core.getBlockState());
        switch (effect) {
            case Effect.Particle particle -> particles(level, core, rt, rotation, particle);
            case Effect.Sound sound -> sound(level, core, rt, rotation, sound);
            case Effect.BlockMove move -> startMoves(level, core, rt, rotation, move, now);
            case Effect.StateSteps steps -> { } // followed every tick (stateSteps)
            case Effect.FloatingItem item -> { }
            case Effect.Figure figure -> { } // drawn every frame (onExtract)
        }
    }

    /**
     * A "block_state" effect on a machine in the world: its formed parts (shells) wear the step's look, like a
     * block move's "swap". Blocks that aren't shells (the core itself) draw themselves, so nothing is done to them.
     */
    private static void stateSteps(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, int index, Effect.StateSteps effect,
                                   boolean working, long now) {
        boolean holds = effect.holds(working) && effect.timing().levelsMet(core::getAnimationLevel);
        int step = holds ? effect.index(core.getAnimationProgress(now)) : -1;
        if (step == rt.steps[index]) return;
        rt.steps[index] = step;
        Rotation rotation = MultiblockPattern.rotationFor(rt.def.front(), core.getBlockState());
        for (BlockPos pos : blocks(level, core, rt, rotation, effect.targets())) {
            if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell) {
                shell.setClientLook(step < 0 ? null : BlockLook.apply(shell.getDisguise(), effect.steps().get(step)));
            }
        }
    }

    private static void particles(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation, Effect.Particle p) {
        ParticleOptions options;
        if (p.item().isPresent()) {
            ItemStack stack = core.getAnimationDisplay(p.item().get());
            if (stack.isEmpty()) return;
            options = new ItemParticleOption(ParticleTypes.ITEM, new ItemStackTemplate(stack.getItem()));
        } else {
            options = p.particle().orElseThrow();
        }
        Vec3 center = Vec3.atCenterOf(core.getBlockPos());
        Vec3 to = center;
        if (p.to().isPresent()) {
            List<Vec3> targets = positions(level, core, rt, rotation, p.to().get());
            if (targets.isEmpty()) return;
            to = targets.getFirst();
        }
        RandomSource r = level.getRandom();
        for (Vec3 base : positions(level, core, rt, rotation, p.at())) {
            for (int n = 0; n < p.count(); n++) {
                Vec3 start = base.add((r.nextDouble() * 2 - 1) * p.spread().x, (r.nextDouble() * 2 - 1) * p.spread().y,
                        (r.nextDouble() * 2 - 1) * p.spread().z);
                switch (p.style()) {
                    case SPREAD -> level.addParticle(options, start.x, start.y, start.z,
                            r.nextGaussian() * p.speed(), r.nextGaussian() * p.speed(), r.nextGaussian() * p.speed());
                    case TOWARD -> {
                        // A particle slowed by friction f each tick covers v / (1 - f) in total: aim exactly at "to"
                        Vec3 velocity = to.subtract(start).scale(1 - p.drag());
                        level.addParticle(options, start.x, start.y, start.z, velocity.x, velocity.y, velocity.z);
                    }
                    case ATTRACT -> {
                        // Enchant / nautilus style: spawned at the target with the start as an offset, drawn inward
                        Vec3 target = to.add(0, ATTRACT_DROP, 0);
                        Vec3 offset = start.subtract(target);
                        level.addParticle(options, target.x, target.y, target.z, offset.x, offset.y, offset.z);
                    }
                }
            }
        }
    }

    private static void sound(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation, Effect.Sound s) {
        SoundEvent event = SoundEvent.createVariableRangeEvent(s.sound());
        RandomSource r = level.getRandom();
        for (Vec3 pos : positions(level, core, rt, rotation, s.at())) {
            float pitch = s.pitch() + (r.nextFloat() * 2 - 1) * s.pitchVariance();
            level.playLocalSound(pos.x, pos.y, pos.z, event, SoundSource.BLOCKS, s.volume(), pitch, false);
        }
    }

    private static void startMoves(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation,
                                   Effect.BlockMove move, long now) {
        for (BlockPos pos : blocks(level, core, rt, rotation, move.targets())) {
            Direction direction = direction(level, core, rotation, pos, move.direction());
            rt.moves.removeIf(old -> old.pos().equals(pos) && old.effect() == move);
            rt.moves.add(new Move(move, pos, direction, now));
            if (!move.swap().isEmpty() && level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell) {
                shell.setClientLook(BlockLook.apply(shell.getDisguise(), move.swap()));
            }
        }
    }

    private static void clearMoves(ClientLevel level, Runtime rt) {
        for (Move move : rt.moves) {
            if (level.getBlockEntity(move.pos()) instanceof ChamberShellBlockEntity shell) shell.setClientLook(null);
        }
        rt.moves.clear();
    }

    // ---- Positions ----

    /** World points of a target: block centers plus the target's offset (turned with the machine). */
    private static List<Vec3> positions(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation, Target target) {
        Vec3 offset = rotate(target.offset(), rotation);
        List<Vec3> points = new ArrayList<>();
        for (BlockPos pos : blocks(level, core, rt, rotation, target)) points.add(Vec3.atCenterOf(pos).add(offset));
        return points;
    }

    /** The blocks a target names: the core, an anchor, or every structure block matching a block selector. */
    private static List<BlockPos> blocks(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation, Target target) {
        if (target.block().isPresent()) {
            long now = level.getGameTime();
            if (now - rt.selectorsTime >= SELECTOR_REFRESH) {
                rt.selectors.clear();
                rt.selectorsTime = now;
            }
            return rt.selectors.computeIfAbsent(target, t -> scan(level, core, rt, rotation, t));
        }
        String anchor = target.anchor().orElse("core");
        if (anchor.equals("core")) return List.of(core.getBlockPos());
        BlockPos relative = rt.def.anchors().get(anchor);
        if (relative == null) return List.of();
        return List.of(core.getBlockPos().offset(relative.rotate(rotation)));
    }

    private static List<BlockPos> scan(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation, Target target) {
        BlockPos a = rt.def.boxMin().rotate(rotation), b = rt.def.boxMax().rotate(rotation);
        BlockPos origin = core.getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                origin.offset(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())))) {
            if (target.matches(ChamberShellBlock.lookAt(level, pos))) found.add(pos.immutable());
        }
        return found;
    }

    /** A pattern-space vector turned like BlockPos.rotate. */
    static Vec3 rotate(Vec3 v, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new Vec3(-v.z, v.y, v.x);
            case CLOCKWISE_180 -> new Vec3(-v.x, v.y, -v.z);
            case COUNTERCLOCKWISE_90 -> new Vec3(v.z, v.y, -v.x);
            default -> v;
        };
    }

    private static Direction direction(ClientLevel level, MultiblockCoreBlockEntity core, Rotation rotation, BlockPos pos, String name) {
        Direction towardCore = towardCore(core.getBlockPos(), pos);
        return switch (name) {
            case "facing" -> {
                Direction facing = facingOf(ChamberShellBlock.lookAt(level, pos));
                yield facing != null ? facing : towardCore;
            }
            case "toward_core" -> towardCore;
            case "outward" -> towardCore.getOpposite();
            default -> {
                Direction direction = Direction.byName(name);
                if (direction == null) yield Direction.UP;
                yield direction.getAxis().isHorizontal() ? rotation.rotate(direction) : direction;
            }
        };
    }

    static Direction towardCore(BlockPos core, BlockPos pos) {
        int dx = core.getX() - pos.getX(), dy = core.getY() - pos.getY(), dz = core.getZ() - pos.getZ();
        if (dx == 0 && dz == 0) return dy >= 0 ? Direction.UP : Direction.DOWN;
        return Direction.getApproximateNearest(dx, 0, dz);
    }

    static @Nullable Direction facingOf(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        return property != null && state.getValue(property) instanceof Direction direction ? direction : null;
    }

    @SuppressWarnings("unchecked")
    static BlockState withFacing(BlockState state, Direction direction) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        if (property != null && property.getValueClass() == Direction.class
                && ((Property<Direction>) property).getPossibleValues().contains(direction)) {
            return state.setValue((Property<Direction>) property, direction);
        }
        return state;
    }

    // ---- Drawing: floating items and moving blocks ----

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        ClientLevel level = event.getLevel();
        float partial = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = level.getGameTime() + partial;
        Vec3 camera = event.getCamera().position();
        List<Entry> entries = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
            if (core.isRemoved() || core.getLevel() != level || !core.isFormed()) continue;
            Runtime rt = RUNTIMES.get(core);
            if (rt == null) continue;
            if (Vec3.atCenterOf(core.getBlockPos()).distanceToSqr(camera) > PLAY_RANGE * PLAY_RANGE) continue;
            Rotation rotation = MultiblockPattern.rotationFor(rt.def.front(), core.getBlockState());

            for (Move move : rt.moves) {
                Keyframe pose = Keyframe.at(move.effect().keyframes(), time - move.start());
                BlockState drawn = move.effect().draw().map(BlockLook::build)
                        .orElseGet(() -> ChamberShellBlock.lookAt(level, move.pos()));
                if (move.effect().align()) drawn = withFacing(drawn, move.direction());
                drawn = BlockLook.apply(drawn, Keyframe.stateAt(move.effect().keyframes(), time - move.start()));
                Direction d = move.direction();
                Vec3 pos = Vec3.atLowerCornerOf(move.pos())
                        .add(d.getStepX() * pose.move(), d.getStepY() * pose.move(), d.getStepZ() * pose.move())
                        .add(rotate(pose.offset(), rotation));
                BlockPos lightPos = BlockPos.containing(pos.add(0.5, 0.5, 0.5));
                MovingBlockRenderState state = new MovingBlockRenderState();
                state.randomSeedPos = move.pos();
                state.blockPos = lightPos;
                state.blockState = drawn;
                state.biome = level.getBiome(lightPos);
                state.cardinalLighting = level.cardinalLighting();
                state.lightEngine = level.getLightEngine();
                entries.add(new BlockEntry(pos, pose.rotation(), pose.scale(), state));
            }

            boolean working = core.isAnimationWorking();
            List<Effect> effects = rt.def.effects();
            for (int i = 0; i < effects.size(); i++) {
                if (!effects.get(i).timing().where().machine()) continue; // only for miniatures of this machine
                if (effects.get(i) instanceof Effect.Figure figure) {
                    List<Vec3> at = positions(level, core, rt, rotation, figure.at());
                    if (at.isEmpty() || !figure.timing().levelsMet(core::getAnimationLevel)) continue; // "min_level"
                    Vec3 feet = at.getFirst().add(0, -0.5, 0); // bottom of the block
                    float yaw = figureYaw(level, core, rt, rotation, figure, feet);
                    FigureDrawer.Prepared prepared = FigureDrawer.prepare(core, i, level, figure, feet, yaw, working,
                            core::getAnimationDisplay, time, partial, camera);
                    if (prepared != null) entries.add(new FigureEntry(feet, prepared));
                    continue;
                }
                if (!(effects.get(i) instanceof Effect.FloatingItem item)) continue;
                Trigger when = item.timing().when();
                if (when.continuous() ? !when.holds(working) : !working) continue;
                if (!item.timing().levelsMet(core::getAnimationLevel)) continue; // "min_level"
                ItemStack stack = core.getAnimationDisplay(item.display());
                if (stack.isEmpty()) continue;
                ItemStackRenderState state = new ItemStackRenderState();
                minecraft.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GROUND, level, null,
                        (int) core.getBlockPos().asLong() + i);
                float speed = working && item.workingSpeed().isPresent() ? item.workingSpeed().get() : item.speed();
                int copies = item.perItems() > 0
                        ? Math.max(1, Math.min(item.copies(), (stack.getCount() + item.perItems() - 1) / item.perItems()))
                        : item.copies();
                float progress = core.getAnimationProgress(time);
                float bob = (float) Math.sin(time / 10.0) * item.bob();
                for (Vec3 base : positions(level, core, rt, rotation, item.at())) {
                    for (int k = 0; k < copies; k++) {
                        float angle = time * speed + item.phase() + k * 360.0F / copies;
                        Vec3 pos = base.add(0, bob, 0);
                        float yRot = angle;
                        switch (item.motion()) {
                            case ORBIT, CONVERGE -> {
                                float radius = item.radius() * (item.motion() == Effect.ItemMotion.CONVERGE ? 1 - progress : 1);
                                double radians = Math.toRadians(angle);
                                pos = pos.add(Math.cos(radians) * radius, 0, Math.sin(radians) * radius);
                                yRot = -angle;
                            }
                            case STILL -> yRot = 0;
                            case SPIN -> { }
                        }
                        entries.add(new ItemEntry(pos, yRot, item.scale(), state));
                    }
                }
            }
        }
        event.getRenderState().setRenderData(RENDER_KEY, entries.isEmpty() ? null : entries);
    }

    /** A figure's heading: toward its "face" target, else its "yaw" in pattern coordinates turned with the machine. */
    private static float figureYaw(ClientLevel level, MultiblockCoreBlockEntity core, Runtime rt, Rotation rotation,
                                   Effect.Figure figure, Vec3 feet) {
        if (figure.face().isPresent()) {
            List<Vec3> targets = positions(level, core, rt, rotation, figure.face().get());
            if (!targets.isEmpty()) {
                Vec3 to = targets.getFirst().subtract(feet);
                return (float) Math.toDegrees(Math.atan2(-to.x, to.z));
            }
        }
        double radians = Math.toRadians(figure.yaw().orElse(0.0F));
        Vec3 heading = rotate(new Vec3(-Math.sin(radians), 0, Math.cos(radians)), rotation);
        return (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
    }

    /** For mods drawing a machine's figures elsewhere: a pattern-space heading turned like BlockPos.rotate. */
    public static float turnYaw(float patternYaw, Rotation rotation) {
        double radians = Math.toRadians(patternYaw);
        Vec3 heading = rotate(new Vec3(-Math.sin(radians), 0, Math.cos(radians)), rotation);
        return (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        List<Entry> entries = event.getLevelRenderState().getRenderData(RENDER_KEY);
        if (entries == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        for (Entry entry : entries) {
            poseStack.pushPose();
            switch (entry) {
                case ItemEntry item -> {
                    poseStack.translate(item.pos().x - camera.x, item.pos().y - camera.y, item.pos().z - camera.z);
                    poseStack.mulPose(Axis.YP.rotationDegrees(item.yRot()));
                    poseStack.scale(item.scale(), item.scale(), item.scale());
                    item.state().submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                }
                case BlockEntry block -> {
                    poseStack.translate(block.pos().x - camera.x, block.pos().y - camera.y, block.pos().z - camera.z);
                    poseStack.translate(0.5, 0.5, 0.5);
                    poseStack.mulPose(Axis.XP.rotationDegrees((float) block.rotation().x));
                    poseStack.mulPose(Axis.YP.rotationDegrees((float) block.rotation().y));
                    poseStack.mulPose(Axis.ZP.rotationDegrees((float) block.rotation().z));
                    poseStack.scale(block.scale(), block.scale(), block.scale());
                    poseStack.translate(-0.5, -0.5, -0.5);
                    collector.submitMovingBlock(poseStack, block.state());
                }
                case FigureEntry figure -> {
                    poseStack.translate(figure.feet().x - camera.x, figure.feet().y - camera.y, figure.feet().z - camera.z);
                    FigureDrawer.submit(figure.figure(), poseStack, collector, event.getLevelRenderState().cameraRenderState);
                }
            }
            poseStack.popPose();
        }
    }
}
