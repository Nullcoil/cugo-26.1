package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.Debug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CopperGolem.class)
public class Cugo_SpawnStackTrace {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cugo$debugSpawn(EntityType entityType, Level level, CallbackInfo ci) {
        if (!level.isClientSide() && ConfigHandler.getConfig().debugMode) {
            Debug.log("====== COPPER GOLEM CREATED ======");
            Debug.log("Stack trace: ");
            Thread.dumpStack();
        }
    }
}
