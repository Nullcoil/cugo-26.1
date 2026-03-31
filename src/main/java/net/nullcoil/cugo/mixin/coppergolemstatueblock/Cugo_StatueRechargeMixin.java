package net.nullcoil.cugo.mixin.coppergolemstatueblock;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoWeatheringAccessor;
import net.nullcoil.cugo.util.Dev;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolemStatueBlock.class)
public abstract class Cugo_StatueRechargeMixin extends Block {

    public Cugo_StatueRechargeMixin(Properties properties) {
        super(properties);
    }

    @Shadow
    protected abstract void updatePose(Level level, BlockState blockState, BlockPos blockPos, Player player);

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void cugo$interceptUseItemOn(ItemStack itemStack, BlockState blockState, Level level, BlockPos blockPos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir) {

        boolean isAxe = itemStack.is(ItemTags.AXES) || itemStack.getItem() instanceof AxeItem;

        if (isAxe && ConfigHandler.getConfig().rechargeableStatues) {
            CopperGolemStatueBlock statueBlock = (CopperGolemStatueBlock)(Object)this;

            // Check if this specific block is a waxed variant
            Block block = blockState.getBlock();
            boolean isWaxed = block == Blocks.WAXED_COPPER_GOLEM_STATUE ||
                    block == Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE ||
                    block == Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE ||
                    block == Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE;

            // Only block the axe if it's UNWAXED and UNAFFECTED
            if (!isWaxed && statueBlock.getWeatheringState() == WeatheringCopper.WeatherState.UNAFFECTED) {
                System.out.println("[CuGO-BLOCK] *** UPDATING POSE ***");
                this.updatePose(level, blockState, blockPos, player);
                cir.setReturnValue(InteractionResult.SUCCESS);
            }
        }
    }

    // --- REDSTONE RECHARGE ---
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);

        if (!level.isClientSide()) {
            boolean hasSignal = level.hasNeighborSignal(pos);
            if (hasSignal && ConfigHandler.getConfig().rechargeableStatues) {
                cugo$revertToGolem(level, pos, state);
            }
        }
    }

    @Unique
    private void cugo$revertToGolem(Level level, BlockPos pos, BlockState state) {
        Dev.log("Statue received redstone signal. Reverting to Golem at " + pos);

        CopperGolem golem = EntityType.COPPER_GOLEM.create(level, EntitySpawnReason.TRIGGERED);

        if (golem != null) {
            double x = pos.getX() + 0.5;
            double y = pos.getY();
            double z = pos.getZ() + 0.5;

            golem.setPos(x, y, z);

            if (state.hasProperty(CopperGolemStatueBlock.FACING)) {
                float yRot = state.getValue(CopperGolemStatueBlock.FACING).toYRot();
                golem.setYRot(yRot);
                golem.setYBodyRot(yRot);
                golem.setYHeadRot(yRot);
                golem.setYRot(yRot);
            }

            Block block = state.getBlock();
            CugoWeatheringAccessor weathering = (CugoWeatheringAccessor) golem;

            if (block == Blocks.OXIDIZED_COPPER_GOLEM_STATUE || block == Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE) {
                weathering.cugo$setWeatherState(WeatheringCopper.WeatherState.OXIDIZED);
            } else if (block == Blocks.WEATHERED_COPPER_GOLEM_STATUE || block == Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE) {
                weathering.cugo$setWeatherState(WeatheringCopper.WeatherState.WEATHERED);
            } else if (block == Blocks.EXPOSED_COPPER_GOLEM_STATUE || block == Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE) {
                weathering.cugo$setWeatherState(WeatheringCopper.WeatherState.EXPOSED);
            } else {
                weathering.cugo$setWeatherState(WeatheringCopper.WeatherState.UNAFFECTED);
            }

            boolean isWaxedBlock = block == Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE ||
                    block == Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE ||
                    block == Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE ||
                    block == Blocks.WAXED_COPPER_GOLEM_STATUE;

            if (isWaxedBlock) {
                weathering.cugo$setWaxed(true);
            }

            level.addFreshEntity(golem);
            level.destroyBlock(pos, false);
            level.playSound(null, pos, SoundEvents.COPPER_GOLEM_STEP, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }
}