package net.nullcoil.cugo.brain.behaviors;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.attribute.CugoAttributes;
import net.nullcoil.cugo.brain.CugoBehavior;
import net.nullcoil.cugo.config.ConfigHandler;
import org.jetbrains.annotations.NotNull;

public class PassivePowerBehavior implements CugoBehavior {

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        // 1. Only run if the config allows passive charging
        if (!ConfigHandler.getConfig().passiveCharge) return;

        // 2. Check if he is currently in a "Bubble" using the logic from your main class
        if (BatteryBehavior.inChargeBubble(level, golem.blockPosition())) {
            applyPassiveSiphon(golem);
            if (level.getRandom().nextFloat() < 0.15f) {
                DustParticleOptions particle = new DustParticleOptions(0xff0000, 1.0f);
                level.sendParticles(particle,
                        golem.getRandomX(0.5), golem.getY(), golem.getRandomZ(0.5),
                        3, 0.1, 0.1, 0.1, 0.05);
            }
        }
    }

    private void applyPassiveSiphon(@NotNull CopperGolem golem) {
        double maxBattery = ConfigHandler.getConfig().batteryLife * 60 /*seconds per minute*/ * 20.0 /*ticks per sec*/;

        // We make passive charging slightly slower than "Docked" charging
        // to encourage the Golem to actually go dock when low.
        // Docked uses: maxBattery / (chargeTime * 20)
        // Passive uses: 1/4th of that speed
        double passiveRate = (maxBattery / (ConfigHandler.getConfig().chargeTime * 20)) * ConfigHandler.getConfig().passiveChargeRateMultiplier;

        double current = golem.getAttributeValue(CugoAttributes.BATTERY);

        if (current < maxBattery) {
            golem.getAttribute(CugoAttributes.BATTERY)
                    .setBaseValue(Math.min(current + passiveRate, maxBattery));
        }
    }
}