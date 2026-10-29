package com.lealex.alchyx.command;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.block.entity.TieredCoreBlockEntity;
import com.lealex.alchyx.item.StructureBuilderItem;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /alchyx showcase build <pattern> <pos> [facing]}: raises a machine for a trailer or a showcase map. pos is the
 * middle of the machine's bottom layer (the floor of its bay); the core goes where that puts it, turned to face
 * `facing` if it can turn. Its blocks are laid a few a tick from the ground up, each with its sound and dust (never over
 * bedrock and the like), the core last, so it is seen being built and then forms by itself; a machine that grows tiers (TieredCoreBlockEntity) has earned its top tier, so the rift
 * then grows it in front of the camera. Silent: meant to be run from a trailer's commands.
 */
public final class ShowcaseCommand {
    private ShowcaseCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("alchyx")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("showcase").then(Commands.literal("build")
                        .then(Commands.argument("pattern", IdentifierArgument.id())
                                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(MultiblockPatterns.ids(), b))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(c -> build(c, null))
                                        .then(Commands.argument("facing", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("north", "south", "east", "west"), b))
                                                .executes(c -> build(c, StringArgumentType.getString(c, "facing")))))))));
    }

    private static int build(CommandContext<CommandSourceStack> context, String facingName) {
        Identifier id = IdentifierArgument.getId(context, "pattern");
        MultiblockPattern pattern = MultiblockPatterns.get(id);
        if (pattern == null || pattern.coreBlock() == null) {
            context.getSource().sendFailure(Component.literal("No machine pattern " + id));
            return 0;
        }
        ServerLevel level = context.getSource().getLevel();
        BlockPos target = BlockPosArgument.getBlockPos(context, "pos");
        BlockState coreState = pattern.coreBlock().defaultBlockState();
        if (facingName != null && coreState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = Direction.byName(facingName.toLowerCase(Locale.ROOT));
            if (facing != null && facing.getAxis().isHorizontal()) coreState = coreState.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        Rotation rotation = pattern.rotationFor(coreState);
        // The box's corners turned with the machine: the core goes where the box's bottom middle lands on target
        BlockPos a = pattern.minOffset().rotate(rotation), b = pattern.maxOffset().rotate(rotation);
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int minY = Math.min(a.getY(), b.getY());
        BlockPos core = target.offset(-Math.floorDiv(minX + maxX, 2), -minY, -Math.floorDiv(minZ + maxZ, 2));

        List<MultiblockPattern.Placement> plan = new ArrayList<>();
        for (MultiblockPattern.Placement placement : pattern.blueprint(core, rotation)) {
            if (placement.pos().equals(core)) continue;
            BlockState current = level.getBlockState(placement.pos());
            if (placement.satisfiedBy(current)) continue;
            if (!current.isAir() && current.getDestroySpeed(level, placement.pos()) < 0) continue; // never over bedrock
            plan.add(placement);
        }
        // Built as a player would: bottom layer first, from the middle out, stairs after their layer's blocks; the core
        // goes in last, then the machine forms (and a growing one is owed its top tier)
        BlockPos middle = core;
        plan.sort(Comparator.<MultiblockPattern.Placement>comparingInt(p -> p.pos().getY())
                .thenComparingInt(p -> p.state().getBlock() instanceof StairBlock ? 1 : 0)
                .thenComparingDouble(p -> p.pos().distSqr(middle)));
        // about two to three seconds whatever its size: small first tiers a block every few ticks, big ones several a tick
        int size = Math.max(1, plan.size());
        JOBS.add(new Job(level, new ArrayDeque<>(plan), new ArrayList<>(), core, coreState,
                Math.max(1, Math.round(BUILD_TICKS / (float) size)), Math.max(1, (int) Math.ceil(size / (float) BUILD_TICKS)), new int[1]));
        return 1;
    }

    private static final int BUILD_TICKS = 50;
    private static final List<Job> JOBS = new ArrayList<>();

    private record Job(ServerLevel level, ArrayDeque<MultiblockPattern.Placement> left, List<MultiblockPattern.Placement> waiting,
                       BlockPos core, BlockState coreState, int every, int per, int[] clock) {}

    /** Lays a few blocks of every machine being built each tick, each with its placing sound and a puff of its dust. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) return;
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job job = it.next();
            ServerLevel level = job.level();
            if (job.clock()[0]++ % job.every() != 0) continue;
            for (int n = 0; n < job.per() && !job.left().isEmpty(); n++) {
                MultiblockPattern.Placement next = job.left().poll();
                if (!next.state().canSurvive(level, next.pos())) { // what holds it up comes later
                    job.waiting().add(next);
                    n--;
                    continue;
                }
                lay(level, next.pos(), next.state());
            }
            if (!job.left().isEmpty()) continue;
            if (!job.waiting().isEmpty()) {
                StructureBuilderItem.place(level, job.waiting()); // hanging things, in as many rounds as they need
                job.waiting().clear();
                continue;
            }
            // stair corners join as a player's placement would have joined them
            for (BlockPos pos : BlockPos.betweenClosed(job.core().offset(-8, -8, -8), job.core().offset(8, 8, 8))) {
                BlockState here = level.getBlockState(pos);
                if (here.getBlock() instanceof StairBlock) {
                    BlockState joined = Block.updateFromNeighbourShapes(here, level, pos);
                    if (joined != here) level.setBlock(pos, joined, Block.UPDATE_CLIENTS);
                }
            }
            lay(level, job.core(), job.coreState());
            level.playSound(null, job.core(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.2F, 0.9F);
            if (level.getBlockEntity(job.core()) instanceof TieredCoreBlockEntity tiered) tiered.showcaseGrown();
            else if (level.getBlockEntity(job.core()) instanceof MultiblockCoreBlockEntity machine) machine.requestStructureCheck();
            it.remove();
        }
    }

    private static void lay(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (state.isAir()) return;
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1F) / 2F, sound.getPitch() * 0.8F);
        if (state.getFluidState().isEmpty()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    6, 0.3, 0.3, 0.3, 0.05);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        JOBS.clear();
    }
}
