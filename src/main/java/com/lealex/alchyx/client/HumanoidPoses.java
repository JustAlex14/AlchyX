package com.lealex.alchyx.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets a mod pose a humanoid model's limbs after the game has (an arm on a table, legs folded to sit...): each hook
 * runs at the end of every HumanoidModel.setupAnim (mixin/HumanoidPoseMixin), for the player model and the armour
 * worn over it alike, so armour follows. The game has no event there. A hook should read what to do from the render
 * state (render data set by a render state modifier) and leave the model alone otherwise.
 */
public final class HumanoidPoses {
    private HumanoidPoses() {}

    public interface Hook {
        void pose(HumanoidModel<?> model, HumanoidRenderState state);
    }

    private static final List<Hook> HOOKS = new CopyOnWriteArrayList<>();

    public static void register(Hook hook) {
        HOOKS.add(hook);
    }

    /** Called by the mixin. */
    public static void apply(HumanoidModel<?> model, HumanoidRenderState state) {
        for (Hook hook : HOOKS) hook.pose(model, state);
    }
}
