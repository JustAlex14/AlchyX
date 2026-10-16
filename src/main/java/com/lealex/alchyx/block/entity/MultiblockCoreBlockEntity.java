package com.lealex.alchyx.block.entity;

import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.multiblock.ShellParts;
import com.lealex.alchyx.multiblock.CoreTracker;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a machine core shares: checking its multiblock against its pattern JSON, the transformation wave,
 * destroying the parts when the structure breaks, the core tracker, client sync and saving.
 *
 * A machine (distortion matrix, destructuration core, ...) extends this, names its pattern with
 * {@link #patternId()} and puts its own work in {@link #tickFormed()}.
 */
public abstract class MultiblockCoreBlockEntity extends BlockEntity {
    public static final int RECHECK_INTERVAL = 40;    // safety re-check every 2 seconds
    private static final double MESSAGE_RANGE = 32.0; // blocks

    private boolean formed = false;
    private long formedAt = 0L;         // game time the last transformation wave finished (0 = never seen forming)
    private boolean forming = false;   // transformation wave in progress
    private boolean announced = true;  // false while a formed machine still plays its formation animation (settling)
    // The pattern the structure was built with (not saved). If a /reload changes the JSON, breaking the machine
    // still cleans up the parts and consumed blocks of the old shape.
    private @Nullable MultiblockPattern builtPattern;
    private @Nullable String structureProblem = "not checked yet";
    private boolean checkRequested = true;
    private int tickCounter = 0;
    private boolean syncPending = false;
    // Real vanilla blocks of the formed structure ("consumed" blocks and "real" results: cauldron, campfire,
    // candles, lava...). Right-clicking them opens the machine instead of using them, so e.g. an empty bottle
    // can't drain the cauldron and break the chamber. Synced, so the client cancels the click too.
    // null = not computed yet (a chamber formed before this existed); empty when not formed.
    private @Nullable List<BlockPos> protectedPositions;
    private static final int PROTECT_SEARCH_RADIUS = 4; // how far a click looks for the machine owning a block

    // Animation state (saved and synced): what the client-side animation files react to. Machines feed it with
    // setAnimationWorking / startAnimationOperation / finishAnimationOperation / setAnimationDisplay.
    private boolean animWorking = false;
    private long animOpStart = Long.MIN_VALUE; // game time the current operation started
    private int animOpTicks = 0;               // how long it lasts
    private int animOpCount = 0;               // finished operations (wraps; only changes matter)
    private final Map<String, ItemStack> animDisplays = new HashMap<>();

    protected MultiblockCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- What each machine provides ----

    /** The pattern file of this machine, e.g. alchymastery:distortion_chamber (data/alchymastery/multiblock/...). */
    public abstract Identifier patternId();

    /** Name used in chat messages, e.g. "Distortion chamber". */
    protected abstract String machineName();

    /** Work done every server tick while the structure is formed. */
    protected void tickFormed() {}

    /** Called on the server when the structure finishes forming (true) or breaks (false). */
    protected void onStructureChanged(boolean nowFormed) {}

    /** Called every tick on the client (visual effects only). */
    public void clientTick() {}

    /** Items pipes and hoppers reach through the machine's parts, or null for none. */
    public @Nullable ResourceHandler<ItemResource> getItemHandler() {
        return null;
    }

    /** Fluids pipes and buckets reach through the machine's parts and core, or null for none. */
    public @Nullable ResourceHandler<FluidResource> getFluidHandler() {
        return null;
    }

    /** Forge Energy pipes and cables reach through the machine's parts and core, or null for none. */
    public net.neoforged.neoforge.transfer.energy.@Nullable EnergyHandler getEnergyHandler() {
        return null;
    }

    /** Right-click on the core or a part. Default: show the machine's state in the action bar. */
    public void openGui(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            String state = formed ? "formed" : forming ? "forming..." : "not formed: " + structureProblem;
            serverPlayer.sendSystemMessage(Component.literal(machineName() + ": " + state), true);
        }
    }

    // ---- Animation state (see com.lealex.alchyx.animation) ----

    /** The machine is working (animation files: "when": "working"). Syncs to clients when it changes. */
    protected void setAnimationWorking(boolean working) {
        if (animWorking != working) {
            animWorking = working;
            setChanged();
            syncToClients();
        }
    }

    /** An operation starts now and lasts {@code ticks} ("when": "start"; drives "converge" items). */
    protected void startAnimationOperation(int ticks) {
        if (level == null) return;
        animOpStart = level.getGameTime();
        animOpTicks = Math.max(1, ticks);
        setChanged();
        syncToClients();
    }

    /** An operation just finished ("when": "finish"). */
    protected void finishAnimationOperation() {
        animOpCount++;
        setChanged();
        syncToClients();
    }

    /**
     * The item shown by animation effects under this name ("display": "input"), e.g. the stack being processed.
     * Empty clears it. Syncs to clients when it changes.
     */
    protected void setAnimationDisplay(String name, ItemStack stack) {
        ItemStack current = animDisplays.getOrDefault(name, ItemStack.EMPTY);
        if (ItemStack.matches(current, stack)) return;
        if (stack.isEmpty()) animDisplays.remove(name);
        else animDisplays.put(name, stack.copy());
        setChanged();
        syncToClients();
    }

    public boolean isAnimationWorking() {
        return animWorking;
    }

    public long getAnimationOperationStart() {
        return animOpStart;
    }

    public int getAnimationOperationCount() {
        return animOpCount;
    }

    /** How far the current operation is (0 to 1) at this game time. */
    public float getAnimationProgress(float gameTime) {
        if (animOpTicks <= 0 || animOpStart == Long.MIN_VALUE) return 0;
        return Math.clamp((gameTime - animOpStart) / animOpTicks, 0.0F, 1.0F);
    }

    public ItemStack getAnimationDisplay(String name) {
        return animDisplays.getOrDefault(name, ItemStack.EMPTY);
    }

    /**
     * A named number the animation files can test ("min_level": {"name": n}), e.g. how corrupted the machine is by
     * its upgrades. Saved and synced to clients when it changes; 0 removes it.
     */
    protected void setAnimationLevel(String name, int value) {
        if (getAnimationLevel(name) == value) return;
        if (value == 0) animLevels.remove(name);
        else animLevels.put(name, value);
        setChanged();
        syncToClients();
    }

    public int getAnimationLevel(String name) {
        return animLevels.getOrDefault(name, 0);
    }

    private final Map<String, Integer> animLevels = new HashMap<>();

    // ---- Upgrades (com.lealex.alchyx.upgrade) ----

    private com.lealex.alchyx.upgrade.@Nullable UpgradeStorage upgrades;

    /**
     * Gives this machine upgrade slots, one per type (call it once, from a field initializer or the constructor). The
     * base class saves and syncs them, drops them with the core, shows each installed upgrade as the animation display
     * "upgrade_<type path>", publishes the tiers' sum as the animation level "corruption", and the client draws
     * corruption veins over the structure. Read tiers with {@code getUpgrades().tier(type)}.
     */
    protected com.lealex.alchyx.upgrade.UpgradeStorage createUpgrades(java.util.List<com.lealex.alchyx.upgrade.UpgradeType> types) {
        upgrades = new com.lealex.alchyx.upgrade.UpgradeStorage(types, this::onUpgradesChanged);
        return upgrades;
    }

    /** This machine's upgrades, or null if it takes none. */
    public com.lealex.alchyx.upgrade.@Nullable UpgradeStorage getUpgrades() {
        return upgrades;
    }

    /** An upgrade went in or out: saved, shown on the structure, and the corruption level follows. */
    protected void onUpgradesChanged() {
        if (upgrades == null) return;
        setChanged();
        for (com.lealex.alchyx.upgrade.UpgradeType type : upgrades.types()) {
            setAnimationDisplay(type.displayName(), upgrades.stack(type));
        }
        setAnimationLevel(com.lealex.alchyx.upgrade.UpgradeStorage.CORRUPTION, upgrades.corruption());
        syncToClients(); // the veins read the slots on the client
    }

    // ---- Accessors ----

    /**
     * True if this machine reveals its converted blocks itself, with its own formation animation: the wave's blocks
     * stay invisible on clients (veiled) until the animation shows each one ({@code ChamberShellBlockEntity
     * #setClientHidden(false)}), e.g. the AlchyMastery nexuses' rifts. The animation must reveal every veiled block
     * it finds, even when it's turned off.
     */
    public boolean veilsWave() {
        return false;
    }

    /**
     * For a machine whose waves are veiled ({@link #veilsWave()}, or shells it places veiled itself): true if each
     * block puffs a few motes on the clients as it hides (client/WaveEffects.phaseOut). Default true.
     */
    public boolean veilPuffs() {
        return true;
    }

    /**
     * Ticks a formed machine spends settling (playing its own formation animation) before it counts as done: the
     * "formed!" message and sounds come then, and it starts working only then. 0 = right away.
     */
    protected int formationSettleTicks() {
        return 0;
    }

    /** True once formed and done settling (its formation animation over). */
    public boolean isSettled() {
        return formed && announced;
    }

    /**
     * For a machine whose real structure is not in its pattern but found by itself around its core (see
     * com.lealex.alchyx.multiblock.Enclosure): how far from the core, along x and z, a block change must also ask it
     * for a structure check ({@link #requestStructureCheck()}, which such a machine overrides to search again).
     * 0 = only the pattern's own box. Read once, when the core loads.
     */
    protected int watchReach() {
        return 0;
    }

    /**
     * True for a machine that announces itself its own way: the "forming", "formed!" and "broken" messages and the
     * forming and breaking sounds of this class are left out (e.g. a machine whose pattern is its core alone).
     */
    protected boolean quiet() {
        return false;
    }

    /**
     * The pattern the client's structure preview shows around this core (ghost blocks for what is missing, red dust on
     * what is in the way), or null for no preview. Default: the machine's pattern while it is not formed. A machine
     * that grows tiers can name its next tier while formed, to show where the next blocks will go (parts it already
     * has count as done).
     */
    public @Nullable Identifier previewPatternId() {
        return isFormed() ? null : patternId();
    }

    /**
     * True while the machine reshapes its own structure (a machine that grows or clears its parts, like
     * AlchyForbidden's altar between two tiers): the structure checks wait, so a part it removes on purpose does not
     * break it. Checks resume (and catch up) as soon as this is false again. Default false.
     */
    protected boolean holdsStructure() {
        return false;
    }

    /** Called once the machine is formed and done settling (server). */
    protected void onFormationSettled() {}

    /** Called every tick while the transformation wave runs (server). */
    protected void tickForming() {}

    /** The puff each block of the wave gives as it switches (a machine-themed particle; reverse portal by default). */
    public net.minecraft.core.particles.ParticleOptions waveParticle() {
        return ParticleTypes.REVERSE_PORTAL;
    }

    /**
     * Game time the machine last finished forming (its transformation wave), or 0 if it formed before this was
     * recorded. Saved and synced: clients can play a formation animation from it (e.g. the nexuses' folding).
     */
    public long getFormedAt() {
        return formedAt;
    }

    public boolean isFormed() {
        return formed;
    }

    public boolean isForming() {
        return forming;
    }

    public @Nullable String getStructureProblem() {
        return structureProblem;
    }

    public @Nullable MultiblockPattern pattern() {
        // The client has its own copy (sent by the server on join and /reload): on a dedicated server the
        // server-side map is empty in the client's JVM
        if (level != null && level.isClientSide()) return MultiblockPatterns.client().get(patternId());
        return MultiblockPatterns.get(patternId());
    }

    /** How the pattern is turned for this core (from the core block's facing, if it has one). */
    public Rotation rotation() {
        MultiblockPattern pattern = pattern();
        return pattern == null ? Rotation.NONE : pattern.rotationFor(getBlockState());
    }

    /**
     * For menus' stillValid: the player can still use this machine, i.e. is within reach of any block of its structure
     * (the player's block interaction range + 4, like vanilla containers). Measuring from the core alone closed the
     * GUI at once when a big machine was opened by a click on its far side.
     */
    public boolean withinReach(net.minecraft.world.entity.player.Player player) {
        if (isRemoved()) return false;
        MultiblockPattern pattern = builtPattern != null ? builtPattern : pattern();
        net.minecraft.world.phys.AABB box;
        if (pattern == null) {
            box = new net.minecraft.world.phys.AABB(worldPosition);
        } else {
            Rotation rotation = rotation();
            BlockPos a = worldPosition.offset(pattern.minOffset().rotate(rotation)), b = worldPosition.offset(pattern.maxOffset().rotate(rotation));
            box = net.minecraft.world.phys.AABB.encapsulatingFullBlocks(a, b);
        }
        double range = player.blockInteractionRange() + 4.0;
        return box.distanceToSqr(player.getEyePosition()) < range * range;
    }

    /** True if pos is one of this machine's structure blocks (as built): a block, tag or core cell, not "air"/"any". */
    public boolean structureContains(BlockPos pos) {
        MultiblockPattern pattern = builtPattern != null ? builtPattern : pattern();
        if (pattern == null) return false;
        Rotation rotation = rotation();
        if (!pattern.contains(worldPosition, rotation, pos)) return false; // cheap box test first
        // Then the real cells: machines built side by side can overlap in bounding-box space (their "air"/"any"
        // cells), and a click on one machine's block must not find its neighbor
        if (occupied == null || occupiedPattern != pattern || occupiedRotation != rotation) {
            occupied = pattern.occupiedPositions(worldPosition, rotation);
            occupiedPattern = pattern;
            occupiedRotation = rotation;
        }
        return occupied.contains(pos);
    }

    // structureContains' cache: the structure's real cells, for this pattern and rotation
    private java.util.@Nullable Set<BlockPos> occupied;
    private @Nullable MultiblockPattern occupiedPattern;
    private @Nullable Rotation occupiedRotation;

    /** True if pos is one of this formed machine's real vanilla blocks (see protectedPositions). */
    public boolean protects(BlockPos pos) {
        return formed && protectedPositions != null && protectedPositions.contains(pos);
    }

    /**
     * The formed machine whose real vanilla block (cauldron, campfire, candles...) is at pos, or null. Works on both
     * sides (the client gets the list with the block entity data), by looking at the block entities around pos.
     */
    public static @Nullable MultiblockCoreBlockEntity protectingCore(net.minecraft.world.level.Level level, BlockPos pos) {
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-PROTECT_SEARCH_RADIUS, -PROTECT_SEARCH_RADIUS, -PROTECT_SEARCH_RADIUS),
                pos.offset(PROTECT_SEARCH_RADIUS, PROTECT_SEARCH_RADIUS, PROTECT_SEARCH_RADIUS))) {
            if (level.getBlockEntity(near) instanceof MultiblockCoreBlockEntity core && core.protects(pos)) return core;
        }
        return null;
    }

    private void refreshProtectedPositions() {
        List<BlockPos> found = new ArrayList<>();
        MultiblockPattern pattern = builtPattern != null ? builtPattern : pattern();
        if (pattern != null) {
            Rotation rotation = pattern.rotationFor(getBlockState());
            for (var consumed : pattern.consumed(worldPosition, rotation)) found.add(consumed.getKey().immutable());
            for (MultiblockPattern.Conversion conversion : pattern.conversions(worldPosition, rotation)) {
                if (conversion.result().real()) found.add(conversion.pos().immutable());
            }
        }
        protectedPositions = found;
        setChanged();
        syncToClients();
    }

    /** Asks for a structure check on the next tick (called when a nearby block changes). */
    public void requestStructureCheck() {
        checkRequested = true;
    }

    /**
     * The same, told which block changed. A machine whose structure is searched for (see {@link #watchReach()}) can
     * override this to leave out changes that cannot matter to it (its own work inside its ground), and so not
     * search again after each of them. By default every change asks for a check.
     */
    public void requestStructureCheck(BlockPos changed) {
        requestStructureCheck();
    }

    // ---- Client sync ----

    /** Asks to send this block entity's data to nearby players, once at the end of the current server tick. */
    protected void syncToClients() {
        if (level != null && !level.isClientSide()) syncPending = true;
    }

    // Data sent when the chunk loads on a client
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    // Packet sent by syncToClients(); the client reads it back with loadAdditional
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ---- Tracking: register in the per-world core list while loaded ----

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            CoreTracker.add(level, worldPosition, patternId());
            CoreTracker.watch(level, worldPosition, watchReach());
            checkRequested = true;
        } else if (level != null) {
            LoadedCores.add(this); // the client-side animation engine plays this core's animation file
        }
    }

    // Called when the core block itself is removed (not when its chunk unloads)
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide() && (formed || forming)) {
            destroyParts();
        }
        if (level != null && !level.isClientSide() && upgrades != null) upgrades.dropAll(level, pos); // upgrades drop with the core
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            CoreTracker.remove(level, worldPosition);
            if (formed) invalidateStructureCapabilities();
        } else {
            LoadedCores.remove(this);
        }
    }

    // ---- Ticking ----

    /** Called every tick on the server (see MultiblockCoreBlock#getTicker). */
    public final void serverTick() {
        tickCounter++;
        if (checkRequested || tickCounter % RECHECK_INTERVAL == 0) {
            checkRequested = false;
            updateStructure();
        }
        if (forming) {
            tickForming();
            advanceWave();
        } else if (formed) {
            if (protectedPositions == null) refreshProtectedPositions();
            if (!announced) { // still settling (its formation animation): announce it once that's over, work after
                if (level != null && level.getGameTime() - formedAt >= formationSettleTicks()) announceFormed();
            } else {
                tickFormed();
            }
        }
        // Send at most one update per tick, after everything changed: the client never sees half a change
        if (syncPending) {
            syncPending = false;
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private void updateStructure() {
        if (level == null || holdsStructure()) return;
        MultiblockPattern pattern = pattern();
        if (pattern == null) {
            // Broken or missing JSON: don't break an existing machine (and eat its blocks) over a typo
            structureProblem = "no valid " + patternId() + " pattern loaded (see the log)";
            return;
        }
        structureProblem = pattern.findProblem(level, worldPosition, rotation());

        if (structureProblem != null) {
            if (formed || forming) {
                boolean wasFormed = formed;
                formed = false;
                forming = false;
                setChanged();
                destroyParts(); // the frame is absorbed by the ritual: nothing drops
                syncToClients();
                if (wasFormed) invalidateStructureCapabilities();
                protectedPositions = List.of();
                onStructureChanged(false);
                if (!quiet()) {
                    playSound(worldPosition, SoundEvents.BEACON_DEACTIVATE, 1.0f, 1.0f);
                    notifyNearbyPlayers(machineName() + " broken: " + structureProblem);
                }
            }
        } else if (!formed && !forming) {
            forming = true;
            setChanged();
            syncToClients(); // clients play their forming effects from isForming()
            if (!quiet()) notifyNearbyPlayers(machineName() + " forming...");
        } else if (formed && hasUnconvertedBlocks(pattern)) {
            // e.g. a /reload added blocks that convert, or a structure formed before the wave existed
            formed = false;
            forming = true;
            setChanged();
            syncToClients();
            invalidateStructureCapabilities();
        }
        if (structureProblem == null) builtPattern = pattern;
    }

    private boolean hasUnconvertedBlocks(MultiblockPattern pattern) {
        if (level == null) return false;
        for (MultiblockPattern.Conversion conversion : pattern.conversions(worldPosition, rotation())) {
            BlockState current = level.getBlockState(conversion.pos());
            if (!conversion.isDone(current) && !conversion.skipped(current)) return true;
        }
        return false;
    }

    /** Converts the next block into a chamber shell, nearest first; finishes the machine when none are left. */
    private void advanceWave() {
        if (level == null) return;
        MultiblockPattern pattern = pattern();
        if (pattern == null) return;
        for (MultiblockPattern.Conversion conversion : pattern.conversions(worldPosition, rotation())) {
            BlockPos pos = conversion.pos();
            BlockState current = level.getBlockState(pos);
            if (!conversion.isDone(current) && !conversion.skipped(current)) {
                // No breaking: the block simply switches to its new look (a puff of void and a chime); veiled waves
                // phase it out to a see-through veil instead (clients: client/WaveEffects.phaseOut)
                if (conversion.result().real()) {
                    // The real block (candles, spore blossom...), so its flames and particles show
                    level.setBlock(pos, conversion.disguise(current), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                } else {
                    ChamberShellBlock.place(level, pos, conversion.disguise(current), conversion.result().hidden(),
                            conversion.result().restore(), veilsWave() ? worldPosition.asLong() : 0L);
                }
                if (!veilsWave() && level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(waveParticle(),
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.05);
                }
                if (!quiet()) playSound(pos, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 0.8f + level.getRandom().nextFloat() * 0.4f);
                return; // one block per tick
            }
        }

        forming = false;
        formed = true;
        formedAt = level.getGameTime();
        announced = formationSettleTicks() <= 0; // settling first (before onStructureChanged, which may look at it)
        setChanged();
        syncToClients();
        invalidateStructureCapabilities();
        refreshProtectedPositions();
        onStructureChanged(true);
        if (!announced) return; // the machine plays its own formation animation first (e.g. the nexuses)
        announceFormed();
    }

    /** The machine is done forming: its sounds and message (after its formation animation, if it has one). */
    private void announceFormed() {
        if (level == null) return;
        announced = true;
        setChanged();
        syncToClients();
        onFormationSettled();
        if (quiet()) return;
        playSound(worldPosition, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.0f);
        if (!veilsWave()) { // an ordinary machine is done: a resonant chord (veiled ones end with their own animation)
            playSound(worldPosition, SoundEvents.END_PORTAL_FRAME_FILL, 1.0f, 0.8f);
            playSound(worldPosition, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0f, 1.0f);
            playSound(worldPosition, SoundEvents.PLAYER_LEVELUP, 0.6f, 0.7f);
        }
        notifyNearbyPlayers(machineName() + " formed!");
    }

    /** Removes every chamber part and every "consumed" block of the structure, without drops. */
    private void destroyParts() {
        if (level == null) return;
        MultiblockPattern pattern = builtPattern != null ? builtPattern : pattern();
        if (pattern == null) return;
        // Consumed blocks first: candles, spore blossoms, lanterns... would otherwise lose their support when the
        // parts go and pop off as items before we get to them
        removeConsumedBlocks();
        for (BlockPos pos : pattern.allPositions(worldPosition, pattern.rotationFor(getBlockState()))) {
            if (ShellParts.isPart(level.getBlockState(pos))) {
                if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell && shell.isRestore()) {
                    // An absorbed valuable block (a machine core inside the nexus): put it back
                    shell.markRestoring();
                    level.setBlock(pos.immutable(), shell.getDisguise(), Block.UPDATE_ALL);
                } else {
                    level.destroyBlock(pos.immutable(), false);
                }
            }
        }
    }

    /**
     * Removes the structure's "consumed" blocks (lava, candles, spore blossom...) without drops. Also called by a
     * chamber shell just before it is broken, so blocks resting on it vanish instead of dropping.
     */
    public void removeConsumedBlocks() {
        if (level == null || level.isClientSide()) return;
        MultiblockPattern pattern = builtPattern != null ? builtPattern : pattern();
        if (pattern == null) return;
        for (var consumed : pattern.consumed(worldPosition, pattern.rotationFor(getBlockState()))) {
            BlockState state = level.getBlockState(consumed.getKey());
            if (!consumed.getValue().test(state)) continue;
            if (state.getBlock() instanceof LiquidBlock) {
                // destroyBlock would put the liquid straight back (a lava block "breaks" into lava): set air instead
                level.setBlock(consumed.getKey(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            } else {
                level.destroyBlock(consumed.getKey(), false);
            }
        }
        // Real blocks placed by the wave ("real" results)
        for (MultiblockPattern.Conversion conversion : pattern.conversions(worldPosition, pattern.rotationFor(getBlockState()))) {
            if (conversion.result().real() && conversion.isDone(level.getBlockState(conversion.pos()))) {
                level.destroyBlock(conversion.pos(), false);
            }
        }
    }

    protected void playSound(BlockPos pos, SoundEvent sound, float volume, float pitch) {
        if (level != null) level.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
    }

    private void invalidateStructureCapabilities() {
        if (level == null) return;
        MultiblockPattern pattern = builtPattern != null ? builtPattern : pattern();
        if (pattern == null) return;
        for (BlockPos pos : pattern.allPositions(worldPosition, pattern.rotationFor(getBlockState()))) {
            level.invalidateCapabilities(pos.immutable());
        }
    }

    protected void notifyNearbyPlayers(String message) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        Vec3 center = Vec3.atCenterOf(worldPosition);
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(center) <= MESSAGE_RANGE * MESSAGE_RANGE) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    // ---- Saving (subclasses call super) ----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("formed", formed);
        output.putBoolean("forming", forming);
        output.putBoolean("announced", announced);
        if (protectedPositions != null) output.store("protected", BlockPos.CODEC.listOf(), protectedPositions);
        output.putBoolean("animWorking", animWorking);
        output.putLong("animOpStart", animOpStart);
        output.putInt("animOpTicks", animOpTicks);
        output.putInt("animOpCount", animOpCount);
        if (!animDisplays.isEmpty()) output.store("animDisplays", DISPLAYS_CODEC, animDisplays);
        if (!animLevels.isEmpty()) output.store("animLevels", LEVELS_CODEC, animLevels);
        if (formedAt != 0L) output.putLong("formedAt", formedAt);
        if (upgrades != null) upgrades.save(output);
    }

    private static final com.mojang.serialization.Codec<Map<String, Integer>> LEVELS_CODEC =
            com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.INT);

    private static final com.mojang.serialization.Codec<Map<String, ItemStack>> DISPLAYS_CODEC =
            com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, ItemStack.CODEC);

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        formed = input.getBooleanOr("formed", false);
        forming = input.getBooleanOr("forming", false);
        announced = input.getBooleanOr("announced", true);
        protectedPositions = input.read("protected", BlockPos.CODEC.listOf()).map(list -> (List<BlockPos>) new ArrayList<>(list)).orElse(null);
        animWorking = input.getBooleanOr("animWorking", false);
        animOpStart = input.getLongOr("animOpStart", Long.MIN_VALUE);
        animOpTicks = input.getIntOr("animOpTicks", 0);
        animOpCount = input.getIntOr("animOpCount", 0);
        animDisplays.clear();
        input.read("animDisplays", DISPLAYS_CODEC).ifPresent(animDisplays::putAll);
        formedAt = input.getLongOr("formedAt", 0L);
        animLevels.clear();
        input.read("animLevels", LEVELS_CODEC).ifPresent(animLevels::putAll);
        if (upgrades != null) upgrades.load(input);
    }
}
