package net.nullcoil.cugo.mixin.hostility;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.nullcoil.cugo.mixin.mob.MobTargetSelectorAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractIllager.class)
public class Cugo_IllagerTargetMixin {
    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void cugo$targetCugo(CallbackInfo ci) {
        AbstractIllager self = (AbstractIllager) (Object)this;
        ((MobTargetSelectorAccessor) self).cugo$getTargetSelector()
                .addGoal(3, new NearestAttackableTargetGoal<>(self, CopperGolem.class, true));
    }
}
