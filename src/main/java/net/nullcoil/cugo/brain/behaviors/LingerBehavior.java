package net.nullcoil.cugo.brain.behaviors;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.brain.CugoBehavior;
import net.nullcoil.cugo.config.ConfigHandler;
import org.jetbrains.annotations.NotNull;

public class LingerBehavior implements CugoBehavior {
    private int timer;
    private final int maxDuration;

    public LingerBehavior(int seconds) {
        this.maxDuration = ConfigHandler.getConfig().lingerTime * 20;
        this.timer = maxDuration;
    }

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (timer > 0) {
            // Stop movement
            golem.getNavigation().stop();

            // Every 1-2 seconds, pick a new spot to look at
            if (timer % 30 == 0) {
                double rx = golem.getX() + level.getRandom().nextGaussian() * 4;
                double ry = golem.getEyeY() + level.getRandom().nextGaussian() * 2;
                double rz = golem.getZ() + level.getRandom().nextGaussian() * 4;
                golem.getLookControl().setLookAt(rx, ry, rz);
            }
            timer--;
        }
    }

    public boolean isFinished() {
        return timer <= 0;
    }

    public void reset() {
        this.timer = maxDuration;
    }
}