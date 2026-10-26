package com.lealex.alchyx.mixin;

import com.lealex.alchyx.client.HumanoidPoses;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of every humanoid model's setupAnim: client/HumanoidPoses' hooks pose the limbs further. Optional
 * (require = 0): if a game update moves the method, the mod still loads and the hooks simply stop posing.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidPoseMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"), require = 0)
    private void alchyx$pose(HumanoidRenderState state, CallbackInfo info) {
        HumanoidPoses.apply((HumanoidModel<?>) (Object) this, state);
    }
}
