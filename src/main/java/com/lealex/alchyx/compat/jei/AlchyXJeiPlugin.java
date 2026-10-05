package com.lealex.alchyx.compat.jei;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.machine.MachineRecipe;
import com.lealex.alchyx.machine.MachineRecipes;
import com.lealex.alchyx.machine.MachineSpec;
import com.lealex.alchyx.machine.ScriptedCoreBlock;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEI pages for script-defined machines (one per recipe type, the cores that use it as catalysts). Recipes come
 * from the server ({@link MachineRecipes#client()}), or straight from the integrated server in single player.
 */
@JeiPlugin
public class AlchyXJeiPlugin implements IModPlugin {
    private static final Identifier UID = AlchyX.id("jei");
    private static final int TEXT = 0xFF404040;

    /** Recipe type -> the cores using it (from the registered ScriptedCoreBlocks). */
    private static Map<Identifier, List<ScriptedCoreBlock>> machines() {
        Map<Identifier, List<ScriptedCoreBlock>> machines = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof ScriptedCoreBlock core) {
                machines.computeIfAbsent(core.spec().recipeType(), id -> new ArrayList<>()).add(core);
            }
        }
        return machines;
    }

    private static IRecipeType<MachineRecipe> typeOf(Identifier recipeType) {
        return IRecipeType.create(recipeType.getNamespace(), recipeType.getPath(), MachineRecipe.class);
    }

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    // Upgrade tabs (any mod's machine menus implementing UpgradeMenu) stick out right of the window: keep JEI's list off them
    @Override
    public void registerGuiHandlers(mezz.jei.api.registration.IGuiHandlerRegistration registration) {
        registration.addGenericGuiContainerHandler(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class,
                new mezz.jei.api.gui.handlers.IGuiContainerHandler<net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>>() {
                    @Override
                    public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(
                            net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
                        if (!(screen.getMenu() instanceof com.lealex.alchyx.menu.UpgradeMenu)) return java.util.List.of();
                        return java.util.List.of(com.lealex.alchyx.client.UpgradeTab.area(screen.getMenu(), screen.getLeftPos(), screen.getTopPos()));
                    }
                });
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper gui = registration.getJeiHelpers().getGuiHelper();
        for (Map.Entry<Identifier, List<ScriptedCoreBlock>> machine : machines().entrySet()) {
            registration.addRecipeCategories(new Category(gui, machine.getKey(), machine.getValue().getFirst()));
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        Map<Identifier, MachineRecipe> recipes = MachineRecipes.client().isEmpty() ? MachineRecipes.all() : MachineRecipes.client();
        Map<Identifier, List<MachineRecipe>> byType = new LinkedHashMap<>();
        for (MachineRecipe recipe : recipes.values()) byType.computeIfAbsent(recipe.machine(), id -> new ArrayList<>()).add(recipe);
        for (Identifier type : machines().keySet()) {
            registration.addRecipes(typeOf(type), byType.getOrDefault(type, List.of()));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (Map.Entry<Identifier, List<ScriptedCoreBlock>> machine : machines().entrySet()) {
            for (ScriptedCoreBlock core : machine.getValue()) {
                registration.addCraftingStation(typeOf(machine.getKey()), core);
            }
        }
    }

    /**
     * Inputs (items then fluids) on the left, an arrow, outputs on the right; time and energy as text. All slots use
     * the standard 18 px frame (JEI's big output frame is 26 px and would overlap neighbors spaced 18 px apart).
     */
    private static class Category extends AbstractRecipeCategory<MachineRecipe> {
        private static final int SLOT = 18, ROW_CAP = 3;

        Category(IGuiHelper gui, Identifier type, ScriptedCoreBlock core) {
            super(typeOf(type), core.getName(), gui.createDrawableItemLike(core), 150, 64);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, MachineRecipe recipe, IFocusGroup focuses) {
            int i = 0;
            for (MachineRecipe.ItemInput input : recipe.inputs()) {
                List<ItemStack> stacks = input.ingredient().items().map(item -> new ItemStack(item, input.count())).toList();
                builder.addInputSlot(2 + (i % ROW_CAP) * SLOT, 2 + (i / ROW_CAP) * SLOT).setStandardSlotBackground().addItemStacks(stacks);
                i++;
            }
            for (MachineRecipe.FluidAmount fluid : recipe.fluidInputs()) {
                builder.addInputSlot(2 + (i % ROW_CAP) * SLOT, 2 + (i / ROW_CAP) * SLOT).setStandardSlotBackground()
                        .add(fluid.fluid(), fluid.amount()).setFluidRenderer(fluid.amount(), false, 16, 16);
                i++;
            }
            int o = 0;
            for (MachineRecipe.ItemOutput output : recipe.outputs()) {
                var slot = builder.addOutputSlot(96 + (o % ROW_CAP) * SLOT, 2 + (o / ROW_CAP) * SLOT).setStandardSlotBackground().add(output.toStack());
                if (output.chance() < 1.0F) {
                    String chance = Math.round(output.chance() * 100) + "% chance";
                    slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(net.minecraft.network.chat.Component.literal(chance)));
                }
                o++;
            }
            for (MachineRecipe.FluidAmount fluid : recipe.fluidOutputs()) {
                builder.addOutputSlot(96 + (o % ROW_CAP) * SLOT, 2 + (o / ROW_CAP) * SLOT).setStandardSlotBackground()
                        .add(fluid.fluid(), fluid.amount()).setFluidRenderer(fluid.amount(), false, 16, 16);
                o++;
            }
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, MachineRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrowWidget().setPosition(64, 20);
        }

        @Override
        public void draw(MachineRecipe recipe, IRecipeSlotsView slots, GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
            int ticks = recipe.time();
            String text = (ticks % 20 == 0 ? ticks / 20 + " s" : String.format("%.1f s", ticks / 20.0))
                    + (recipe.energy() > 0 ? " · " + recipe.energy() + " FE" : "");
            graphics.text(Minecraft.getInstance().font, text, 2, 56, TEXT, false);
        }
    }
}
