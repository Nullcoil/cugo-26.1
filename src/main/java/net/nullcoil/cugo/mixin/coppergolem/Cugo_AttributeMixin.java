package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.nullcoil.cugo.attribute.CugoAttributes;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoWeatheringAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public class Cugo_AttributeMixin {

    @Inject(method = "createAttributes", at = @At("RETURN"), cancellable = true)
    private static void cugo$addBatteryAttribute(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
        cir.setReturnValue(
                cir.getReturnValue().add(
                        CugoAttributes.BATTERY,
                        12000.0 // 10 min * 60 sec * 20 ticks — hardcoded default, config applied at runtime
                )
        );
    }

    // Check battery every tick and convert to statue if dead
    @Inject(method = "tick", at = @At("HEAD"))
    private void cugo$checkBattery(CallbackInfo ci) {
        CopperGolem self = (CopperGolem) (Object) this;
        if (!ConfigHandler.getConfig().rechargeableGolems) return;
        if (self.level().isClientSide()) return;
        if (!self.isAlive()) return;

        var batteryAttr = self.getAttribute(CugoAttributes.BATTERY);
        if (batteryAttr == null) return;

        if (batteryAttr.getValue() <= 0) {
            ServerLevel serverLevel = (ServerLevel) self.level();

            // Mirror the wax state onto nextWeatheringTick before converting —
            // turnToStatue reads weatherState and facing directly from the entity,
            // so oxidation and facing are already correct. Wax is stored as
            // nextWeatheringTick == -2L internally, which is already on the entity.
            cugo$convertToStatue(self, serverLevel);
        }
    }

    @Unique
    private void cugo$convertToStatue(CopperGolem golem, ServerLevel level) {
        if (golem.isRemoved()) return;

        if (!golem.getMainHandItem().isEmpty()) {
            golem.spawnAtLocation(level, golem.getMainHandItem());
            golem.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        BlockPos pos = golem.blockPosition();
        // Ensure we aren't placing the statue inside a non-replaceable block (like a slab or fence)
        if (!level.getBlockState(pos).canBeReplaced()) {
            pos = pos.above();
        }

        // 1. Determine the correct block based on weathering and wax
        // Note: This assumes your CopperGolem class has the standard accessors for weather/wax
        Block statueBlock = cugo$getStatueBlockForEntity(golem);

        // 2. Build the BlockState
        BlockState statueState = statueBlock.defaultBlockState()
                .setValue(CopperGolemStatueBlock.FACING, golem.getDirection())
                .setValue(CopperGolemStatueBlock.POSE,
                        CopperGolemStatueBlock.Pose.values()[golem.getRandom().nextInt(CopperGolemStatueBlock.Pose.values().length)]
                );

        // 3. Place the block
        level.setBlock(pos, statueState, 3);

        // 4. Handle Data Transfer via BlockEntity
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CopperGolemStatueBlockEntity statue) {
            // This method usually handles copying the UUID, equipment, and states
            statue.createStatue(golem);

            // Finalize the entity removal
            golem.discard();

            // Play effects
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.COPPER_GOLEM_BECOME_STATUE,
                    golem.getSoundSource(), 1.0f, 1.0f);
            level.gameEvent(GameEvent.BLOCK_PLACE, pos,
                    GameEvent.Context.of(golem, statueState));
        }
    }

    @Unique
    private Block cugo$getStatueBlockForEntity(CopperGolem golem) {
        // You may need to cast 'golem' to your accessor interface if these aren't public
        var state = golem.getWeatherState();
        boolean waxed = ((CugoWeatheringAccessor)golem).cugo$isWaxed();

        if (waxed) {
            return switch (state) {
                case UNAFFECTED -> Blocks.WAXED_COPPER_GOLEM_STATUE;
                case EXPOSED -> Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE;
                case WEATHERED -> Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE;
                case OXIDIZED -> Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE;
            };
        } else {
            return switch (state) {
                case UNAFFECTED -> Blocks.COPPER_GOLEM_STATUE;
                case EXPOSED -> Blocks.EXPOSED_COPPER_GOLEM_STATUE;
                case WEATHERED -> Blocks.WEATHERED_COPPER_GOLEM_STATUE;
                case OXIDIZED -> Blocks.OXIDIZED_COPPER_GOLEM_STATUE;
            };
        }
    }
}