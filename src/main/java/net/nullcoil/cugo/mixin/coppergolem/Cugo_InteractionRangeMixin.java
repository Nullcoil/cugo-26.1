package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.config.ConfigHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public class Cugo_InteractionRangeMixin {
    @Inject(method = "getContainerInteractionRange", at = @At("HEAD"), cancellable = true)
    private void cugo$overrideInteractionRange(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue((double) Math.max(ConfigHandler.getConfig().xzInteractRange, ConfigHandler.getConfig().yInteractRange));
    }
}
