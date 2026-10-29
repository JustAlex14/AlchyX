package com.lealex.alchyx.item;

import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.MultiblockPatterns;
import com.lealex.alchyx.multiblock.ShellParts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Right-click a machine's core block to build the whole structure from its pattern JSON around it, exactly as a
 * player would (before the transformation wave). Works for every machine: it builds the pattern the core's block
 * entity names (a machine with tiers names the tier it stands at), or else the pattern whose "core" is the clicked
 * block.
 * <ul>
 *   <li>Creative: places everything for free.</li>
 *   <li>Survival: takes the blocks from the inventory (for a tag, whichever block of it the player carries; fluids
 *       cost a bucket and give the empty bucket back). Nothing is placed unless everything is there: it lists what's
 *       missing instead. Blocks already right are kept, the right block turned the wrong way is turned for free, and
 *       anything else in the way must be cleared by the player (it never breaks blocks).</li>
 * </ul>
 */
public class StructureBuilderItem extends Item implements MachineTool {

    public StructureBuilderItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos core = context.getClickedPos();
        Player player = context.getPlayer();
        if (level.isClientSide()) return InteractionResult.SUCCESS; // the server decides; just swing the arm
        if (player == null) return InteractionResult.FAIL;

        // The core's own pattern first: several patterns can share a core block (AlchyForbidden's altar, one per tier)
        MultiblockPattern pattern = level.getBlockEntity(core) instanceof com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity entity
                ? MultiblockPatterns.get(entity.patternId()) : null;
        if (pattern == null) pattern = MultiblockPatterns.forCore(level.getBlockState(core).getBlock());
        if (pattern == null) {
            tell(player, Component.literal("Not a machine core: right-click the core block of a machine"));
            return InteractionResult.FAIL;
        }
        List<MultiblockPattern.Placement> plan = pattern.blueprint(core, pattern.rotationFor(level.getBlockState(core)));
        boolean free = player.hasInfiniteMaterials();

        // Decide what goes where
        List<MultiblockPattern.Placement> todo = new ArrayList<>();
        Map<Item, Integer> needed = new LinkedHashMap<>(), returned = new LinkedHashMap<>();
        Map<Item, Integer> available = free ? Map.of() : countInventory(player);
        Map<Item, Integer> missing = new LinkedHashMap<>();
        BlockPos firstInTheWay = null;
        int inTheWay = 0;
        for (MultiblockPattern.Placement placement : plan) {
            BlockPos pos = placement.pos();
            BlockState current = level.getBlockState(pos);
            if (ShellParts.isPart(current) || placement.satisfiedBy(current)) continue; // formed, or already right
            if (free) {
                // Creative replaces what stands in the way, but never what nothing is meant to break (bedrock, a
                // barrier, the shell of a pocket room...): that would be gone for good
                if (!current.isAir() && current.getDestroySpeed(level, pos) < 0) {
                    inTheWay++;
                    if (firstInTheWay == null) firstInTheWay = pos;
                    continue;
                }
                todo.add(placement);
                continue;
            }
            if (!level.mayInteract(player, pos) || !player.mayBuild()) {
                inTheWay++;
                if (firstInTheWay == null) firstInTheWay = pos;
                continue;
            }
            // The right block turned the wrong way (or a slab that should be double): fix it, paying the difference
            BlockState same = null;
            for (BlockState option : placement.options()) if (option.getBlock() == current.getBlock()) same = option;
            if (same != null) {
                Map<Item, Integer> diff = cost(level, same, pos);
                cost(level, current, pos).forEach((item, n) -> diff.merge(item, -n, Integer::sum));
                diff.values().removeIf(n -> n <= 0);
                todo.add(new MultiblockPattern.Placement(pos, same));
                diff.forEach((item, n) -> needed.merge(item, n, Integer::sum));
                continue;
            }
            boolean empty = current.isAir() || (current.canBeReplaced() && current.getFluidState().isEmpty()) || current.is(Blocks.WATER) && placement.state().is(Blocks.WATER);
            if (!empty || placement.state().isAir()) { // never breaks a block: the player clears the way
                inTheWay++;
                if (firstInTheWay == null) firstInTheWay = pos;
                continue;
            }
            // The first option the player can afford (counting what earlier positions already took)
            BlockState chosen = null;
            Map<Item, Integer> chosenCost = null;
            for (BlockState option : placement.options()) {
                Map<Item, Integer> price = cost(level, option, pos);
                if (affordable(price, needed, available)) {
                    chosen = option;
                    chosenCost = price;
                    break;
                }
            }
            if (chosen == null) { // report the first option's items
                chosen = placement.state();
                chosenCost = cost(level, chosen, pos);
                chosenCost.forEach((item, n) -> missing.merge(item, n, Integer::sum));
            }
            chosenCost.forEach((item, n) -> needed.merge(item, n, Integer::sum));
            for (Item bucket : refunds(chosen)) returned.merge(bucket, 1, Integer::sum);
            todo.add(new MultiblockPattern.Placement(pos, chosen));
        }

