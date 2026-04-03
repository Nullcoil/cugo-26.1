package net.nullcoil.cugo.mixin.hostility;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.monster.Ravager;
import net.nullcoil.cugo.mixin.mob.MobTargetSelectorAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Ravager.class)
public class Cugo_RavagerTargetMixin {
    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void cugo$targetCugo(CallbackInfo ci) {
        Ravager self = (Ravager)(Object)this;
        ((MobTargetSelectorAccessor) self).cugo$getTargetSelector()
                .addGoal(3, new NearestAttackableTargetGoal<>(self, CopperGolem.class, true));
    }
}
