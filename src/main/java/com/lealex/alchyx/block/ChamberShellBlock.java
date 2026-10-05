package com.lealex.alchyx.block;

import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.lealex.alchyx.registry.AlchyXRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The block every converted chamber position becomes. It looks like (and has the shape and sound of) its
 * "disguise", the block named in the pattern's result grid, but behaves like a chamber part: no drops, can't be
 * pushed, right-click opens the matrix, pipes reach the matrix, destroyed when the chamber breaks.
 *
 * Some things Minecraft caches per block state, before any block entity exists, so they are stored as
 * block state properties chosen when the shell is placed:
 * OPAQUE (hides neighbor faces and blocks light like a full solid block) and LIGHT (light level it gives off).
 */
public class ChamberShellBlock extends Block implements EntityBlock {
    public static final BooleanProperty OPAQUE = BooleanProperty.create("opaque");
    public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);
    /** The disguise is a lit campfire: only these shells get a client ticker (for the smoke). */
    public static final BooleanProperty SMOKING = BooleanProperty.create("smoking");

    public ChamberShellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPAQUE, true).setValue(LIGHT, 0).setValue(SMOKING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPAQUE, LIGHT, SMOKING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChamberShellBlockEntity(pos, state);
    }

    /** Replaces the block at pos with a shell that looks like {@code disguise}. */
    public static void place(Level level, BlockPos pos, BlockState disguise) {
        place(level, pos, disguise, false);
    }

    /** Same, and {@code hidden} shells keep the disguise's shape but draw nothing (and hide nothing). */
    public static void place(Level level, BlockPos pos, BlockState disguise, boolean hidden) {
        place(level, pos, disguise, hidden, false);
    }

    /** Same, and {@code restore} shells give the original block back when the machine breaks (see ResultKey). */
    public static void place(Level level, BlockPos pos, BlockState disguise, boolean hidden, boolean restore) {
        place(level, pos, disguise, hidden, restore, 0L);
    }

    /** Same, veiled by the core at {@code veiledBy} (BlockPos.asLong, 0 = not veiled): see ChamberShellBlockEntity#markVeiled. */
    public static void place(Level level, BlockPos pos, BlockState disguise, boolean hidden, boolean restore, long veiledBy) {
        BlockState shell = AlchyXRegistries.CHAMBER_SHELL.get().defaultBlockState()
                .setValue(OPAQUE, !hidden && disguise.isSolidRender())
                .setValue(LIGHT, Math.clamp(disguise.getLightEmission(level, pos), 0, 15))
                .setValue(SMOKING, !hidden && smokes(disguise));
        // UPDATE_KNOWN_SHAPE: don't make the neighbors recompute their shape. Otherwise a corner stair next to a
        // stair that just became a shell would see "no stair here" and turn straight, breaking the pattern.
        // No UPDATE_NEIGHBORS either: nothing outside the chamber needs to react to a look-alike swap.
        level.setBlock(pos, shell, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity entity) {
            entity.markConverted(level.getGameTime());
            entity.markVeiled(veiledBy);
            entity.setHidden(hidden);
            entity.setRestore(restore);
            entity.setDisguise(disguise);
        }
    }

    /**
     * The disguise at pos, or null. Uses the model data when there is some (client chunk meshing, thread-safe),
     * otherwise the block entity (server, main thread).
     */
    public static @Nullable BlockState disguiseAt(BlockGetter level, BlockPos pos) {
        BlockState fromModel = level.getModelData(pos).get(ChamberShellBlockEntity.DISGUISE);
        if (fromModel != null) return fromModel;
        return level.getBlockEntity(pos) instanceof ChamberShellBlockEntity entity ? entity.getDisguise() : null;
    }

    // ---- Shape, sound and faces follow the disguise ----

    /** What the block at pos looks like: a shell's disguise, or the block itself. */
    public static BlockState lookAt(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChamberShellBlock) {
            BlockState disguise = disguiseAt(level, pos);
            if (disguise != null) return disguise;
        }
        return state;
    }

    /** True if a shell with this look needs a client ticker (a lit campfire's smoke). */
    public static boolean smokes(BlockState disguise) {
        return net.minecraft.world.level.block.CampfireBlock.isLitCampfire(disguise);
    }

    // Client only, and only for shells that look like a lit campfire (the smoke comes from the real campfire's
    // block entity ticker). Every other shell has no ticker at all: a big machine has a hundred shells.
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide() || !state.getValue(SMOKING)) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof ChamberShellBlockEntity shell) shell.clientTick();
        };
    }

    // Client: run the disguise's own ambient effects, so a shell that looks like a lit candle, a torch, a campfire or
    // a spore blossom shows its flames, smoke and spores like the real block (hidden shells show nothing)
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell && !shell.isHidden()) {
            BlockState disguise = shell.getDisguise();
            disguise.getBlock().animateTick(disguise, level, pos, random);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState disguise = disguiseAt(level, pos);
        return disguise != null ? disguise.getShape(level, pos, context) : Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState disguise = disguiseAt(level, pos);
        return disguise != null ? disguise.getCollisionShape(level, pos, context) : Shapes.block();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        return state.getValue(OPAQUE) ? Shapes.block() : Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return !state.getValue(OPAQUE);
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        BlockState disguise = disguiseAt(level, pos);
        return disguise != null ? disguise.getSoundType(level, pos, entity) : super.getSoundType(state, level, pos, entity);
    }

    /**
     * Lets two see-through shells hide the face between them the way their disguises would
     * (e.g. two glass windows side by side don't draw the glass face between them).
     * {@code pos}/{@code state} is this shell; {@code neighborState} is the block whose face may be hidden.
     */
    @Override
    public boolean hidesNeighborFace(BlockGetter level, BlockPos pos, BlockState state, BlockState neighborState, Direction dir) {
        BlockState mine = disguiseAt(level, pos);
        if (mine == null || Boolean.TRUE.equals(level.getModelData(pos).get(ChamberShellBlockEntity.HIDDEN))) return false;
        BlockState theirs = neighborState.getBlock() instanceof ChamberShellBlock
                ? disguiseAt(level, pos.relative(dir))
                : neighborState;
        return theirs != null && theirs.skipRendering(mine, dir.getOpposite());
    }
}
