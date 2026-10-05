package com.lealex.alchyx.machine;

import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.registry.AlchyXRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The block entity of every script-defined machine core. Its {@link MachineSpec} (from the block) sets the slots,
 * tanks and energy buffer; while formed it runs the first {@link MachineRecipe} of its recipe type whose inputs are
 * present and whose outputs fit. Feeds AlchyX's animation state (working, start/finish, displays "input",
 * "output", "result") and calls {@link MachineHooks} (KubeJS events).
 *
 * Public getters/setters below are meant for scripts (event.machine in AlchyXEvents).
 */
public class ScriptedCoreBlockEntity extends MultiblockCoreBlockEntity implements MenuProvider {
    public static final int DATA_COUNT = 6;

    private final MachineSpec spec;
    private final ItemStacksResourceHandler items;
    private final FluidStacksResourceHandler tanks;
    private final SimpleEnergyHandler energy;
    private final ResourceHandler<ItemResource> itemAutomation;
    private final ResourceHandler<FluidResource> fluidAutomation;

    private @Nullable Identifier currentRecipe;
    private int progress = 0;
    private boolean lookupDirty = true;   // inputs changed: look for a recipe again
    private int lookupGeneration = -1;

    private final ContainerData guiData = new ContainerData() {
        @Override
        public int get(int index) {
            MachineRecipe recipe = recipe();
            long stored = energy.getAmountAsLong(), capacity = energy.getCapacityAsLong();
            return switch (index) {
                case 0 -> progress;
                case 1 -> recipe == null ? 0 : recipe.time();
                case 2 -> (int) (stored & 0xFFFF);           // menu data is synced as 16-bit values
                case 3 -> (int) ((stored >>> 16) & 0xFFFF);
                case 4 -> (int) (capacity & 0xFFFF);
                case 5 -> (int) ((capacity >>> 16) & 0xFFFF);
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public ScriptedCoreBlockEntity(BlockPos pos, BlockState state) {
        super(AlchyXRegistries.SCRIPTED_CORE_BE.get(), pos, state);
        this.spec = specOf(state);
        this.items = createItems(spec, this::onItemsChanged);
        this.tanks = createTanks(spec, this::onTanksChanged);
        this.energy = new SimpleEnergyHandler(Math.max(1, spec.energyCapacity()), spec.usesEnergy() ? spec.energyInput() : 0, 0);
        this.itemAutomation = new ItemAutomation(items, spec.itemInputs());
        this.fluidAutomation = new FluidAutomation(tanks, spec.fluidInputs());
    }

    /** The spec of the block in this state (a placeholder spec if the block isn't a script core). */
    public static MachineSpec specOf(BlockState state) {
        if (state.getBlock() instanceof ScriptedCoreBlock core) return core.spec();
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new MachineSpec(id, id, 0, 0, 0, 0, 1, 0, 0);
    }

    /** Items: inputs first (players and pipes may insert), then outputs (filled by the machine). */
    public static ItemStacksResourceHandler createItems(MachineSpec spec, Runnable onChanged) {
        return new ItemStacksResourceHandler(Math.max(1, spec.itemSlots())) {
            @Override
            public boolean isValid(int index, ItemResource resource) {
                return index < spec.itemInputs();
            }

            @Override
            protected void onContentsChanged(int index, ItemStack previousContents) {
                onChanged.run();
            }
        };
    }

    /** Tanks: inputs first, then outputs. */
    public static FluidStacksResourceHandler createTanks(MachineSpec spec, Runnable onChanged) {
        return new FluidStacksResourceHandler(Math.max(1, spec.tanks()), spec.tankCapacity()) {
            @Override
            public boolean isValid(int index, FluidResource resource) {
                return index < spec.fluidInputs();
            }

            @Override
            protected void onContentsChanged(int index, net.neoforged.neoforge.fluids.FluidStack previousContents) {
                onChanged.run();
            }
        };
    }

    private void onItemsChanged() {
        lookupDirty = true;
        setChanged();
        if (spec.itemInputs() > 0) setAnimationDisplay("input", items.copyToList().get(0));
        if (spec.itemOutputs() > 0) setAnimationDisplay("output", items.copyToList().get(spec.itemInputs()));
    }

    private void onTanksChanged() {
        lookupDirty = true;
        setChanged();
        syncToClients(); // the GUI and scripts on the client see the tanks
    }

    // ---- MultiblockCoreBlockEntity ----

    @Override
    public Identifier patternId() {
        return spec.pattern();
    }

    @Override
    protected String machineName() {
        return getBlockState().getBlock().getName().getString();
    }

    @Override
    public ResourceHandler<ItemResource> getItemHandler() {
        return spec.itemSlots() > 0 ? itemAutomation : null;
    }

    @Override
    public ResourceHandler<FluidResource> getFluidHandler() {
        return spec.tanks() > 0 ? fluidAutomation : null;
    }

    @Override
    public EnergyHandler getEnergyHandler() {
        return spec.usesEnergy() ? energy : null;
    }

    @Override
    protected void onStructureChanged(boolean nowFormed) {
        if (nowFormed) {
            MachineHooks.get().formed(this);
        } else {
            resetOperation();
            MachineHooks.get().broken(this);
        }
    }

    // ---- Processing ----

    @Override
    protected void tickFormed() {
        if (level == null) return;
        MachineHooks.get().tick(this);
        MachineRecipe recipe = recipe();
        if (recipe == null) {
            if (lookupDirty || lookupGeneration != MachineRecipes.generation()) startNext();
            setAnimationWorking(false);
            return;
        }
        if (!inputsPresent(recipe) || !outputsFit(recipe)) {
            resetOperation();
            setAnimationWorking(false);
            return;
        }
        if (spec.usesEnergy() && recipe.energy() > 0) {
            long needed = (long) recipe.energy() * (progress + 1) / recipe.time() - (long) recipe.energy() * progress / recipe.time();
            if (energy.getAmountAsLong() < needed) {
                setAnimationWorking(false); // wait for power
                return;
            }
            energy.set((int) (energy.getAmountAsLong() - needed));
        }
        progress++;
        setAnimationWorking(true);
        if (progress >= recipe.time()) finish(recipe);
        setChanged();
    }

    /** Looks for the first recipe that can run and starts it (if no script cancels it). */
    private void startNext() {
        lookupDirty = false;
        lookupGeneration = MachineRecipes.generation();
        for (Map.Entry<Identifier, MachineRecipe> entry : MachineRecipes.forMachine(spec.recipeType())) {
            MachineRecipe recipe = entry.getValue();
            if (!inputsPresent(recipe) || !outputsFit(recipe)) continue;
            if (!MachineHooks.get().starting(this, entry.getKey())) return;
            currentRecipe = entry.getKey();
            progress = 0;
            startAnimationOperation(recipe.time());
            setAnimationDisplay("result", recipe.outputs().isEmpty() ? ItemStack.EMPTY : recipe.outputs().getFirst().toStack());
            setChanged();
            return;
        }
    }

    private void finish(MachineRecipe recipe) {
        if (level == null) return;
        Identifier id = currentRecipe;
        consumeInputs(recipe);
        for (MachineRecipe.ItemOutput output : recipe.outputs()) {
            if (output.chance() < 1.0F && level.getRandom().nextFloat() >= output.chance()) continue;
            addOutput(output.toStack());
        }
        for (MachineRecipe.FluidAmount output : recipe.fluidOutputs()) addFluidOutput(output);
        currentRecipe = null;
        progress = 0;
        lookupDirty = true;
        setAnimationDisplay("result", ItemStack.EMPTY);
        finishAnimationOperation();
        if (id != null) MachineHooks.get().finished(this, id);
    }

    private void resetOperation() {
        if (currentRecipe != null || progress != 0) {
            currentRecipe = null;
            progress = 0;
            lookupDirty = true;
            setAnimationDisplay("result", ItemStack.EMPTY);
            setChanged();
        }
    }

    private @Nullable MachineRecipe recipe() {
        return currentRecipe == null ? null : MachineRecipes.get(currentRecipe);
    }

    /** Each item input in its own input slot, each fluid input in its own input tank. */
    private boolean inputsPresent(MachineRecipe recipe) {
        return matchSlots(recipe) != null && matchTanks(recipe) != null;
    }

    private int @Nullable [] matchSlots(MachineRecipe recipe) {
        List<ItemStack> stacks = items.copyToList();
        boolean[] used = new boolean[spec.itemInputs()];
        int[] slots = new int[recipe.inputs().size()];
        for (int i = 0; i < recipe.inputs().size(); i++) {
            slots[i] = -1;
            for (int s = 0; s < spec.itemInputs(); s++) {
                if (!used[s] && recipe.inputs().get(i).test(stacks.get(s))) {
                    used[s] = true;
                    slots[i] = s;
                    break;
                }
            }
            if (slots[i] < 0) return null;
        }
        return slots;
    }

    private int @Nullable [] matchTanks(MachineRecipe recipe) {
        boolean[] used = new boolean[spec.fluidInputs()];
        int[] found = new int[recipe.fluidInputs().size()];
        for (int i = 0; i < recipe.fluidInputs().size(); i++) {
            MachineRecipe.FluidAmount wanted = recipe.fluidInputs().get(i);
            found[i] = -1;
            for (int t = 0; t < spec.fluidInputs(); t++) {
                if (!used[t] && tanks.getResource(t).getFluid() == wanted.fluid() && tanks.getAmountAsInt(t) >= wanted.amount()) {
                    used[t] = true;
                    found[i] = t;
                    break;
                }
            }
            if (found[i] < 0) return null;
        }
        return found;
    }

    /** Every output (counted as if all chances succeed) fits in the output slots and tanks. */
    private boolean outputsFit(MachineRecipe recipe) {
        List<ItemStack> outputs = new ArrayList<>();
        for (int s = 0; s < spec.itemOutputs(); s++) outputs.add(items.copyToList().get(spec.itemInputs() + s).copy());
        for (MachineRecipe.ItemOutput output : recipe.outputs()) {
            ItemStack stack = output.toStack();
            boolean placed = false;
            for (int s = 0; s < outputs.size() && !placed; s++) {
                ItemStack current = outputs.get(s);
                if (current.isEmpty()) {
                    outputs.set(s, stack.copy());
                    placed = true;
                } else if (ItemStack.isSameItemSameComponents(current, stack) && current.getCount() + stack.getCount() <= current.getMaxStackSize()) {
                    current.grow(stack.getCount());
                    placed = true;
                }
            }
            if (!placed) return false;
        }
        for (MachineRecipe.FluidAmount output : recipe.fluidOutputs()) {
            if (outputTankFor(output) < 0) return false;
        }
        return true;
    }

    private int outputTankFor(MachineRecipe.FluidAmount output) {
        for (int t = spec.fluidInputs(); t < spec.tanks(); t++) {
            FluidResource current = tanks.getResource(t);
            boolean same = current.isEmpty() || current.getFluid() == output.fluid();
            if (same && tanks.getAmountAsInt(t) + output.amount() <= spec.tankCapacity()) return t;
        }
        return -1;
    }

    private void consumeInputs(MachineRecipe recipe) {
        int[] slots = matchSlots(recipe);
        int[] found = matchTanks(recipe);
        if (slots == null || found == null) return;
        try (Transaction tx = Transaction.openRoot()) {
            for (int i = 0; i < slots.length; i++) {
                items.extract(slots[i], items.getResource(slots[i]), recipe.inputs().get(i).count(), tx);
            }
            for (int i = 0; i < found.length; i++) {
                tanks.extract(found[i], tanks.getResource(found[i]), recipe.fluidInputs().get(i).amount(), tx);
            }
            tx.commit();
        }
    }

    private void addOutput(ItemStack stack) {
        for (int s = spec.itemInputs(); s < spec.itemSlots(); s++) {
            ItemResource current = items.getResource(s);
            int amount = current.isEmpty() ? 0 : items.getAmountAsInt(s);
            if (current.isEmpty() || (current.equals(ItemResource.of(stack)) && amount + stack.getCount() <= stack.getMaxStackSize())) {
                items.set(s, ItemResource.of(stack), amount + stack.getCount()); // outputs bypass isValid
                return;
            }
        }
    }

    private void addFluidOutput(MachineRecipe.FluidAmount output) {
        int t = outputTankFor(output);
        if (t >= 0) tanks.set(t, FluidResource.of(output.fluid()), tanks.getAmountAsInt(t) + output.amount());
    }

    // ---- For scripts and the GUI ----

    public MachineSpec getSpec() {
        return spec;
    }

    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < spec.itemSlots() ? items.copyToList().get(slot) : ItemStack.EMPTY;
    }

    /** Scripts: replace a slot's contents (input or output). */
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= spec.itemSlots()) return;
        items.set(slot, ItemResource.of(stack), stack.getCount());
    }

