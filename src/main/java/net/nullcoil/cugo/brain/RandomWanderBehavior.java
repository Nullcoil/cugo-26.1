package net.nullcoil.cugo.brain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.phys.Vec3;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.Dev;
import org.jetbrains.annotations.NotNull;

public class RandomWanderBehavior implements CugoBehavior {
    private final int maxRadius = ConfigHandler.getConfig().wanderRadius;
    private int currentRadius = maxRadius;
    private final int minRadius = 1;
    private int wanderChance = 100;

    private int preStepSubtractor = 0;
    private int stepSubtractor = 1;

    private boolean shouldLinger = false;

    // NEW: wanderTime budget in ticks
    private int wanderTicks = 0;
    private final int maxWanderTicks = ConfigHandler.getConfig().wanderTime * 20;
    private boolean wanderTimeExpired = false;

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        // Count up while wandering
        wanderTicks++;
        if (wanderTicks >= maxWanderTicks) {
            wanderTimeExpired = true;
        }

        if (golem.getNavigation().isDone()) {
            if (level.getRandom().nextInt(100) < wanderChance) {
                Vec3 randomPos = LandRandomPos.getPos(golem, currentRadius, 7);
                if (randomPos != null) {
                    boolean success = golem.getNavigation().moveTo(randomPos.x, randomPos.y, randomPos.z, 1.0D);
                    if (success) {
                        fiboChance();
                        currentRadius = Math.min(currentRadius + 1, maxRadius);
                    } else {
                        currentRadius = Math.max(currentRadius - 1, minRadius);
                    }
                } else {
                    currentRadius = Math.max(currentRadius - 1, minRadius);
                }
            } else {
                shouldLinger = true;
            }
        }
    }

    private void fiboChance() {
        wanderChance -= stepSubtractor;
        int nextValue = preStepSubtractor + stepSubtractor;
        preStepSubtractor = stepSubtractor;
        stepSubtractor = nextValue;
        Dev.log(wanderChance);
    }

    public void resetWanderChance() {
        preStepSubtractor = 0;
        stepSubtractor = 1;
        wanderChance = 100;
    }

    // NEW: call this when re-entering WANDER so the budget restarts
    public void resetWanderTime() {
        wanderTicks = 0;
        wanderTimeExpired = false;
    }

    public boolean wanderTimeExpired() { return wanderTimeExpired; }
    public boolean shouldLinger() { return shouldLinger; }
    public void falsifyLinger() { shouldLinger = false; }
}