        if (todo.isEmpty() && inTheWay == 0) {
            tell(player, Component.literal("Structure already complete"));
            return InteractionResult.SUCCESS;
        }
        if (inTheWay > 0) {
            tell(player, Component.literal(inTheWay + " block" + (inTheWay > 1 ? "s" : "") + " in the way (first at "
                    + firstInTheWay.getX() + " " + firstInTheWay.getY() + " " + firstInTheWay.getZ() + "): clear them first"));
            return InteractionResult.FAIL;
        }
        if (!missing.isEmpty()) {
            // Shortfall per item: what everything needs minus what the player has
            Map<Item, Integer> shortfall = new LinkedHashMap<>();
            for (Item item : missing.keySet()) {
                int lack = needed.getOrDefault(item, 0) - available.getOrDefault(item, 0);
                if (lack > 0) shortfall.put(item, lack);
            }
            MutableComponent text = Component.literal("Missing: ");
            int shown = 0;
            for (Map.Entry<Item, Integer> entry : shortfall.entrySet()) {
                if (shown == 4) {
                    text.append(" and " + (shortfall.size() - 4) + " more");
                    break;
                }
                if (shown > 0) text.append(", ");
                text.append(entry.getValue() + " ").append(new ItemStack(entry.getKey()).getHoverName());
                shown++;
            }
            tell(player, text);
            return InteractionResult.FAIL;
        }

        if (!free) {
            needed.forEach((item, n) -> take(player, item, n));
            returned.forEach((item, n) -> player.getInventory().placeItemBackInInventory(new ItemStack(item, n)));
        }
        int placed = place(level, todo);
        level.playSound(null, context.getClickedPos(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0F, 1.2F);
        tell(player, Component.literal("Placed " + placed + " blocks"));
        return InteractionResult.SUCCESS;
    }

