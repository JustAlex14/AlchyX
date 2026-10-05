package com.lealex.alchyx.compat.kubejs;

import com.lealex.alchyx.machine.MachineRecipe;
import com.lealex.alchyx.machine.MachineRecipes;
import com.lealex.alchyx.machine.ScriptedCoreBlockEntity;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** A machine event about one operation: event.recipeId (the recipe file id) and event.recipe. */
public class MachineOperationKubeEvent extends MachineKubeEvent {
    private final Identifier recipeId;

    public MachineOperationKubeEvent(ScriptedCoreBlockEntity machine, Identifier recipeId) {
        super(machine);
        this.recipeId = recipeId;
    }

    public Identifier getRecipeId() {
        return recipeId;
    }

    public @Nullable MachineRecipe getRecipe() {
        return MachineRecipes.get(recipeId);
    }
}