    public FluidResource getFluid(int tank) {
        return tank >= 0 && tank < spec.tanks() ? tanks.getResource(tank) : FluidResource.EMPTY;
    }

    public int getFluidAmount(int tank) {
        return tank >= 0 && tank < spec.tanks() ? tanks.getAmountAsInt(tank) : 0;
    }

    public int getEnergy() {
        return (int) energy.getAmountAsLong();
    }

    /** Scripts: set the stored FE (clamped to the buffer). */
    public void setEnergy(int amount) {
        if (spec.usesEnergy()) energy.set(Math.clamp(amount, 0, spec.energyCapacity()));
    }

    public @Nullable Identifier getCurrentRecipe() {
        return currentRecipe;
    }

    public int getProgress() {
        return progress;
    }

    public boolean isWorking() {
        return isAnimationWorking();
    }

    // ---- GUI ----

    @Override
    public void openGui(Player player) {
        // Also before forming: the GUI then offers the structure preview (ghost blocks of what's missing)
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
        }
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MachineMenu(containerId, playerInventory, this, items, guiData);
    }

    public boolean stillValid(Player player) {
        return withinReach(player); // any block of the structure, not just the core
    }

    // ---- Breaking drops the items ----

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null || level.isClientSide()) return;
        for (ItemStack stack : items.copyToList()) {
            if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        }
    }

    // ---- Saving ----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        items.serialize(output.child("items"));
        tanks.serialize(output.child("tanks"));
        energy.serialize(output.child("energy"));
        if (currentRecipe != null) output.store("recipe", Identifier.CODEC, currentRecipe);
        output.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.deserialize(input.childOrEmpty("items"));
        tanks.deserialize(input.childOrEmpty("tanks"));
        energy.deserialize(input.childOrEmpty("energy"));
        currentRecipe = input.read("recipe", Identifier.CODEC).orElse(null);
        progress = input.getIntOr("progress", 0);
        lookupDirty = true;
    }

    // ---- Automation views: insert into inputs only, extract from outputs only ----

    private record ItemAutomation(ItemStacksResourceHandler inner, int inputs) implements ResourceHandler<ItemResource> {
        @Override public int size() { return inner.size(); }
        @Override public ItemResource getResource(int index) { return inner.getResource(index); }
        @Override public long getAmountAsLong(int index) { return inner.getAmountAsLong(index); }
        @Override public long getCapacityAsLong(int index, ItemResource resource) { return inner.getCapacityAsLong(index, resource); }
        @Override public boolean isValid(int index, ItemResource resource) { return index < inputs && inner.isValid(index, resource); }
        @Override public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index < inputs ? inner.insert(index, resource, amount, transaction) : 0;
        }
        @Override public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index >= inputs ? inner.extract(index, resource, amount, transaction) : 0;
        }
    }

    private record FluidAutomation(FluidStacksResourceHandler inner, int inputs) implements ResourceHandler<FluidResource> {
        @Override public int size() { return inner.size(); }
        @Override public FluidResource getResource(int index) { return inner.getResource(index); }
        @Override public long getAmountAsLong(int index) { return inner.getAmountAsLong(index); }
        @Override public long getCapacityAsLong(int index, FluidResource resource) { return inner.getCapacityAsLong(index, resource); }
        @Override public boolean isValid(int index, FluidResource resource) { return index < inputs && inner.isValid(index, resource); }
        @Override public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return index < inputs ? inner.insert(index, resource, amount, transaction) : 0;
        }
        @Override public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return index >= inputs ? inner.extract(index, resource, amount, transaction) : 0;
        }
    }
}
