package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.animation.BlockLook;
import com.lealex.alchyx.animation.Effect;
import com.lealex.alchyx.animation.Keyframe;
import com.lealex.alchyx.animation.ResolvedAnimation;
import com.lealex.alchyx.animation.Target;
import com.lealex.alchyx.animation.Timing;
import com.lealex.alchyx.animation.Trigger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Plays a machine's animation file inside a miniature: the same file that animates the real machine in the world
 * (see ANIMATIONS.md), on a small copy that exists only as a picture. A nexus showing the machines it absorbed, a
 * farm's preview with nothing behind it: no code for the animation, only the file.
 *
 * Everything in the file can play: block moves (pistons...), block states (bottles filling...), floating items,
 * figures (entities, and whatever mods code: a book, an orb), and, when the miniature says
 * where it stands in the world ({@link Placement}), particles and sounds. Triggers work as on a real machine:
 * always / idle / working every "interval" ticks, start and finish once per operation (when the {@link State}
 * reports operations).
 *
 * How to use it, for one miniature:
 * <ol>
 *   <li>make it once from the animation, the miniature's blocks (by cell, counted from the core, already turned the
 *       way the miniature is) and that turn;</li>
 *   <li>leave {@link #changingCells()} out of the miniature's fixed mesh: those blocks change look while a move
 *       lasts (a piston's base while its head is out), so they are drawn here, each frame;</li>
 *   <li>each frame, {@link #prepare} what shows now, then {@link #submit} it in the pose where one unit is one block
 *       of the miniature and the origin is the lowest corner of its core cell.</li>
 * </ol>
 * The animation only advances while it is prepared: a miniature nobody draws stands still and makes no noise.
 */
public final class MiniatureAnimation {
    /** What the animation asks of the thing it plays for. Everything but working has a quiet default. */
    public interface State {
        boolean working();

        /** 0 to 1 through the current operation (for items that close in as it advances). */
        default float progress() {
            return 0;
        }

        /** Changes when an operation starts (any number: its start time, a counter): fires the "start" effects. */
        default long operationStart() {
            return 0;
        }

        /** Changes when an operation finishes (a count of finished operations): fires the "finish" effects. */
        default int operationCount() {
            return 0;
        }

        /** The item a display of the animation file shows ("input"...). */
        default ItemStack display(String name) {
            return ItemStack.EMPTY;
        }

        /** A named level ("corruption"...), for effects with "min_level". */
        default int level(String name) {
            return 0;
        }
    }

    /**
     * Which kinds of effects to play. A mod that draws some of them its own way leaves those out. Particles and
     * sounds also need a {@link Placement}.
     */
    public record Plays(boolean blocks, boolean items, boolean figures, boolean particles, boolean sounds) {
        /** Everything the file holds. */
        public static final Plays ALL = new Plays(true, true, true, true, true);
        /** What is drawn in the miniature, nothing let loose in the world: no particles, no sounds. */
        public static final Plays PICTURE = new Plays(true, true, true, false, false);
    }

    /**
     * Where the miniature stands in the world, for what leaves the picture: particles and sounds. {@code toWorld}
     * turns a point of the miniature (its own units, origin = lowest corner of the core cell) into a world position;
     * {@code scale} is the size of one miniature block in the world. Particles spawn at the mapped place with their
     * spread and speed shrunk by the scale (their own size is the particle's: a puff of smoke stays a puff), sounds
     * play there, quieter the smaller the miniature.
     */
    public record Placement(Function<Vec3, Vec3> toWorld, float scale) {}

    private sealed interface Entry permits ItemEntry, BlockEntry, FigureEntry {}

    private record ItemEntry(Vec3 pos, float yRot, float scale, ItemStackRenderState state) implements Entry {}

    private record BlockEntry(Vec3 pos, Vec3 rotation, float scale, MovingBlockRenderState state) implements Entry {}

    private record FigureEntry(Vec3 feet, FigureDrawer.Prepared figure) implements Entry {}

    /** What one frame draws. */
    public static final class Frame {
        private final List<Entry> entries = new ArrayList<>();
    }

    private record Pending(long at, Effect effect) {}

    private record Move(Effect.BlockMove effect, BlockPos cell, Direction direction, float start) {}

    private final ResolvedAnimation animation;
    private final Map<BlockPos, BlockState> looks;
    private final Map<String, BlockPos> anchors;
    private final Rotation turn;
    private final Plays plays;
    private final Map<Target, List<BlockPos>> selected = new HashMap<>();
    private final Set<BlockPos> changing = new HashSet<>();

    // What it remembers between ticks, like the engine does for a real machine
    private final int[] counters;
    private final List<Pending> pending = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private boolean seen;
    private long lastStart;
    private int lastCount;
    private long clock;          // its own tick count: only advances while it is prepared
    private long lastTick = Long.MIN_VALUE;

    /**
     * @param looks   the miniature's blocks by cell, counted from the core cell, turned the way the miniature is
     * @param anchors the pattern's anchors turned the same way, or null to take the animation's own and turn them
     * @param turn    how the miniature is turned from the pattern as written (offsets and headings follow)
     */
    public MiniatureAnimation(ResolvedAnimation animation, Map<BlockPos, BlockState> looks, @Nullable Map<String, BlockPos> anchors,
                              Rotation turn, Plays plays) {
        this.animation = animation;
        this.looks = looks;
        this.turn = turn;
        this.plays = plays;
        this.counters = new int[animation.effects().size()];
        if (anchors != null && !anchors.isEmpty()) {
            this.anchors = anchors;
        } else {
            Map<String, BlockPos> turned = new HashMap<>();
            animation.anchors().forEach((name, cell) -> turned.put(name, cell.rotate(turn)));
            this.anchors = turned;
        }
        if (plays.blocks) {
            for (Effect effect : animation.effects()) {
                if (!effect.timing().where().miniature()) continue;
                if (effect instanceof Effect.BlockMove move && !move.swap().isEmpty()) changing.addAll(cells(move.targets()));
                if (effect instanceof Effect.StateSteps steps) changing.addAll(cells(steps.targets()));
            }
        }
    }

    /**
     * The cells whose block changes look (while a move lasts, or step by step through an operation): drawn by this
     * player, to leave out of a fixed mesh.
     */
    public Set<BlockPos> changingCells() {
        return changing;
    }

    // ---- Where things are (miniature units, origin = lowest corner of the core cell) ----

    private List<BlockPos> cells(Target target) {
        if (target.block().isPresent()) {
            return selected.computeIfAbsent(target, t -> {
                List<BlockPos> found = new ArrayList<>();
                looks.forEach((cell, look) -> {
                    if (t.matches(look)) found.add(cell);
                });
                return found;
            });
        }
        String anchor = target.anchor().orElse("core");
        if (anchor.equals("core")) return List.of(BlockPos.ZERO);
        BlockPos cell = anchors.get(anchor);
        return cell == null ? List.of() : List.of(cell);
    }

    private List<Vec3> points(Target target) {
        Vec3 offset = AnimationEngine.rotate(target.offset(), turn);
        List<Vec3> points = new ArrayList<>();
        for (BlockPos cell : cells(target)) points.add(Vec3.atCenterOf(cell).add(offset));
        return points;
    }

    private Direction direction(BlockPos cell, BlockState look, String name) {
        Direction towardCore = AnimationEngine.towardCore(BlockPos.ZERO, cell);
        return switch (name) {
            case "facing" -> {
                Direction facing = AnimationEngine.facingOf(look);
                yield facing != null ? facing : towardCore;
            }
            case "toward_core" -> towardCore;
            case "outward" -> towardCore.getOpposite();
            default -> {
                Direction direction = Direction.byName(name);
                if (direction == null) yield Direction.UP;
                yield direction.getAxis().isHorizontal() ? turn.rotate(direction) : direction;
            }
        };
    }

    // ---- Ticking: triggers, block moves, particles, sounds ----

    /** One tick of the animation, as the engine does for a real machine. */
    private void tick(ClientLevel level, State state, @Nullable Placement placement) {
        clock++;
        boolean working = state.working();
        long opStart = state.operationStart();
        int opCount = state.operationCount();
        boolean started = seen && opStart != lastStart;
        boolean finished = seen && opCount != lastCount;
        seen = true;
        lastStart = opStart;
        lastCount = opCount;

        RandomSource random = level.getRandom();
        List<Effect> effects = animation.effects();
        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
            if (effect instanceof Effect.FloatingItem || effect instanceof Effect.Figure
                    || effect instanceof Effect.StateSteps) continue; // worked out every frame
            Timing timing = effect.timing();
            if (!timing.where().miniature()) continue; // only for the machine in the world
            boolean fire;
            if (timing.when().continuous()) {
                if (timing.when().holds(working)) {
                    fire = counters[i] % timing.interval() == 0;
                    counters[i]++;
                } else {
                    counters[i] = 0;
                    fire = false;
                }
            } else {
                fire = timing.when() == Trigger.START ? started : finished;
            }
            if (!fire || !timing.levelsMet(state::level)) continue; // "min_level"
            if (timing.chance() < 1.0F && random.nextFloat() >= timing.chance()) continue;
            if (timing.delay() > 0) pending.add(new Pending(clock + timing.delay(), effect));
            else fire(level, state, effect, placement);
        }
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending waiting = it.next();
            if (waiting.at() <= clock) {
                it.remove();
                fire(level, state, waiting.effect(), placement);
            }
        }
        moves.removeIf(move -> clock - move.start() > Keyframe.duration(move.effect().keyframes()));
    }

    private void fire(ClientLevel level, State state, Effect effect, @Nullable Placement placement) {
        switch (effect) {
            case Effect.BlockMove move when plays.blocks -> {
                for (BlockPos cell : cells(move.targets())) {
                    BlockState look = looks.get(cell);
                    if (look == null) continue;
                    moves.removeIf(old -> old.cell().equals(cell) && old.effect() == move);
                    moves.add(new Move(move, cell, direction(cell, look, move.direction()), clock));
                }
            }
            case Effect.Particle particle when plays.particles && placement != null -> particles(level, state, particle, placement);
            case Effect.Sound sound when plays.sounds && placement != null -> sound(level, sound, placement);
            default -> { }
        }
    }

    private void particles(ClientLevel level, State state, Effect.Particle p, Placement placement) {
        ParticleOptions options;
        if (p.item().isPresent()) {
            ItemStack stack = state.display(p.item().get());
            if (stack.isEmpty()) return;
            options = new ItemParticleOption(ParticleTypes.ITEM, new ItemStackTemplate(stack.getItem()));
        } else {
            options = p.particle().orElseThrow();
        }
        float scale = placement.scale();
        Vec3 to = placement.toWorld().apply(Vec3.atCenterOf(BlockPos.ZERO));
        if (p.to().isPresent()) {
            List<Vec3> targets = points(p.to().get());
            if (targets.isEmpty()) return;
            to = placement.toWorld().apply(targets.getFirst());
        }
        RandomSource r = level.getRandom();
        for (Vec3 base : points(p.at())) {
            for (int n = 0; n < p.count(); n++) {
                Vec3 start = placement.toWorld().apply(base.add((r.nextDouble() * 2 - 1) * p.spread().x,
                        (r.nextDouble() * 2 - 1) * p.spread().y, (r.nextDouble() * 2 - 1) * p.spread().z));
                switch (p.style()) {
                    case SPREAD -> level.addParticle(options, start.x, start.y, start.z,
                            r.nextGaussian() * p.speed() * scale, r.nextGaussian() * p.speed() * scale, r.nextGaussian() * p.speed() * scale);
                    case TOWARD -> {
                        // A particle slowed by friction f each tick covers v / (1 - f) in total: aim exactly at "to"
                        Vec3 velocity = to.subtract(start).scale(1 - p.drag());
                        level.addParticle(options, start.x, start.y, start.z, velocity.x, velocity.y, velocity.z);
                    }
                    case ATTRACT -> {
                        // Enchant / nautilus style: spawned at the target with the start as an offset, drawn inward.
                        // These end 1.2 blocks under the place given, whatever the miniature's size
                        Vec3 target = to.add(0, 1.2, 0);
                        Vec3 offset = start.subtract(target);
                        level.addParticle(options, target.x, target.y, target.z, offset.x, offset.y, offset.z);
                    }
                }
            }
        }
    }

    private void sound(ClientLevel level, Effect.Sound s, Placement placement) {
        SoundEvent event = SoundEvent.createVariableRangeEvent(s.sound());
        RandomSource r = level.getRandom();
        // Smaller means quieter, but never silent: a quarter-size machine plays at half volume
        float volume = s.volume() * (float) Math.sqrt(Math.min(1.0F, placement.scale()));
        for (Vec3 point : points(s.at())) {
            Vec3 pos = placement.toWorld().apply(point);
            float pitch = s.pitch() + (r.nextFloat() * 2 - 1) * s.pitchVariance();
            level.playLocalSound(pos.x, pos.y, pos.z, event, SoundSource.BLOCKS, volume, pitch, false);
        }
    }

    // ---- What shows now ----

    /** As {@link #prepare(ClientLevel, Object, State, BlockPos, float, float, Placement)}, with nothing let loose in the world. */
    public Frame prepare(ClientLevel level, Object owner, State state, BlockPos lightPos, float time, float partialTicks) {
        return prepare(level, owner, state, lightPos, time, partialTicks, null);
    }

    /**
     * Advances the animation to this instant and works out what it shows.
     *
     * @param owner     what the miniature belongs to (figures keep their stand-in entity per owner)
     * @param lightPos  a block of the world lit like the miniature
     * @param time      game time plus the partial tick (for spins and bobs)
     * @param placement where the miniature stands in the world, for particles and sounds; null for none
     */
    public Frame prepare(ClientLevel level, Object owner, State state, BlockPos lightPos, float time, float partialTicks,
                         @Nullable Placement placement) {
        // One tick of triggers per game tick, however many frames are drawn (none while the game is paused)
        long gameTick = level.getGameTime();
        if (gameTick != lastTick && !Minecraft.getInstance().isPaused()) {
            lastTick = gameTick;
            tick(level, state, placement);
        }
        float now = clock + partialTicks;

        Frame frame = new Frame();
        boolean working = state.working();
        // The look each changing cell shows right now: its own, or what an ongoing move swaps in
        Map<BlockPos, BlockState> shown = new HashMap<>();
        for (BlockPos cell : changing) shown.put(cell, looks.get(cell));
        if (plays.blocks) {
            for (Effect effect : animation.effects()) {
                if (!(effect instanceof Effect.StateSteps steps) || !steps.timing().where().miniature()) continue;
                if (!steps.holds(working) || !steps.timing().levelsMet(state::level)) continue;
                Map<String, String> values = steps.steps().get(steps.index(state.progress()));
                for (BlockPos cell : cells(steps.targets())) {
                    BlockState look = looks.get(cell);
                    if (look != null) shown.put(cell, BlockLook.apply(look, values));
                }
            }
        }

        for (Move move : moves) {
            BlockState look = looks.get(move.cell());
            if (look == null) continue;
            float elapsed = Math.max(0, now - move.start());
            Keyframe pose = Keyframe.at(move.effect().keyframes(), elapsed);
            BlockState drawn = move.effect().draw().map(BlockLook::build).orElse(look);
            if (move.effect().align()) drawn = AnimationEngine.withFacing(drawn, move.direction());
            drawn = BlockLook.apply(drawn, Keyframe.stateAt(move.effect().keyframes(), elapsed));
            Direction d = move.direction();
            Vec3 pos = Vec3.atLowerCornerOf(move.cell())
                    .add(d.getStepX() * pose.move(), d.getStepY() * pose.move(), d.getStepZ() * pose.move())
                    .add(AnimationEngine.rotate(pose.offset(), turn));
            frame.entries.add(new BlockEntry(pos, pose.rotation(), pose.scale(), moving(level, drawn, lightPos)));
            if (!move.effect().swap().isEmpty()) shown.put(move.cell(), BlockLook.apply(look, move.effect().swap()));
        }
        for (Map.Entry<BlockPos, BlockState> cell : shown.entrySet()) {
            if (cell.getValue() == null) continue;
            frame.entries.add(new BlockEntry(Vec3.atLowerCornerOf(cell.getKey()), Vec3.ZERO, 1, moving(level, cell.getValue(), lightPos)));
        }

        List<Effect> effects = animation.effects();
        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
            if (!effect.timing().levelsMet(state::level)) continue; // "min_level"
            if (!effect.timing().where().miniature()) continue;
            switch (effect) {
                case Effect.FloatingItem item when plays.items -> item(frame, level, item, i, state, working, time);
                case Effect.Figure figure when plays.figures -> figure(frame, level, owner, figure, i, state, working, lightPos, time, partialTicks);
                default -> { }
            }
        }
        return frame;
    }

    private void item(Frame frame, ClientLevel level, Effect.FloatingItem item, int index, State state, boolean working, float time) {
        Trigger when = item.timing().when();
        if (when.continuous() ? !when.holds(working) : !working) return;
        ItemStack stack = state.display(item.display());
        if (stack.isEmpty()) return;
        ItemStackRenderState render = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(render, stack, ItemDisplayContext.GROUND, level, null, index);
        float speed = working && item.workingSpeed().isPresent() ? item.workingSpeed().get() : item.speed();
        int copies = item.perItems() > 0
                ? Math.max(1, Math.min(item.copies(), (stack.getCount() + item.perItems() - 1) / item.perItems()))
                : item.copies();
        float bob = (float) Math.sin(time / 10.0) * item.bob();
        for (Vec3 base : points(item.at())) {
            for (int k = 0; k < copies; k++) {
                float angle = time * speed + item.phase() + k * 360.0F / copies;
                Vec3 pos = base.add(0, bob, 0);
                float yRot = angle;
                switch (item.motion()) {
                    case ORBIT, CONVERGE -> {
                        float radius = item.radius() * (item.motion() == Effect.ItemMotion.CONVERGE ? 1 - state.progress() : 1);
                        double radians = Math.toRadians(angle);
                        pos = pos.add(Math.cos(radians) * radius, 0, Math.sin(radians) * radius);
                        yRot = -angle;
                    }
                    case STILL -> yRot = 0;
                    case SPIN -> { }
                }
                frame.entries.add(new ItemEntry(pos, yRot, item.scale(), render));
            }
        }
    }

    private void figure(Frame frame, ClientLevel level, Object owner, Effect.Figure figure, int index, State state, boolean working,
                        BlockPos lightPos, float time, float partialTicks) {
        List<Vec3> at = points(figure.at());
        if (at.isEmpty()) return;
        Vec3 feet = at.getFirst().add(0, -0.5, 0); // bottom of the block
        float yaw = AnimationEngine.turnYaw(figure.yaw().orElse(0.0F), turn);
        if (figure.face().isPresent()) {
            List<Vec3> targets = points(figure.face().get());
            if (!targets.isEmpty()) {
                Vec3 to = targets.getFirst().subtract(feet);
                yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
            }
        }
        // Lit where the miniature stands; no head turning: a miniature isn't where its figures look from
        FigureDrawer.Prepared prepared = FigureDrawer.prepare(owner, index, level, figure, Vec3.atCenterOf(lightPos), yaw, working,
                state::display, time, partialTicks, null);
        if (prepared != null) frame.entries.add(new FigureEntry(feet, prepared));
    }

    private static MovingBlockRenderState moving(ClientLevel level, BlockState state, BlockPos lightPos) {
        MovingBlockRenderState moving = new MovingBlockRenderState();
        moving.randomSeedPos = lightPos;
        moving.blockPos = lightPos;
        moving.blockState = state;
        moving.biome = level.getBiome(lightPos);
        moving.cardinalLighting = level.cardinalLighting();
        moving.lightEngine = level.getLightEngine();
        return moving;
    }

    /** Forgets ongoing moves and waiting effects (a miniature that just came back into view starts clean). */
    public void reset() {
        moves.clear();
        pending.clear();
        Arrays.fill(counters, 0);
        seen = false;
    }

    // ---- Drawing ----

    /** Draws a frame in the current pose: one unit = one block of the miniature, origin = lowest corner of its core cell. */
    public static void submit(Frame frame, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        for (Entry entry : frame.entries) {
            poseStack.pushPose();
            switch (entry) {
                case ItemEntry item -> {
                    poseStack.translate(item.pos().x, item.pos().y, item.pos().z);
                    poseStack.mulPose(Axis.YP.rotationDegrees(item.yRot()));
                    poseStack.scale(item.scale(), item.scale(), item.scale());
                    item.state().submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                }
                case BlockEntry block -> {
                    poseStack.translate(block.pos().x + 0.5, block.pos().y + 0.5, block.pos().z + 0.5);
                    poseStack.mulPose(Axis.XP.rotationDegrees((float) block.rotation().x));
                    poseStack.mulPose(Axis.YP.rotationDegrees((float) block.rotation().y));
                    poseStack.mulPose(Axis.ZP.rotationDegrees((float) block.rotation().z));
                    poseStack.scale(block.scale(), block.scale(), block.scale());
                    poseStack.translate(-0.5, -0.5, -0.5);
                    collector.submitMovingBlock(poseStack, block.state());
                }
                case FigureEntry figure -> {
                    poseStack.translate(figure.feet().x, figure.feet().y, figure.feet().z);
                    FigureDrawer.submit(figure.figure(), poseStack, collector, camera);
                }
            }
            poseStack.popPose();
        }
    }
}
