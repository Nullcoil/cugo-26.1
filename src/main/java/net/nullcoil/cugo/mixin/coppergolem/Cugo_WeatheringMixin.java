package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.nullcoil.cugo.util.CugoWeatheringAccessor;
import net.nullcoil.cugo.util.Dev;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public abstract class Cugo_WeatheringMixin extends Entity implements CugoWeatheringAccessor {
    @Shadow public abstract WeatheringCopper.WeatherState getWeatherState();
    @Shadow public abstract void setWeatherState(WeatheringCopper.WeatherState state);

    @Unique
    private boolean isDying = false;
    @Unique
    private int shutdownTimer = 0;

    // Fixed: Registration should point to the target Entity class, not the Mixin class
    @Unique
    private static final EntityDataAccessor<Boolean> IS_WAXED = SynchedEntityData.defineId(CopperGolem.class, EntityDataSerializers.BOOLEAN);

    public Cugo_WeatheringMixin(EntityType<?> entityType, Level level) { super(entityType, level); }

    @Inject(method = "canTurnToStatue", at = @At("HEAD"), cancellable = true)
    private void cugo$disableVanillaStatueRNG(Level level, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cugo$tickWeathering(CallbackInfo ci) {
        if (this.level().isClientSide()) return;

        if (isDying) {
            handleShutdownSequence();
            return;
        }

        if (cugo$isWaxed()) return;

        if (getWeatherState() == WeatheringCopper.WeatherState.OXIDIZED) {
            return;
        }

        if (this.tickCount % 20 == 0) {
            if (this.random.nextFloat() < 0.000833f) {
                Dev.log("Natural Oxidation Roll Passed! Advancing Stage from " + getWeatherState());
                cugo$advanceStage();
            }
        }

        // Removed the stray shutdownTimer check here since it's handled in the sequence logic
    }

    @Unique
    private void handleShutdownSequence() {
        shutdownTimer++;
        CopperGolem self = (CopperGolem) (Object) this;
        self.getNavigation().stop();

        if (shutdownTimer % 20 == 0) {
            this.playSound(SoundEvents.COPPER_GOLEM_STEP, 1.0f, 0.5f);
            for(int i=0; i<5; i++) {
                this.level().addParticle(ParticleTypes.SCRAPE, this.getRandomX(0.5), this.getRandomY(), this.getRandomZ(0.5), 0, 0, 0);
            }
        }

        if (shutdownTimer > 100) {
            Dev.log("System halted. Converting to statue");
            cugo$convertToStatue(false);
        }
    }

    @Override
    public void cugo$startShutdown() {
        if (!this.isDying) {
            Dev.log("Shutdown sequence triggered via accessor.");
            this.isDying = true;
            this.shutdownTimer = 0;
        }
    }

    // --- SAVE / LOAD LOGIC (Fixed to use lowercase) ---

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void cugo$saveWeathering(ValueOutput valueOutput, CallbackInfo ci) {
        valueOutput.putBoolean("is_dying", this.isDying);
        valueOutput.putInt("shutdown_timer", this.shutdownTimer);
        valueOutput.putBoolean("waxed", this.entityData.get(IS_WAXED));
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void cugo$loadWeathering(ValueInput valueInput, CallbackInfo ci) {
        this.isDying = valueInput.getBooleanOr("is_dying", false);
        this.shutdownTimer = valueInput.getIntOr("shutdown_timer", 0);
        this.entityData.set(IS_WAXED, valueInput.getBooleanOr("waxed", false));
    }

    // --- DATA SYNC ---

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void cugo$defineWaxData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(IS_WAXED, false);
    }

    // --- UTILITY METHODS ---

    @Unique
    private void cugo$advanceStage() {
        WeatheringCopper.WeatherState current = getWeatherState();
        switch(current) {
            case UNAFFECTED -> setWeatherState(WeatheringCopper.WeatherState.EXPOSED);
            case EXPOSED -> setWeatherState(WeatheringCopper.WeatherState.WEATHERED);
            case WEATHERED -> setWeatherState(WeatheringCopper.WeatherState.OXIDIZED);
        }
    }

    @Override public WeatheringCopper.WeatherState cugo$getWeatherState() { return getWeatherState(); }
    @Override public void cugo$setWeatherState(WeatheringCopper.WeatherState state) { setWeatherState(state); }
    @Override public boolean cugo$isWaxed() { return this.entityData.get(IS_WAXED); }
    @Override public void cugo$setWaxed(boolean waxed) { this.entityData.set(IS_WAXED, waxed); }

    @Override
    public WeatheringCopper.WeatherState cugo$getPreviousWeatherState() {
        WeatheringCopper.WeatherState current = this.getWeatherState();
        return switch (current) {
            case UNAFFECTED, EXPOSED -> WeatheringCopper.WeatherState.UNAFFECTED;
            case WEATHERED -> WeatheringCopper.WeatherState.EXPOSED;
            case OXIDIZED -> WeatheringCopper.WeatherState.WEATHERED;
        };
    }

    @Unique
    public void cugo$convertToStatue(boolean randomizePose) {
        if(this.isRemoved()) return;

        CopperGolem self = (CopperGolem) (Object) this;
        if (!self.getMainHandItem().isEmpty()) {
            this.spawnAtLocation((ServerLevel) this.level(), self.getMainHandItem());
            self.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        BlockPos pos = this.blockPosition();

        if(!this.level().getBlockState(pos).canBeReplaced()) pos = pos.above();
        Block statueBlock = cugo$getStatueBlock();
        if (statueBlock == null) return;

        BlockState state = statueBlock.defaultBlockState().setValue(CopperGolemStatueBlock.FACING, this.getDirection());
        if (randomizePose) {
            CopperGolemStatueBlock.Pose[] poses = CopperGolemStatueBlock.Pose.values();
            state = state.setValue(CopperGolemStatueBlock.POSE, poses[this.random.nextInt(poses.length)]);
        }

        this.level().setBlock(pos, state, 3);
        this.level().gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(this, state));
        this.level().playSound(null, pos, SoundEvents.COPPER_GOLEM_BECOME_STATUE, this.getSoundSource(), 1.0f, 1.0f);
        this.discard();
    }

    @Unique
    private Block cugo$getStatueBlock() {
        WeatheringCopper.WeatherState state = getWeatherState();
        boolean waxed = cugo$isWaxed();

        if(waxed) {
            return switch(state) {
                case UNAFFECTED -> Blocks.WAXED_COPPER_GOLEM_STATUE;
                case EXPOSED -> Blocks.WAXED_EXPOSED_COPPER_GOLEM_STATUE;
                case WEATHERED -> Blocks.WAXED_WEATHERED_COPPER_GOLEM_STATUE;
                case OXIDIZED -> Blocks.WAXED_OXIDIZED_COPPER_GOLEM_STATUE;
            };
        } else {
            return switch(state) {
                case UNAFFECTED -> Blocks.COPPER_GOLEM_STATUE;
                case EXPOSED -> Blocks.EXPOSED_COPPER_GOLEM_STATUE;
                case WEATHERED -> Blocks.WEATHERED_COPPER_GOLEM_STATUE;
                case OXIDIZED -> Blocks.OXIDIZED_COPPER_GOLEM_STATUE;
            };
        }
    }
}