    /** Places the blocks: plain ones first, then stairs, then blocks needing support, then joins stair corners. */
    public static int place(Level level, List<MultiblockPattern.Placement> plan) {
        int placed = 0;
        // Blocks that need support (hanging lanterns and dripstone, candles...) wait until what holds them is there
        List<MultiblockPattern.Placement> needSupport = new ArrayList<>();
        // Everything except stairs first, then stairs, so the stairs find their neighbors when they join up
        for (boolean stairsPass : new boolean[]{false, true}) {
            for (MultiblockPattern.Placement placement : plan) {
                if ((placement.state().getBlock() instanceof StairBlock) != stairsPass) continue;
                if (!placement.state().canSurvive(level, placement.pos())) {
                    needSupport.add(placement);
                    continue;
                }
                level.setBlock(placement.pos(), placement.state(), Block.UPDATE_ALL);
                placed++;
            }
        }
        // Then the supported blocks, in as many rounds as needed (a block can hold up the next one)
        while (!needSupport.isEmpty()) {
            boolean progress = false;
            for (var iterator = needSupport.iterator(); iterator.hasNext(); ) {
                MultiblockPattern.Placement placement = iterator.next();
                if (placement.state().canSurvive(level, placement.pos())) {
                    level.setBlock(placement.pos(), placement.state(), Block.UPDATE_ALL);
                    placed++;
                    iterator.remove();
                    progress = true;
                }
            }
            if (!progress) {
                // Nothing in the pattern holds the rest up: place them anyway, as before
                for (MultiblockPattern.Placement placement : needSupport) {
                    level.setBlock(placement.pos(), placement.state(), Block.UPDATE_ALL);
                    placed++;
                }
                break;
            }
        }
        // setBlock doesn't run the "join with neighbors" logic a player's placement does: do it now for the stairs,
        // which turns the roof corners into outer corners
        for (MultiblockPattern.Placement placement : plan) {
            BlockState here = level.getBlockState(placement.pos());
            if (here.getBlock() instanceof StairBlock) {
                BlockState joined = Block.updateFromNeighbourShapes(here, level, placement.pos());
                if (joined != here) level.setBlock(placement.pos(), joined, Block.UPDATE_CLIENTS);
            }
        }
        return placed;
    }

    /** The items a player spends to place {@code state}: its item (times candles, pickles, a double slab...), buckets. */
    private static Map<Item, Integer> cost(Level level, BlockState state, BlockPos pos) {
        Map<Item, Integer> price = new LinkedHashMap<>();
        if (state.isAir()) return price;
        if (state.getBlock() instanceof LiquidBlock liquid) {
            Item bucket = liquid.fluid.getBucket();
            if (bucket != Items.AIR) price.put(bucket, 1);
            return price;
        }
        if (state.getBlock() instanceof AbstractCauldronBlock && !state.is(Blocks.CAULDRON)) {
            price.put(Items.CAULDRON, 1);
            if (state.is(Blocks.WATER_CAULDRON)) price.put(Items.WATER_BUCKET, 1);
            else if (state.is(Blocks.LAVA_CAULDRON)) price.put(Items.LAVA_BUCKET, 1);
            else if (state.is(Blocks.POWDER_SNOW_CAULDRON)) price.put(Items.POWDER_SNOW_BUCKET, 1);
            return price;
        }
        Item item = state.getCloneItemStack(level, pos, false).getItem();
        if (item == Items.AIR) item = state.getBlock().asItem();
        if (item == Items.AIR) return price; // nothing a player could hold (fire...): free
        int count = 1;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) count = 2;
        for (IntegerProperty several : new IntegerProperty[]{BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS}) {
            if (state.hasProperty(several)) count = state.getValue(several);
        }
        price.put(item, count);
        return price;
    }

    /** Empty buckets given back for the fluids placed. */
    private static List<Item> refunds(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock liquid && liquid.fluid.getBucket() != Items.AIR) return List.of(Items.BUCKET);
        if (state.getBlock() instanceof AbstractCauldronBlock && !state.is(Blocks.CAULDRON)) return List.of(Items.BUCKET);
        return List.of();
    }

    private static boolean affordable(Map<Item, Integer> price, Map<Item, Integer> alreadyNeeded, Map<Item, Integer> available) {
        for (Map.Entry<Item, Integer> entry : price.entrySet()) {
            int total = alreadyNeeded.getOrDefault(entry.getKey(), 0) + entry.getValue();
            if (total > available.getOrDefault(entry.getKey(), 0)) return false;
        }
        return true;
    }

    /** How many of each item the player carries (main inventory and hotbar, not armor). */
    private static Map<Item, Integer> countInventory(Player player) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static void take(Player player, Item item, int count) {
        var items = player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size() && count > 0; i++) {
            ItemStack stack = items.get(i);
            if (!stack.is(item)) continue;
            int used = Math.min(count, stack.getCount());
            stack.shrink(used);
            count -= used;
        }
        player.getInventory().setChanged();
    }

    private static void tell(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true); // action bar
        }
    }
}
