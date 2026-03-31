package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.brain.CugoBrain;
import net.nullcoil.cugo.util.CugoBrainAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CopperGolem.class)
public class Cugo_PainMixin {
    @Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void cugo$notifyBrainOfPain(ServerLevel level, DamageSource src, float amount, CallbackInfo ci) {
        CugoBrain brain = ((CugoBrainAccessor)this).cugo$getBrain();
        if (brain != null) {
            brain.onPhysicalDamage(src);
        }
    }
}
