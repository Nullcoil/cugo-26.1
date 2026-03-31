package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WeatheringCopper;
import net.nullcoil.cugo.util.CugoWeatheringAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public abstract class Cugo_InteractionMixin extends AbstractGolem {

    protected Cugo_InteractionMixin(EntityType<? extends AbstractGolem> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void cugo$handleItemInteraction(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        CopperGolem golem = (CopperGolem) (Object) this;
        ItemStack playerItem = player.getItemInHand(hand);
        ItemStack golemItem = golem.getMainHandItem();
        CugoWeatheringAccessor weathering = (CugoWeatheringAccessor) golem;

        // A. REPAIR (Copper Ingot)
        if (playerItem.is(Items.COPPER_INGOT) && golem.getHealth() < golem.getMaxHealth()) {
            if (!this.level().isClientSide()) {
                golem.heal(golem.getMaxHealth() * 0.25f);
                if (!player.getAbilities().instabuild) playerItem.shrink(1);
                this.level().broadcastEntityEvent(golem, (byte)18);
                golem.playSound(SoundEvents.IRON_GOLEM_REPAIR, 1.0f, 1.0f);
            }
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // B. DIRECT MAINTENANCE (Utility items used BY player ON golem)
        // Only happens if NOT sneaking. If sneaking, we skip to "Giving" logic.
        if (!player.isShiftKeyDown()) {
            // Waxing
            if (playerItem.is(Items.HONEYCOMB) && !weathering.cugo$isWaxed()) {
                if (!this.level().isClientSide()) {
                    weathering.cugo$setWaxed(true);
                    if (!player.getAbilities().instabuild) playerItem.shrink(1);
                    this.level().levelEvent(null, 3003, golem.blockPosition(), 0);
                    golem.playSound(SoundEvents.HONEYCOMB_WAX_ON, 1.0f, 1.0f);
                }
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
            // Scraping (Axe)
            if (playerItem.is(ItemTags.AXES) && (weathering.cugo$isWaxed() || golem.getWeatherState() != WeatheringCopper.WeatherState.UNAFFECTED)) {
                if (!this.level().isClientSide()) {
                    if (weathering.cugo$isWaxed()) {
                        weathering.cugo$setWaxed(false);
                        this.level().levelEvent(null, 3004, golem.blockPosition(), 0);
                    } else {
                        golem.setWeatherState(golem.getWeatherState().previous());
                        this.level().levelEvent(null, 3005, golem.blockPosition(), 0);
                    }
                    playerItem.hurtAndBreak(1, player, hand.asEquipmentSlot());
                    golem.playSound(SoundEvents.AXE_SCRAPE, 1.0f, 1.0f);
                }
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
        }

        // C. ITEM MANAGEMENT (Taking and Giving)

        // 1. Take Item from Golem (Empty hand)
        if (playerItem.isEmpty() && !golemItem.isEmpty()) {
            if (!this.level().isClientSide()) {
                player.setItemInHand(hand, golemItem.copy());
                golem.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                golem.playSound(SoundEvents.ITEM_PICKUP, 1.0f, 1.0f);
            }
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // 2. Give Item to Golem (Golem has empty hand)
        if (!playerItem.isEmpty() && golemItem.isEmpty()) {
            if (!this.level().isClientSide()) {
                ItemStack toGive = playerItem.copy();

                // Logic: 1 from stack if normal click, Full stack if sneaking
                if (!player.isShiftKeyDown()) {
                    toGive.setCount(1);
                    playerItem.shrink(1);
                } else {
                    playerItem.setCount(0);
                }

                golem.setItemInHand(InteractionHand.MAIN_HAND, toGive);
                golem.playSound(SoundEvents.ITEM_PICKUP, 1.0f, 1.0f);
            }
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // 3. Drop Item (If player clicks a Golem that is holding something, but player also has an item)
        // This allows the "Drop item on ground" behavior you requested.
        if (!playerItem.isEmpty() && !golemItem.isEmpty()) {
            if (!this.level().isClientSide()) {
                golem.spawnAtLocation((ServerLevel)this.level(), golemItem.copy());
                golem.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                golem.playSound(SoundEvents.ITEM_PICKUP, 0.5f, 0.5f);
            }
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}