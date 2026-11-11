package com.lealex.alchyx.block.entity;

import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.multiblock.CoreTracker;
import com.lealex.alchyx.registry.AlchyXRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelProperty;

/**
 * Remembers which block a chamber shell looks like (its "disguise").
 *
 * The client model can't read block entities directly (chunks are meshed on worker threads), so the disguise is
 * handed to it through NeoForge's {@link ModelData}: {@link #getModelData()} is snapshotted on the main thread and
 * read by {@code DisguiseModel} while meshing.
 */
public class ChamberShellBlockEntity extends BlockEntity {
    public static final ModelProperty<BlockState> DISGUISE = new ModelProperty<>();
    public static final ModelProperty<Boolean> HIDDEN = new ModelProperty<>();
    public static final ModelProperty<Integer> TINT = new ModelProperty<>();

    // Color replacing the disguise's own tint (e.g. a cauldron that holds distortion fluid instead of water);
    // 0 = use the disguise's normal colors
    private int tint = 0;

    private boolean hidden = false; // a "hidden" result: keeps its shape, draws nothing
    private long convertedAt = Long.MIN_VALUE / 2; // game time the wave turned this block into a shell
    private long materializedAt = Long.MIN_VALUE;  // client: the conversion whose materializing effect already played
    private long veiledBy = 0L;                    // core (BlockPos.asLong) that reveals this block itself, 0 = none
    private boolean clientHidden = false;          // client: veiled, drawn by its core's animation instead
    private boolean restore = false; // a "restore" result: the original block comes back when the machine breaks
    private boolean restoring = false; // not saved: the machine is putting the original block back right now

    private BlockState disguise;
    // Client only, not saved: a temporary look set by an animation ("swap" of a block move), e.g. an extended piston
    private @Nullable BlockState clientLook;

    // Piston animation: game time when the current strike started (see strikeExtension)
    public static final int STRIKE_EXTEND_TICKS = 2, STRIKE_HOLD_TICKS = 2, STRIKE_RETRACT_TICKS = 3;
    public static final int STRIKE_TICKS = STRIKE_EXTEND_TICKS + STRIKE_HOLD_TICKS + STRIKE_RETRACT_TICKS;
    private long strikeStart = Long.MIN_VALUE / 2;

    public ChamberShellBlockEntity(BlockPos pos, BlockState state) {
        super(AlchyXRegistries.CHAMBER_SHELL_BE.get(), pos, state);
        this.disguise = defaultDisguise();
    }

    private static BlockState defaultDisguise() {
        return net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(); // until the real disguise loads
    }

