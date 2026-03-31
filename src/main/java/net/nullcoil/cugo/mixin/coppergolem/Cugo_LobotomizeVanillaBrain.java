package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.util.Debug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public class Cugo_LobotomizeVanillaBrain {
	@Inject(method = "makeBrain", at = @At("HEAD"), cancellable = true)
	private void cugo$lobotomize(Brain.Packed packedBrain, CallbackInfoReturnable<Brain<CopperGolem>> cir) {
        Debug.log("Copper Golem lobotomized");
		cir.setReturnValue(new Brain<>());
	}
}