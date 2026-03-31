package net.nullcoil.cugo.mixin.coppergolemstatueblock;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.WeatheringCopperGolemStatueBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.Debug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WeatheringCopperGolemStatueBlock.class)
public class Cugo_WeatheringStatueRechargeMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void cugo$interceptWeatheringStatueUseItemOn(ItemStack itemStack, BlockState blockState, Level level, BlockPos blockPos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir) {
        boolean isAxe = itemStack.is(ItemTags.AXES) || itemStack.getItem() instanceof AxeItem;

        if (isAxe && ConfigHandler.getConfig().rechargeableStatues) {
            WeatheringCopperGolemStatueBlock block = (WeatheringCopperGolemStatueBlock) (Object) this;

            if(block.getAge() == WeatheringCopper.WeatherState.UNAFFECTED) {
                Debug.log("Preventing activation. Updating pose");
                ((CopperGolemStatueBlockAccessor) block).invokeUpdatePose(level, blockState, blockPos, player);

                cir.setReturnValue(InteractionResult.SUCCESS);
            }
        }
    }
}