    // Called just before this shell is removed (broken by a player, an explosion...). Blocks resting on it that the
    // machine consumes (candles, spore blossom) are removed now, without drops; otherwise they would lose their
    // support and pop off as items before the core notices the structure is broken.
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide()) {
            MultiblockCoreBlockEntity core = CoreTracker.formedCoreAt(level, pos);
            if (core != null) core.removeConsumedBlocks();
            // A valuable absorbed block (a machine core in the nexus) broken directly: drop it, never lose it
            if (restore && !restoring) Block.popResource(level, pos, new ItemStack(disguise.getBlock()));
        }
    }

    /** Client, every tick: effects the real block would make from its block entity (campfire smoke, like vanilla). */
    public void clientTick() {
        if (level == null || hidden || !CampfireBlock.isLitCampfire(disguise)) return;
        RandomSource random = level.getRandom();
        if (random.nextFloat() < 0.11F) {
            for (int i = 0; i < random.nextInt(2) + 2; i++) {
                CampfireBlock.makeParticles(level, worldPosition, disguise.getValue(CampfireBlock.SIGNAL_FIRE), false);
            }
        }
    }

    // ---- The disguise's own block entity data (a spawner's mob, a banner's patterns...) ----

    private @Nullable CompoundTag data;           // as the block had it when it turned into a shell, or as set in the studio
    private @Nullable BlockEntity puppet;         // client: the disguise's block entity made from it, only drawn
    private boolean puppetFailed;                 // client: it couldn't be made or ticked: don't try again
    private long puppetTick = Long.MIN_VALUE;

    /** The disguise's block entity data kept by this shell (a copy), or null. */
    public @Nullable CompoundTag getData() {
        return data == null ? null : data.copy();
    }

    /** Keeps this block entity data for the disguise (null: none); clients draw the disguise's block entity from it. */
    public void setData(@Nullable CompoundTag data) {
        this.data = data == null || data.isEmpty() ? null : data.copy();
        puppet = null;
        puppetFailed = false;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public boolean hasData() {
        return data != null;
    }

    /**
     * Client: the disguise's block entity, made from the kept data and ticked once a game tick (a spawner's mob
     * turns), only to be drawn: it is in no level's list, so it does nothing else. Null if there's none to draw.
     */
    public @Nullable BlockEntity puppet() {
        if (data == null || level == null || !level.isClientSide() || hidden || clientHidden || puppetFailed) return null;
        BlockState look = clientLook != null ? clientLook : disguise;
        if (puppet != null && puppet.getBlockState().getBlock() != look.getBlock()) puppet = null;
        try {
            if (puppet == null) {
                if (!(look.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) return null;
                BlockEntity made = entityBlock.newBlockEntity(worldPosition, look);
                if (made == null) {
                    puppetFailed = true;
                    return null;
                }
                made.setLevel(level);
                made.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                        level.registryAccess(), data));
                puppet = made;
            }
            if (puppet.getBlockState() != look) puppet.setBlockState(look);
            if (level.getGameTime() != puppetTick) {
                puppetTick = level.getGameTime();
                tickPuppet(puppet, look);
            }
        } catch (RuntimeException e) { // a block entity that wants more than a look-alike can give: just its block then
            puppetFailed = true;
            puppet = null;
        }
        return puppet;
    }

    @SuppressWarnings("unchecked")
    private <T extends BlockEntity> void tickPuppet(BlockEntity entity, BlockState look) {
        net.minecraft.world.level.block.entity.BlockEntityTicker<T> ticker =
                look.getTicker(level, (net.minecraft.world.level.block.entity.BlockEntityType<T>) entity.getType());
        if (ticker != null) ticker.tick(level, worldPosition, look, (T) entity);
    }

    private net.minecraft.world.phys.@Nullable AABB renderBox;

    /** Client: the box the shell renderer is culled with (cached: asked for every visible shell every frame). */
    public net.minecraft.world.phys.AABB renderBox() {
        if (renderBox == null) renderBox = new net.minecraft.world.phys.AABB(worldPosition).inflate(1.0);
        return renderBox;
    }

    public BlockState getDisguise() {
        return disguise;
    }

    public void setDisguise(BlockState disguise) {
        this.disguise = disguise.getBlock() instanceof ChamberShellBlock ? defaultDisguise() : disguise;
        setChanged();
        // Keep the "smoking" state (whether the shell gets a client ticker) in step with the new look
        BlockState state = getBlockState();
        boolean smoking = !hidden && ChamberShellBlock.smokes(this.disguise);
        if (level != null && !level.isClientSide() && state.hasProperty(ChamberShellBlock.SMOKING)
                && state.getValue(ChamberShellBlock.SMOKING) != smoking) {
            level.setBlock(worldPosition, state.setValue(ChamberShellBlock.SMOKING, smoking), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /**
     * Client: show {@code look} instead of the disguise until called with null (animation "swap"). Redraws the
     * block right away.
     */
    public void setClientLook(@Nullable BlockState look) {
        if (level == null || !level.isClientSide() || java.util.Objects.equals(look, clientLook)) return;
        clientLook = look;
        requestModelDataUpdate();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
    }

    public long getStrikeStart() {
        return strikeStart;
    }

    /** How far a striking piston's head is out of its base (0 to 1), {@code elapsed} ticks after the strike began. */
    public static float strikeExtension(float elapsed) {
        if (elapsed < 0) return 0;
        if (elapsed < STRIKE_EXTEND_TICKS) return elapsed / STRIKE_EXTEND_TICKS;
        elapsed -= STRIKE_EXTEND_TICKS;
        if (elapsed < STRIKE_HOLD_TICKS) return 1;
        elapsed -= STRIKE_HOLD_TICKS;
        if (elapsed < STRIKE_RETRACT_TICKS) return 1 - elapsed / STRIKE_RETRACT_TICKS;
        return 0;
    }

    /** Server: a piston shell starts a strike (its base shows extended; clients animate the head). */
    public void startStrike(long gameTime) {
        if (!(disguise.getBlock() instanceof PistonBaseBlock)) return;
        strikeStart = gameTime;
        setDisguise(disguise.setValue(PistonBaseBlock.EXTENDED, true)); // also syncs strikeStart
    }

    /** Server: the strike is over, back to the closed piston. */
    public void endStrike() {
        if (disguise.getBlock() instanceof PistonBaseBlock && disguise.getValue(PistonBaseBlock.EXTENDED)) {
            setDisguise(disguise.setValue(PistonBaseBlock.EXTENDED, false));
        }
    }

    public int getTint() {
        return tint;
    }

    /** Set before setDisguise (which syncs to clients). 0 = the disguise's normal colors. */
    public void setTint(int tint) {
        this.tint = tint;
        setChanged();
    }

    public boolean isRestore() {
        return restore;
    }

    /** Set before setDisguise (which syncs to clients). */
    /**
     * Converted by a core that veils its wave ({@link MultiblockCoreBlockEntity#veilsWave}): clients that see the
     * conversion keep the block invisible until that core's animation reveals it ({@link #setClientHidden}).
     */
    public void markVeiled(long corePos) {
        veiledBy = corePos;
    }

    /** Game time the wave converted this block (for client phase effects). */
    public long getConvertedAt() {
        return convertedAt;
    }

    public long getVeiledBy() {
        return veiledBy;
    }

    public boolean isClientHidden() {
        return clientHidden;
    }

    /** Client: hide or show the block (a veiled block being revealed by its core's animation). Redraws it now. */
    public void setClientHidden(boolean hide) {
        if (level == null || !level.isClientSide() || clientHidden == hide) return;
        clientHidden = hide;
        requestModelDataUpdate();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
    }

    /** The wave converts this block now: clients seeing it fresh play the materializing effect (client/WaveEffects). */
    public void markConverted(long gameTime) {
        convertedAt = gameTime;
    }

    public void setRestore(boolean restore) {
        this.restore = restore;
        setChanged();
    }

    /** The machine is about to replace this shell with its original block (no item drop then). */
    public void markRestoring() {
        restoring = true;
    }

    public boolean isHidden() {
        return hidden;
    }

    /** Set before setDisguise (which syncs to clients). */
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        setChanged();
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(DISGUISE, clientLook != null ? clientLook : disguise).with(HIDDEN, hidden || clientHidden).with(TINT, tint).build();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("disguise", BlockState.CODEC, disguise);
        output.putLong("strikeStart", strikeStart);
        output.putBoolean("hidden", hidden);
        output.putBoolean("restore", restore);
        output.putInt("tint", tint);
        output.putLong("convertedAt", convertedAt);
        if (veiledBy != 0L) output.putLong("veiledBy", veiledBy);
        if (data != null) output.store("data", CompoundTag.CODEC, data);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        disguise = input.read("disguise", BlockState.CODEC).orElseGet(ChamberShellBlockEntity::defaultDisguise);
        strikeStart = input.getLongOr("strikeStart", Long.MIN_VALUE / 2);
        hidden = input.getBooleanOr("hidden", false);
        restore = input.getBooleanOr("restore", false);
        tint = input.getIntOr("tint", 0);
        convertedAt = input.getLongOr("convertedAt", Long.MIN_VALUE / 2);
        veiledBy = input.getLongOr("veiledBy", 0L);
        CompoundTag readData = input.read("data", CompoundTag.CODEC).orElse(null);
        if (!java.util.Objects.equals(readData, data)) {
            data = readData;
            puppet = null;
            puppetFailed = false;
        }
        if (level != null && level.isClientSide() && materializedAt != convertedAt && level.getGameTime() - convertedAt <= 3) {
            // a fresh conversion, once each (not when the chunk loads later); a shell converted again while it stands
            // (another look, the block entity kept) plays it again
            materializedAt = convertedAt;
            if (veiledBy != 0L && !hidden) { // it phases out; its core's animation brings it back later
                clientHidden = true;
                if (!(level.getBlockEntity(BlockPos.of(veiledBy)) instanceof MultiblockCoreBlockEntity core) || core.veilPuffs()) {
                    com.lealex.alchyx.client.WaveEffects.phaseOut(level, worldPosition);
                }
            }
        }
        if (level != null && level.isClientSide()) {
            // New disguise from the server: refresh the model data and redraw the block right away
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
