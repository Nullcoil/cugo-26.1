package net.nullcoil.cugo.mixin.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.Dev;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public class PoweredBlockMixin {
    @Inject(method = "stepOn", at = @At("HEAD"), cancellable = true)
    public void cugo$cugoStep(Level level, BlockPos pos, BlockState state, Entity entity, CallbackInfo ci) {
        if(level.getBlockState(pos).is(Blocks.REDSTONE_BLOCK) && ConfigHandler.getConfig().redstoneBoost && entity instanceof CopperGolem golem) {
            golem.addEffect(new MobEffectInstance(MobEffects.SPEED, 80, 0, false, true, false));
        }
        ci.cancel();
    }
}
