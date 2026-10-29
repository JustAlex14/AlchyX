package com.lealex.alchyx.block.entity;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.growth.GrowthPayload;
import com.lealex.alchyx.multiblock.CoreTracker;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import com.lealex.alchyx.multiblock.ShellParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A machine that grows its own tiers (moved from AlchyForbidden, where every multiblock grows this way): the player
 * builds tier 1 by hand, it forms as usual, and every later tier grows by itself once the core has been given what
 * the tier asks for ({@link #tierCosts}; {@link #extraPaid} for something more, a kill beside it...). One pattern per
 * tier ({@link #patternOf}), all in the same box.
 * <ul>
 * <li>GATHER ({@link #gatherTicks}): the machine gathers itself; its structure checks are held.</li>
 * <li>GROW: its own parts the new tier has no place for fly up into the source and go (lowest last), then every new
 *     part flies out of the source ({@link #growthSource}) down to its place, a few per tick, lowest first, each one
 *     resting on what is under it; pillars ({@link #pillar}) and platform cells ({@link #platform}) rise out of the
 *     ground instead, a whole column together; what {@link #growsInPlace} (creeping sculk) just appears; the core rises
 *     with its platform when a tier puts it higher ({@link #rise}), hidden while its look slides up
 *     ({@link #coreHidden}), and really moves once everything stands, with all it holds.</li>
 * <li>SETTLE: the burst; then the new tier stands. Parts are placed veiled and see-through while they fly (AlchyX's
 *     client draws the flights, client/growth/GrowthParts, and shows them as they land) and settled at the end.</li>
 * </ul>
 * A broken structure keeps the earned tier: tier 1 built again grows the rest back for nothing (a risen core sinks back
 * first). The core's item keeps the tier too. Between growths it only runs {@link #tickWorking}.
 * <p>
 * Everything themed is a hook with a plain default: sounds and particles ({@link #onGatherStart}, {@link #onPartEmerging},
 * {@link #onAwakened}, {@link #clientGrowing}...), the flights' particles ({@link #growthStyle}), what counts as its
 * own part or as ground it may eat ({@link #ownLook}, {@link #eats}), its messages ({@link #growthText}).
 */
public abstract class TieredCoreBlockEntity extends MultiblockCoreBlockEntity {
    protected static final int IDLE = 0, GATHER = 1, GROW = 2, SETTLE = 3;
    /** The first phase number a subclass may use for its own work (a rite...). */
    protected static final int OWN_PHASES = 4;
    private static final int SETTLE_TICKS = 30; // longer than a part's flight (GrowthPayload.FLY_TICKS)
    private static final double MESSAGE_RANGE = 32.0;

    /** A thing a tier asks for. */
    public record TierCost(Item item, int count) {}

    protected int tier = 1;        // the tier earned
    protected int standing = 1;    // the tier the structure stands at (its pattern); below tier while it grows back
    protected int phase = IDLE;
    protected long phaseStart = 0L;
    protected int goal = 1;        // the tier being grown
    private final Map<Identifier, Integer> offered = new HashMap<>(); // what was given toward the next tier

    // Not saved: the growth's order (worked out again when needed), what it clears, the last place reported in the way
    private @Nullable List<MultiblockPattern.Conversion> plan;
    private @Nullable List<BlockPos> clears;
    private @Nullable BlockPos blockedAt;
    private boolean moving = false; // the core is moving: its old block entity goes without breaking anything
    /** Parts set down only when their flight lands: real blocks, and shells that change their look. */
    private record Landing(BlockPos pos, BlockState look, long at, boolean real, boolean hidden, boolean restore) {}
    private final List<Landing> landing = new ArrayList<>();
    private boolean coreRose = false; // this growth's core has started rising with its platform
    private long riseUntil = 0L;      // every column risen so far stands at its height from then
    /** When each part brought this growth lands (what rests on it waits). */
    private final Map<BlockPos, Long> landsAt = new HashMap<>();
    private int coreRise = 1;      // how many blocks the core is rising this growth (synced)
    private int coreLift = 0;      // how far the core has gone up this growth (what comes later comes out of a source as high)
    private long risingSeen = -1;  // client: when its block was first seen hidden while rising

    protected TieredCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- What each machine says ----

    /** The pattern of a tier. */
    protected abstract Identifier patternOf(int tier);

    /** What growing to this tier costs in items. */
    protected abstract List<TierCost> tierCosts(int tier);

    /** Its highest tier. */
    public int maxTier() {
        return 3;
    }

    /** How many blocks above the floor the core stands at a tier (the patterns' core layers; most stay put). */
    protected int rise(int tier) {
        return 0;
    }

    /** A cell of a platform that rises out of the ground instead of flying in (with {@link #platformBase} for the floor under it). */
    protected boolean platform(BlockPos pos) {
        return false;
    }

    /** As platform(pos), knowing its look: by default every {@link #pillar} rises too, by its whole height. */
    protected boolean platform(BlockPos pos, BlockState look) {
        return platform(pos) || pillar(look);
    }

    /** True for a look that is a pillar's piece (it grows out of the ground with the rest of its column). */
    protected boolean pillar(BlockState look) {
        return false;
    }

    /** Blocks above the floor a cell rises from on its own though nothing rises under it (arches over pillars); 0: none. */
    protected int riseFromBase(BlockPos pos, BlockState look) {
        return 0;
    }

    /** The floor under a platform: changed where it lies, nothing flies in. */
    protected boolean platformBase(BlockPos pos) {
        return false;
    }

    /** True if it may grow now (an altar needs its eye...). */
    protected boolean canGrow() {
        return true;
    }

    /** Ticks it gathers itself before the parts come. */
    protected int gatherTicks() {
        return 60;
    }

    /** Ticks between steps of the growth, and parts per step. */
    protected int ticksPerStep() {
        return 2;
    }

    protected int blocksPerStep() {
        return 1;
    }

    /** Ground it may grow over and replace besides air and replaceable blocks (natural ground...). */
    protected boolean eats(BlockState state) {
        return false;
    }

    /** A shell look that is one of its own parts (a later tier may clear it). */
    protected boolean ownLook(BlockState look) {
        return false;
    }

    /** A real block that is one of its own parts (a later tier may clear it). */
    protected boolean ownRealPart(BlockState state) {
        return false;
    }

    /** A look that is not brought but appears where it stands (sculk creeping over the stone). */
    protected boolean growsInPlace(BlockState look) {
        return false;
    }

    /** Where the parts fly out of and into: two blocks over the core by default, raised with the core as it goes up. */
    protected BlockPos growthSource() {
        return worldPosition.above(2 + coreLift);
    }

    /** The particles of its flights (client/growth/GrowthParts.registerStyle). */
    protected Identifier growthStyle() {
        return AlchyX.id("default");
    }

    /**
     * The core's own block hidden (true) or shown (false) while it rises, or null when it cannot hide (then it does not
     * slide up: it moves at the end). A block with a boolean property and an empty model when set does it.
     */
    protected @Nullable BlockState coreHidden(BlockState state, boolean hidden) {
        return null;
    }

    /** What the core leaves where it stood when it rises, if the next tier names nothing there (null: air). */
    protected @Nullable BlockState pedestalFallback() {
        return null;
    }

    /** Work done every tick while it is formed and not growing. */
    protected void tickWorking() {}

    /** Work of a subclass's own phase (OWN_PHASES and up). */
    protected void tickOwnPhase(int phase, long elapsed) {}

    /** As a growth begins (its path, its contents may be about to change). */
    protected void onGrowthStart() {}

    /** Just before the core sinks back when its structure breaks (drop what lived on the grown parts...). */
    protected void onStructureBroken() {}

    // ---- Effects (themed by the machine; plain by default) ----

    protected void onOffered(Player player, Item item) {
        if (level != null) playSound(worldPosition, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f, 0.9f + level.getRandom().nextFloat() * 0.2f);
    }

    protected void onGatherStart() {
        playSound(worldPosition, SoundEvents.BEACON_POWER_SELECT, 1.0f, 0.8f);
    }

    protected void gatherTick(long elapsed) {}

    /** As the parts start to come (a loud growth may draw things...). */
    protected void onGrowStart() {}

    protected void onPartCleared(BlockPos pos, BlockState gone) {
        playSound(pos, SoundEvents.AMETHYST_BLOCK_BREAK, 0.7f, 0.6f);
    }

    protected void onPartEmerging(BlockPos pos, BlockState look) {
        if (level != null) playSound(pos, SoundEvents.AMETHYST_BLOCK_PLACE, 0.8f, 0.8f + level.getRandom().nextFloat() * 0.4f);
    }

    protected void onPartGrewInPlace(BlockPos pos, BlockState look) {}

    protected void onColumnRising(BlockPos start) {
        playSound(start, SoundEvents.PISTON_EXTEND, 0.7f, 0.45f);
    }

    /** The core starts rising `up` blocks (a source drawn over it may go up with it). */
    protected void onCoreRising(int up) {}

    /** Every part stands, just before the tier changes and the core moves (close a source drawn over it...). */
    protected void onGrowthEnding() {}

    /** The new tier stands: the burst. */
    protected void onAwakened() {
        playSound(worldPosition, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.0f);
    }

    /** A growth cut short by a broken structure. */
    protected void onGrowthAborted() {}

    /** Client particles while it gathers or grows (cy: over the core, raised as it rises). */
    protected void clientGrowing(int phase, RandomSource random, long elapsed, double cx, double cy, double cz) {}

    /** Client effects outside the growth. */
    protected void clientWorking(RandomSource random, long elapsed, double cx, double cy, double cz) {}

    /** Its messages: blocked (block name, x, y, z), gathers, grown (tier), wants (tier, list). */
    protected Component growthText(String key, Object... args) {
        return Component.translatable("growth.alchyx." + key, args);
    }

    // ---- State ----

    @Override
    public Identifier patternId() {
        return patternOf(standing);
    }

    /** The pattern of a tier (to show the next one). */
    public Identifier patternIdFor(int tier) {
        return patternOf(tier);
    }

    public int tier() {
        return tier;
    }

    public int standing() {
        return standing;
    }

    /** How many blocks the core is rising (while its block is hidden): things drawn on it rise as far. */
    public int coreRise() {
        return coreRise;
    }

    /** How far the core has gone up this growth. */
    protected int coreLift() {
        return coreLift;
    }

    /** True while it gathers, grows or settles, or works in a phase of its own. */
    public boolean isGrowing() {
        return phase != IDLE;
    }

    /** The tier it would grow next (0 = none). */
    public int nextTier() {
        if (!isFormed() || isGrowing()) return 0;
        if (standing < tier) return tier;
        return tier < maxTier() ? tier + 1 : 0;
    }

    /** Where the core stands at a tier (it rises or sinks by the patterns' core layers, over the same floor). */
    public BlockPos coreFor(int tier) {
        return worldPosition.above(rise(tier) - rise(standing));
    }

    // Grown parts are hidden while they fly: no puff where they will land
    @Override
    public boolean veilPuffs() {
        return false;
    }

    // While it gathers and grows it reshapes itself: AlchyX holds its structure checks until the new tier stands
    @Override
    protected boolean holdsStructure() {
        return phase == GATHER || phase == GROW;
    }

    protected void setPhase(int next) {
        phase = next;
        phaseStart = level == null ? 0L : level.getGameTime();
        setChanged();
        syncToClients();
    }

    // ---- Giving ----

    public int givenOf(Item item) {
        return offered.getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0);
    }

    /** True if this stack is something it asks for now (both sides). */
    public boolean wants(ItemStack stack) {
        if (stack.isEmpty() || !isFormed() || !canGrow() || isGrowing() || tier >= maxTier() || standing < tier) return false;
        for (TierCost cost : tierCosts(tier + 1)) {
            if (stack.is(cost.item()) && givenOf(cost.item()) < cost.count()) return true;
        }
        return false;
    }

    /** Takes what it still needs of this stack; once everything is given, the next tier grows. */
    public void offer(Player player, ItemStack stack) {
        if (level == null || !wants(stack)) return;
        for (TierCost cost : tierCosts(tier + 1)) {
            if (!stack.is(cost.item())) continue;
            int take = Math.min(cost.count() - givenOf(cost.item()), stack.getCount());
            if (take <= 0) continue;
            offered.merge(BuiltInRegistries.ITEM.getKey(cost.item()), take, Integer::sum);
            if (!player.getAbilities().instabuild) stack.shrink(take);
            setChanged();
            syncToClients();
            onOffered(player, cost.item());
            break;
        }
        if (paid()) startGrowing(tier + 1);
        else if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(wantsLine(), true);
    }

    protected boolean paid() {
        for (TierCost cost : tierCosts(tier + 1)) {
            if (givenOf(cost.item()) < cost.count()) return false;
        }
        return extraPaid(tier + 1);
    }

    /** Something a tier asks for besides items (a kill beside it...). Default none. */
    protected boolean extraPaid(int tier) {
        return true;
    }

    /** That something, said in the wants line (null = nothing more). */
    protected @Nullable Component extraWanted(int tier) {
        return null;
    }

    /** Set by /alchyx showcase build (not saved): the machine was raised for a trailer, so it grows quietly. */
    protected boolean showcase;

    /**
     * For trailers and showcase maps: the machine has earned its top tier, so once its first tier forms the rift grows
     * the rest. {@link #prepareShowcase} gives it what else it needs to grow (a key item...).
     */
    public void showcaseGrown() {
        tier = maxTier();
        showcase = true;
        prepareShowcase();
        setChanged();
        if (isFormed()) tryGrowing();
    }

    /** What a showcased machine needs besides its tier (default nothing). */
    protected void prepareShowcase() {}

    public boolean showcase() {
        return showcase;
    }

    /** Tries the growth it is owed: back to its earned tier, or the next one once paid for. */
    protected void tryGrowing() {
        if (!isFormed() || isGrowing() || !canGrow()) return;
        if (standing < tier) startGrowing(tier);
        else if (tier < maxTier() && paid()) startGrowing(tier + 1);
    }

    /** What the next tier still asks for. */
    public Component wantsLine() {
        MutableComponent list = Component.empty();
        boolean first = true;
        for (TierCost cost : tierCosts(tier + 1)) {
            int missing = cost.count() - givenOf(cost.item());
            if (missing <= 0) continue;
            if (!first) list.append(", ");
            list.append(missing + " ").append(cost.item().getName(new ItemStack(cost.item())));
            first = false;
        }
        Component extra = extraPaid(tier + 1) ? null : extraWanted(tier + 1);
        if (extra != null) {
            if (!first) list.append(", ");
            list.append(extra);
        }
        return growthText("wants", tier, list);
    }

    // ---- Growing ----

    /** Starts growing toward a tier, if nothing stands in the way of its blocks (otherwise says where). */
    protected void startGrowing(int toTier) {
        if (level == null || isGrowing() || !canGrow()) return;
        MultiblockPattern target = MultiblockPatterns.get(patternOf(toTier));
        if (target == null) {
            AlchyX.LOGGER.warn("no pattern {} loaded: {} cannot grow", patternOf(toTier), machineName());
            return;
        }
        List<MultiblockPattern.Conversion> order = order(target, coreFor(toTier));
        clears = clearsFor(target, coreFor(toTier));
        List<BlockPos> needed = new ArrayList<>();
        for (MultiblockPattern.Conversion conversion : order) {
            if (!grown(level, conversion)) needed.add(conversion.pos());
        }
        int up = rise(toTier) - rise(standing);
        for (int i = 1; i <= up; i++) needed.add(worldPosition.above(i)); // where the core rises to
        for (BlockPos pos : needed) {
            if (pos.equals(worldPosition)) continue; // the core's own cell: the move fills it
            BlockState current = level.getBlockState(pos);
            if (!roomFor(current) && !ShellParts.isShell(current) && !current.is(getBlockState().getBlock())) {
                if (!pos.equals(blockedAt)) {
                    blockedAt = pos.immutable();
                    say(growthText("blocked", current.getBlock().getName(), pos.getX(), pos.getY(), pos.getZ()));
                }
                return;
            }
        }
        blockedAt = null;
        if (toTier > tier) { // paid for: the gifts are spent
            tier = toTier;
            offered.clear();
        }
        goal = toTier;
        plan = order;
        coreRose = false;
        riseUntil = 0L;
        coreLift = 0;
        landsAt.clear();
        onGrowthStart();
        setPhase(GATHER);
        setAnimationWorking(true);
        onGatherStart();
        say(growthText("gathers"));
    }

    /** Where it may put a block: air, plants and snow, water, and what it {@link #eats}. */
    protected boolean roomFor(BlockState state) {
        return state.isAir() || state.canBeReplaced() || eats(state);
    }

    /**
     * True if this cell already holds what the tier puts there: the real block, or a shell wearing that very look (a
     * shell with another look, like a step that becomes a solid block at the next tier, is grown again). Both sides.
     */
    public static boolean grown(Level level, MultiblockPattern.Conversion conversion) {
        BlockState current = level.getBlockState(conversion.pos());
        if (conversion.result().real() || !ShellParts.isShell(current)) return conversion.isDone(current);
        BlockState disguise = ChamberShellBlock.disguiseAt(level, conversion.pos());
        return disguise == null || disguise == conversion.disguise(Blocks.AIR.defaultBlockState());
    }

    /** The cells of a tier, lowest first, then nearest; the column over the core comes last (the middle rises at the end). */
    private List<MultiblockPattern.Conversion> order(MultiblockPattern target, BlockPos core) {
        List<MultiblockPattern.Conversion> cells = new ArrayList<>(target.conversions(core, rotation()));
        cells.sort(Comparator.<MultiblockPattern.Conversion>comparingInt(c -> isCenter(c.pos()) ? 1 : 0)
                .thenComparingInt(c -> c.pos().getY())
                .thenComparingDouble(c -> c.pos().distSqr(worldPosition)));
        return cells;
    }

    /** Its own parts standing where the new tier asks for air (a tier can reshape the one below), highest first. */
    private List<BlockPos> clearsFor(MultiblockPattern target, BlockPos core) {
        List<BlockPos> found = new ArrayList<>();
        if (level == null) return found;
        for (MultiblockPattern.PreviewCell cell : target.previewCells()) {
            if (cell.display() != null) continue; // only the cells that must be air
            BlockPos pos = core.offset(cell.offset());
            if (ownPart(level, pos)) found.add(pos.immutable());
        }
        found.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
        return found;
    }

    /** One of its own parts: a shell wearing one of its looks, or one of its real blocks. Never anything else. */
    protected boolean ownPart(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (ownRealPart(state)) return true;
        if (!ShellParts.isShell(state)) return false;
        BlockState look = ChamberShellBlock.disguiseAt(level, pos);
        return look != null && ownLook(look);
    }

    private boolean isCenter(BlockPos pos) {
        return pos.getX() == worldPosition.getX() && pos.getZ() == worldPosition.getZ();
    }

    @Override
    protected void tickFormed() {
        if (level == null) return;
        if (phase == IDLE) {
            tickWorking();
            return;
        }
        long elapsed = level.getGameTime() - phaseStart;
        switch (phase) {
            case GATHER -> {
                gatherTick(elapsed);
                if (elapsed >= gatherTicks()) {
                    setPhase(GROW);
                    onGrowStart();
                }
            }
            case GROW -> {
                land();
                if (elapsed % Math.max(1, ticksPerStep()) != 0) return;
                for (int i = 0; i < blocksPerStep(); i++) {
                    if (!growOne()) break;
                }
            }
            case SETTLE -> {
                if (elapsed >= SETTLE_TICKS) finish();
            }
            default -> tickOwnPhase(phase, elapsed);
        }
    }

    private void part(int kind, BlockPos source, BlockPos pos, BlockState look, int distance) {
        if (level instanceof ServerLevel server) GrowthPayload.send(server, kind, growthStyle(), source, pos, look, distance);
    }

    /** Grows (or clears) the next block of the tier. False when there is none left, or the next one is in the way. */
    private boolean growOne() {
        if (level == null) return false;
        if (plan == null) {
            MultiblockPattern target = MultiblockPatterns.get(patternOf(goal));
            if (target == null) {
                setPhase(IDLE);
                setAnimationWorking(false);
                return false;
            }
            plan = order(target, coreFor(goal));
            clears = clearsFor(target, coreFor(goal));
        }
        // First what the new tier has no place for: it flies up into the source
        if (clears != null && !clears.isEmpty()) {
            BlockPos pos = clears.removeFirst();
            if (ownPart(level, pos)) {
                BlockState gone = ShellParts.isShell(level.getBlockState(pos)) ? ChamberShellBlock.disguiseAt(level, pos) : level.getBlockState(pos);
                if (gone != null) part(GrowthPayload.SINK, growthSource(), pos, gone, 0);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                onPartCleared(pos, gone == null ? Blocks.AIR.defaultBlockState() : gone);
                return true;
            }
        }
        for (MultiblockPattern.Conversion conversion : plan) {
            BlockPos pos = conversion.pos();
            if (pos.equals(worldPosition)) continue; // the core's own cell: the move fills it
            BlockState current = level.getBlockState(pos);
            if (grown(level, conversion) || landingAt(pos)) continue;
            if (!roomFor(current) && !ShellParts.isShell(current)) { // someone put something there meanwhile: wait
                if (!pos.equals(blockedAt)) {
                    blockedAt = pos.immutable();
                    say(growthText("blocked", current.getBlock().getName(), pos.getX(), pos.getY(), pos.getZ()));
                }
                return false;
            }
            blockedAt = null;
            BlockState look = conversion.disguise(Blocks.AIR.defaultBlockState());
            boolean real = conversion.result().real(), hidden = conversion.result().hidden(), restore = conversion.result().restore();
            if (!real && platformBase(pos)) { // the floor under a platform: changed where it lies
                ChamberShellBlock.place(level, pos, look, hidden, restore);
                return true;
            }
            if (!real && platform(pos, look)) {
                // in its turn: the column rises out of the ground, once the block it stands on has landed
                Long under = landsAt.get(pos.below());
                if (under != null && level.getGameTime() < under) return false;
                riseColumn(pos);
                return true;
            }
            BlockState there = ShellParts.isShell(current) ? ChamberShellBlock.disguiseAt(level, pos) : current;
            if ((there != null && !there.isAir() && there.getBlock() == look.getBlock()) || growsInPlace(look)) {
                // already standing there (an older layout, turned another way), or a look that appears where it is:
                // changed in place, nothing flies in
                if (real) level.setBlock(pos, look, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                else ChamberShellBlock.place(level, pos, look, hidden, restore);
                if (growsInPlace(look)) onPartGrewInPlace(pos, look);
                return true;
            }
            Long under = landsAt.get(pos.below()); // what it rests on (still flying, still rising): first
            if (under != null && level.getGameTime() < under) return false;
            landsAt.put(pos.immutable(), level.getGameTime() + GrowthPayload.FLY_TICKS);
            if (real || ShellParts.isShell(current)) { // set down as it lands
                landing.add(new Landing(pos.immutable(), look, level.getGameTime() + GrowthPayload.FLY_TICKS, real, hidden, restore));
            } else {
                // veiled: hidden on the clients until it has flown in (client/growth/GrowthParts), and see-through until
                // then so the blocks around it show no holes (finish() settles it)
                ChamberShellBlock.place(level, pos, look, hidden, restore, hidden ? 0L : worldPosition.asLong(), true);
            }
            part(GrowthPayload.EMERGE, growthSource(), pos, look, 0);
            onPartEmerging(pos, look);
            return true;
        }
        if (!landing.isEmpty()) return false; // the last parts are still flying
        if (!coreRose && riseCore()) return true; // a core rising with no platform cell to rise with: now
        if (level.getGameTime() < riseUntil) return false; // the columns are still rising (the core moves as they stop)
        // Everything stands: the core moves to its new place if the tier puts it elsewhere, the structure is the new
        // tier at once, then the burst
        BlockPos to = coreFor(goal);
        BlockState pedestal = pedestalLook();
        onGrowthEnding();
        standing = goal;
        plan = null;
        clears = null;
        phase = SETTLE;
        phaseStart = level.getGameTime();
        TieredCoreBlockEntity core = to.equals(worldPosition) ? this : moveCore(to, pedestal);
        if (core == null) return false;
        core.risen();
        return false;
    }

    /** The look the core leaves where it stood when it moves: the tier's cell there, or the fallback. */
    private @Nullable BlockState pedestalLook() {
        MultiblockPattern target = MultiblockPatterns.get(patternOf(goal));
        if (target != null) {
            for (MultiblockPattern.Conversion conversion : target.conversions(coreFor(goal), rotation())) {
                if (conversion.pos().equals(worldPosition)) return conversion.disguise(Blocks.AIR.defaultBlockState());
            }
        }
        return pedestalFallback();
    }

    /** At its new tier (and place): known to AlchyX under its new pattern, checked, the burst. */
    private void risen() {
        if (level == null) return;
        CoreTracker.add(level, worldPosition, patternId());
        requestStructureCheck();
        setChanged();
        syncToClients();
        onAwakened();
    }

    /**
     * Moves the core block (and everything it holds: tier, gifts, contents, AlchyX's state) to another place, leaving
     * {@code leave} as a shell where it stood, or air when null. Returns the block entity at the new place.
     */
    private @Nullable TieredCoreBlockEntity moveCore(BlockPos to, @Nullable BlockState leave) {
        if (level == null) return null;
        Level world = level;
        BlockPos from = worldPosition;
        net.minecraft.nbt.CompoundTag data = saveCustomOnly(world.registryAccess());
        BlockState state = getBlockState();
        BlockState shown = coreHidden(state, false);
        if (shown != null) state = shown; // shown again where it arrives
        world.setBlock(to, state, Block.UPDATE_ALL);
        if (!(world.getBlockEntity(to) instanceof TieredCoreBlockEntity moved)) return null;
        moved.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, world.registryAccess(), data));
        moved.plan = null;
        moving = true;
        if (leave == null || leave.isAir()) world.setBlock(from, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        else ChamberShellBlock.place(world, from, leave, false, false, 0L);
        CoreTracker.add(world, to, moved.patternId());
        return moved;
    }

    /** True while the old block entity of a moving core goes (it must not drop or break anything). */
    protected boolean isMoving() {
        return moving;
    }

    /**
     * One column rises out of the ground: the cell growOne reached and the rising cells above and below it. Each is
     * placed now, veiled (hidden on the clients while its look slides up) and see-through until finish() settles it.
     * A pillar comes up by its whole height; a platform as far as the tiers it climbs this growth. The first platform
     * column takes the core up with it.
     */
    private void riseColumn(BlockPos start) {
        if (level == null || plan == null) return;
        Map<BlockPos, MultiblockPattern.Conversion> rising = new HashMap<>();
        for (MultiblockPattern.Conversion conversion : plan) {
            BlockPos pos = conversion.pos();
            BlockState look = conversion.disguise(Blocks.AIR.defaultBlockState());
            if (!pos.equals(worldPosition) && !conversion.result().real() && platform(pos, look) && !grown(level, conversion)) rising.put(pos, conversion);
        }
        List<BlockPos> column = new ArrayList<>();
        BlockPos bottom = start;
        while (rising.containsKey(bottom.below())) bottom = bottom.below();
        for (BlockPos pos = bottom; rising.containsKey(pos); pos = pos.above()) column.add(pos);
        int run = column.size(), longest = 1;
        boolean platformCell = false;
        // a column rising from the base (an arch) comes up from the floor by its lowest cell's height, all together
        int fromBase = riseFromBase(column.getFirst(), rising.get(column.getFirst()).disguise(Blocks.AIR.defaultBlockState()));
        for (BlockPos pos : column) {
            MultiblockPattern.Conversion conversion = rising.get(pos);
            BlockState current = level.getBlockState(pos);
            if (!roomFor(current) && !ShellParts.isShell(current)) continue; // something in the way: growOne reports it
            BlockState look = conversion.disguise(Blocks.AIR.defaultBlockState());
            ChamberShellBlock.place(level, pos, look, conversion.result().hidden(), conversion.result().restore(),
                    conversion.result().hidden() ? 0L : worldPosition.asLong(), true);
            boolean pillarPiece = pillar(look);
            platformCell |= !pillarPiece && fromBase <= 0;
            int distance = fromBase > 0 ? fromBase : pillarPiece ? run : Math.min(run, Math.max(1, goal - standing));
            longest = Math.max(longest, distance);
            part(GrowthPayload.RISE, pos, pos, look, distance);
            landsAt.put(pos.immutable(), level.getGameTime() + GrowthPayload.riseTicks(distance)); // what rests on it waits
        }
        riseUntil = Math.max(riseUntil, level.getGameTime() + GrowthPayload.riseTicks(longest));
        if (platformCell && !coreRose) riseCore();
        onColumnRising(start);
    }

    /**
     * The core rises with its platform: hidden where it stands, its look slid up to where it will be, the pedestal it
     * leaves rising under it; it really moves when every column has stopped. False when it does not rise this growth.
     */
    private boolean riseCore() {
        coreRose = true;
        if (level == null) return false;
        BlockPos to = coreFor(goal);
        BlockState core = getBlockState();
        int up = to.getY() - worldPosition.getY();
        BlockState hiddenCore = coreHidden(core, true), shownCore = coreHidden(core, false);
        if (up < 1 || to.getX() != worldPosition.getX() || to.getZ() != worldPosition.getZ() || hiddenCore == null || shownCore == null
                || !(level instanceof ServerLevel)) return false;
        coreRise = up;
        level.setBlock(worldPosition, hiddenCore, Block.UPDATE_CLIENTS);
        syncToClients();
        part(GrowthPayload.RISE, to, to, shownCore, up);
        BlockState pedestal = pedestalLook();
        if (pedestal != null && !pedestal.isAir()) part(GrowthPayload.RISE, worldPosition, worldPosition, pedestal, up);
        onCoreRising(up);
        coreLift = up; // what comes later comes out of a source as high
        riseUntil = Math.max(riseUntil, level.getGameTime() + GrowthPayload.riseTicks(up));
        return true;
    }

    /** True while the core's block is hidden, rising. */
    protected boolean coreHiddenNow() {
        BlockState state = getBlockState(), hidden = coreHidden(state, true);
        return hidden != null && state.equals(hidden) && !state.equals(coreHidden(state, false));
    }

    /** Client: how far the core has visibly risen while it is hidden rising (0 otherwise). */
    protected double visualRise() {
        if (level == null || !coreHiddenNow()) {
            risingSeen = -1;
            return 0;
        }
        long now = level.getGameTime();
        if (risingSeen < 0) risingSeen = now;
        int blocks = Math.max(1, coreRise);
        float t = Mth.clamp((now - risingSeen) / (float) GrowthPayload.riseTicks(blocks), 0, 1);
        return blocks * t * t * (3 - 2 * t);
    }

    /** Sets down the parts whose flight has landed (a cell filled meanwhile waits for growOne). */
    private void land() {
        if (level == null || landing.isEmpty()) return;
        long now = level.getGameTime();
        landing.removeIf(part -> {
            if (part.at() > now) return false;
            BlockState there = level.getBlockState(part.pos());
            if (!part.real() && (ShellParts.isShell(there) || roomFor(there))) {
                ChamberShellBlock.place(level, part.pos(), part.look(), part.hidden(), part.restore());
            } else if (part.real() && roomFor(there)) {
                level.setBlock(part.pos(), part.look(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            return true;
        });
    }

    private boolean landingAt(BlockPos pos) {
        for (Landing part : landing) {
            if (part.pos().equals(pos)) return true;
        }
        return false;
    }

    /** The burst is over: it rests at its new tier (every part has landed: they become opaque again). */
    private void finish() {
        if (level == null) return;
        MultiblockPattern pattern = pattern();
        if (pattern != null) {
            for (MultiblockPattern.Conversion conversion : pattern.conversions(worldPosition, rotation())) {
                ChamberShellBlock.settle(level, conversion.pos());
            }
        }
        setPhase(IDLE);
        setAnimationWorking(false);
        say(growthText("grown", standing));
    }

    @Override
    protected void onStructureChanged(boolean nowFormed) {
        if (level == null) return;
        if (!nowFormed) {
            // The parts are gone (AlchyX destroys every part in the box). The tier is kept: rebuilt by hand, the first
            // tier grows the rest back. A risen core sinks back to its first tier's place.
            boolean changed = standing != 1 || phase != IDLE;
            int sink = rise(standing) - rise(1);
            if (phase == GATHER || phase == GROW) onGrowthAborted();
            onStructureBroken();
            standing = 1;
            plan = null;
            landing.clear();
            riseUntil = 0L;
            if (coreHiddenNow()) level.setBlock(worldPosition, coreHidden(getBlockState(), false), Block.UPDATE_CLIENTS);
            phase = IDLE;
            setAnimationWorking(false);
            if (sink > 0 && free(worldPosition.below(sink), sink)) {
                TieredCoreBlockEntity moved = moveCore(worldPosition.below(sink), null);
                if (moved != null) {
                    moved.setChanged();
                    moved.syncToClients();
                }
                return;
            }
            if (changed) {
                setChanged();
                syncToClients();
                CoreTracker.add(level, worldPosition, patternId());
            }
        } else if (tier > standing) {
            startGrowing(tier);
        }
    }

    /** True if the cells from {@code bottom} up, {@code count} of them, are free for the core to sink through. */
    private boolean free(BlockPos bottom, int count) {
        if (level == null) return false;
        for (int i = 0; i < count; i++) {
            if (!roomFor(level.getBlockState(bottom.above(i)))) return false;
        }
        return true;
    }

    // ---- Client ----

    @Override
    public void clientTick() {
        if (level == null) return;
        RandomSource random = level.getRandom();
        long elapsed = level.getGameTime() - phaseStart;
        double cx = worldPosition.getX() + 0.5, cy = worldPosition.getY() + 0.9 + visualRise(), cz = worldPosition.getZ() + 0.5;
        if (phase == GATHER || phase == GROW) clientGrowing(phase, random, elapsed, cx, cy, cz);
        else clientWorking(random, elapsed, cx, cy, cz);
    }

    // ---- Messages ----

    /** Tells nearby players (action bar by default: AlchyXConfig messages.machine). */
    protected void say(Component message) {
        notifyNearbyPlayers(message);
    }

    // ---- Removal: what was given comes back ----

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (moving) return; // the core moved: its structure and gifts went with it
        super.preRemoveSideEffects(pos, state);
        if (level == null || level.isClientSide()) return;
        for (Map.Entry<Identifier, Integer> entry : offered.entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(entry.getKey());
            int left = entry.getValue();
            while (left > 0) {
                int count = Math.min(left, item.getDefaultMaxStackSize());
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(item, count));
                left -= count;
            }
        }
        offered.clear();
    }

    // ---- Saving ----

    private static final com.mojang.serialization.Codec<Map<Identifier, Integer>> OFFERED_CODEC =
            com.mojang.serialization.Codec.unboundedMap(Identifier.CODEC, com.mojang.serialization.Codec.INT);

    /** The core's item keeps the tier it had grown to: set down and built again, it regrows the rest for free. */
    @Override
    protected boolean keepsStateOnItem() {
        return true;
    }

    @Override
    protected void keepOnItem(net.minecraft.nbt.CompoundTag tag) {
        super.keepOnItem(tag);
        if (tier > 1) tag.putInt("tier", tier);
    }

    @Override
    protected void restoreFromItem(net.minecraft.nbt.CompoundTag tag) {
        super.restoreFromItem(tag);
        tier = Mth.clamp(tag.getIntOr("tier", tier), 1, maxTier());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("tier", tier);
        output.putInt("standing", standing);
        output.putInt("phase", phase);
        output.putLong("phaseStart", phaseStart);
        output.putInt("goal", goal);
        if (!offered.isEmpty()) output.store("offered", OFFERED_CODEC, offered);
        if (coreRise != 1) output.putInt("coreRise", coreRise);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        int oldStanding = standing;
        super.loadAdditional(input);
        tier = Mth.clamp(input.getIntOr("tier", 1), 1, maxTier());
        standing = Mth.clamp(input.getIntOr("standing", 1), 1, tier);
        phase = input.getIntOr("phase", IDLE);
        phaseStart = input.getLongOr("phaseStart", 0L);
        goal = Mth.clamp(input.getIntOr("goal", tier), 1, maxTier());
        offered.clear();
        input.read("offered", OFFERED_CODEC).ifPresent(offered::putAll);
        coreRise = input.getIntOr("coreRise", 1);
        if (standing != oldStanding) plan = null;
    }
}
