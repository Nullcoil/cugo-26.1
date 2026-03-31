package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.brain.CugoBrain;
import net.nullcoil.cugo.util.CugoBrainAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CopperGolem.class)
public class Cugo_BrainTransplant implements CugoBrainAccessor {
    @Unique
    private CugoBrain cugo$brain;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cugo$initBrain(CallbackInfo ci) {
        this.cugo$brain = new CugoBrain();
        if(this.cugo$brain instanceof CugoBrain brain) {
            brain.onAttach((CopperGolem) (Object) this);
        }
    }

    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void cugo$replaceAiStep(ServerLevel level, CallbackInfo ci) {
        if (this.cugo$brain != null) {
            this.cugo$brain.tick((CopperGolem) (Object) this, level);
        }
        ci.cancel();
    }

    @Override
    public CugoBrain cugo$getBrain() { return this.cugo$brain; }

}
