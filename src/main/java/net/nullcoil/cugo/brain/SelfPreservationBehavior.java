package net.nullcoil.cugo.brain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.WeatheringCopper;
import net.nullcoil.cugo.util.CugoWeatheringAccessor;
import net.nullcoil.cugo.config.ConfigHandler; // Added import
import org.jetbrains.annotations.NotNull;

public class SelfPreservationBehavior implements CugoBehavior {
    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        ItemStack hand = golem.getMainHandItem();
        CugoWeatheringAccessor weathering = (CugoWeatheringAccessor) golem;

        if (hand.is(Items.HONEYCOMB) && !weathering.cugo$isWaxed()) {
            // Apply Wax
            level.levelEvent(null, 3003, golem.blockPosition(), 0);
            weathering.cugo$setWaxed(true);

            if(ConfigHandler.getConfig().consumeConsumables) hand.shrink(1);

            golem.playSound(SoundEvents.HONEYCOMB_WAX_ON, 1.0f, 1.0f);
        }
        else if (hand.is(ItemTags.AXES)) {
            // Safety check: Don't use the axe if it's on its last legs
            if (hand.getDamageValue() < hand.getMaxDamage() - 1) {
                boolean scraped = false;

                if (weathering.cugo$isWaxed()) {
                    weathering.cugo$setWaxed(false);
                    level.levelEvent(null, 3004, golem.blockPosition(), 0);
                    scraped = true;
                } else if (golem.getWeatherState() != WeatheringCopper.WeatherState.UNAFFECTED) {
                    golem.setWeatherState(golem.getWeatherState().previous());
                    level.levelEvent(null, 3005, golem.blockPosition(), 0);
                    scraped = true;
                }

                if (scraped) {
                    golem.playSound(SoundEvents.AXE_SCRAPE, 1.0f, 1.0f);

                    // ── CONFIG CHECK ─────────────────────────────────────────
                    if (ConfigHandler.getConfig().damageItemsOnUse) {
                        // Damages the axe by 1. The lambda handles what happens if it DOES break.
                        hand.hurtAndBreak(1, level, null, (item) -> {
                            // Optional: golem.broadcastBreakEvent(InteractionHand.MAIN_HAND);
                        });
                    }
                    // ─────────────────────────────────────────────────────────
                }
            }
        }
    }

    public boolean canPerform(@NotNull CopperGolem golem) {
        CugoWeatheringAccessor weathering = (CugoWeatheringAccessor) golem;
        ItemStack hand = golem.getMainHandItem();

        if (hand.is(Items.HONEYCOMB) && !weathering.cugo$isWaxed()) return true;

        if (hand.is(ItemTags.AXES) && !weathering.cugo$isWaxed()) {
            return golem.getWeatherState() != WeatheringCopper.WeatherState.UNAFFECTED;
        }

        return false;
    }
}