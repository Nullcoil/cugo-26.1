package net.nullcoil.cugo.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;

public class PanicBehavior implements CugoBehavior {

    private int panicTicks = 0;
    private final int MIN_PANIC_TIME = ConfigHandler.getConfig().panicTime;
    private int pathCalcCooldown = 0;
    private StateMachine.PanicPhase phase = StateMachine.PanicPhase.RUNNING;

    // Memory variables
    private LivingEntity currentGuardian = null;
    private LivingEntity knownThreat = null; // Persist the attacker here

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        panicTicks++;
        if (pathCalcCooldown > 0) pathCalcCooldown--;

        // 1. UPDATE THREAT MEMORY
        // If the game reports a current attacker, update our memory.
        if (golem.getLastAttacker() != null) {
            this.knownThreat = golem.getLastAttacker();
        }

        // If the threat is dead, forget them immediately.
        if (this.knownThreat != null && !this.knownThreat.isAlive()) {
            this.knownThreat = null;
        }

        BlockPos home = ((CugoNBTAccessor)golem).cugo$getHome();

        // 2. HOME RUSH (High Priority: Holding Item + Has Home)
        if (!golem.getMainHandItem().isEmpty() && home != null) {
            if (pathCalcCooldown == 0 || golem.getNavigation().isDone()) {
                golem.getNavigation().moveTo(home.getX(), home.getY(), home.getZ(), 1.5d);
                pathCalcCooldown = 20;
            }
            return;
        }

        // 3. PHASE MANAGEMENT
        // Use knownThreat instead of golem.getLastAttacker()
        if (phase == StateMachine.PanicPhase.RUNNING && panicTicks > 20 && knownThreat != null) {
            phase = StateMachine.PanicPhase.SEEKING_GUARDIAN;
        }

        switch (phase) {
            case RUNNING -> handleRunning(golem, knownThreat);
            case SEEKING_GUARDIAN -> handleSeeking(golem, level, knownThreat);
            case HIDING -> handleHiding(golem, knownThreat);
        }
    }

    private void handleRunning(CopperGolem golem, LivingEntity threat) {
        if (pathCalcCooldown == 0 || golem.getNavigation().isDone()) {
            Vec3 target;
            if (threat != null) {
                // Vector math: Run away from the KNOWN threat
                Vec3 fleeDir = golem.position().subtract(threat.position()).normalize();
                target = golem.position().add(fleeDir.scale(10d));
            } else {
                target = DefaultRandomPos.getPos(golem, 10, 7);
            }

            if (target != null) {
                golem.getNavigation().moveTo(target.x, target.y, target.z, 1.5d);
                pathCalcCooldown = 10;
            }
        }
    }

    private void handleSeeking(CopperGolem golem, ServerLevel level, LivingEntity threat) {
        AABB searchBox = golem.getBoundingBox().inflate(32.0);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, searchBox, e ->
                (e instanceof IronGolem || e instanceof Player || e instanceof SnowGolem) && e != threat && e.isAlive()
        );

        if (candidates.isEmpty()) {
            phase = StateMachine.PanicPhase.RUNNING;
            return;
        }

        candidates.sort(Comparator.comparingInt(this::getGuardianPriority));
        this.currentGuardian = candidates.get(0);
        this.phase = StateMachine.PanicPhase.HIDING;
    }

    private void handleHiding(CopperGolem golem, LivingEntity threat) {
        if (currentGuardian == null || !currentGuardian.isAlive()) {
            phase = StateMachine.PanicPhase.SEEKING_GUARDIAN;
            return;
        }

        // --- LOOKING LOGIC ---
        if (threat != null) {
            // Paranoid looking
            if (golem.tickCount % 20 < 15) {
                golem.getLookControl().setLookAt(threat, 30.0f, 30.0f);
            } else {
                golem.getLookControl().setLookAt(currentGuardian, 30.0f, 30.0f);
            }
        } else {
            golem.getLookControl().setLookAt(currentGuardian, 30.0f, 30.0f);
        }

        // --- DYNAMIC SHIELDING MATH ---
        Vec3 hidePos;
        if (threat != null) {
            // Calculate spot behind guardian relative to THREAT
            Vec3 attackDirection = currentGuardian.position().subtract(threat.position());
            Vec3 safeOffset = attackDirection.normalize().scale(3.0);
            hidePos = currentGuardian.position().add(safeOffset);
        } else {
            hidePos = currentGuardian.position();
        }

        double distToSafeSpot = golem.position().distanceTo(hidePos);

        if (distToSafeSpot > 0.5 || pathCalcCooldown == 0) {
            golem.getNavigation().moveTo(hidePos.x, hidePos.y, hidePos.z, 1.6d);
            pathCalcCooldown = (distToSafeSpot < 2.0) ? 5 : 10;
        }
    }

    private int getGuardianPriority(LivingEntity entity) {
        if (entity instanceof IronGolem) return 0;
        if (entity instanceof Player) return 1;
        if (entity instanceof SnowGolem) return 2;
        return 3;
    }

    public boolean isSafe(@NotNull CopperGolem golem) {
        // 1. Min time check
        if (panicTicks < MIN_PANIC_TIME) return false;

        // 2. Home Check
        BlockPos home = ((CugoNBTAccessor)golem).cugo$getHome();
        if (home != null && !golem.getMainHandItem().isEmpty()) {
            return golem.blockPosition().closerThan(home, 3);
        }

        // 3. THREAT DISTANCE CHECK (The Fix)
        // If we remember a threat, and they are alive, and they are close... WE ARE NOT SAFE.
        if (knownThreat != null && knownThreat.isAlive()) {
            double distToThreat = golem.distanceTo(knownThreat);

            // If threat is within 16 blocks, keep panicking!
            if (distToThreat < 16.0) {
                return false;
            }
        }

        // 4. Default Safe State
        // Safe only if navigation is done AND threat is gone/dead/far away
        return golem.getNavigation().isDone();
    }

    public void reset() {
        this.panicTicks = 0;
        this.pathCalcCooldown = 0;
        this.phase = StateMachine.PanicPhase.RUNNING;
        this.currentGuardian = null;
        this.knownThreat = null; // Clear memory on full reset
    }
}