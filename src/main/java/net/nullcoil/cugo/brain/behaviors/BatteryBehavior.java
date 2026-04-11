package net.nullcoil.cugo.brain.behaviors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.level.block.state.BlockState;
import net.nullcoil.cugo.attribute.CugoAttributes;
import net.nullcoil.cugo.brain.CugoBehavior;
import net.nullcoil.cugo.brain.behaviors.pathfinding.movecontrol.TightMoveControl;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.Dev;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public class BatteryBehavior implements CugoBehavior {

    private static final int WARNING_THRESHOLD = ConfigHandler.getConfig().warningThreshold * 20; // 1 minute in ticks
    private static final int CHARGE_TICKS = ConfigHandler.getConfig().chargeTime * 20;      // 30 seconds in ticks
    private static final int DRAIN_PER_TICK = 1;
    private static final int PANIC_DRAIN_MULTIPLIER = ConfigHandler.getConfig().panicWasteMultiplier;
    private static final int CHARGE_GRACE = 5;             // ticks before losing dock
    private int dockingTimeout = 0;
    private static final int DOCKING_TIMEOUT_MAX = 60;

    private StateMachine.ChargePhase chargePhase = StateMachine.ChargePhase.IDLE;
    private @Nullable BlockPos chargeTarget = null;
    private int chargeTicks = 0;
    private int pathCooldown = 0;
    private int chargeGraceTicks = 0;
    private boolean isPanicking = false;
    private boolean wasChargingLastTick = false;
    private @Nullable TightMoveControl tightMoveControl = null;

    private final Set<Integer> loggedThresholds = new HashSet<>(Set.of(75, 50, 40, 30, 20));

    // ── External wiring ──────────────────────────────────────────────────────

    public void setMoveControl(@NotNull TightMoveControl tmc) {
        this.tightMoveControl = tmc;
    }

    public void setPanicking(boolean panicking) {
        this.isPanicking = panicking;
    }

    public boolean needsCharge() {
        return chargePhase != StateMachine.ChargePhase.IDLE;
    }

    // ── Main tick ────────────────────────────────────────────────────────────

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (!ConfigHandler.getConfig().rechargeableGolems) return;

        // ── Drain ────────────────────────────────────────────────────────────
        // Always drain unless we are actively charging.
        if (chargePhase != StateMachine.ChargePhase.CHARGING) {
            double current = golem.getAttributeValue(CugoAttributes.BATTERY);
            double drain = isPanicking
                    ? DRAIN_PER_TICK * PANIC_DRAIN_MULTIPLIER
                    : DRAIN_PER_TICK;
            golem.getAttribute(CugoAttributes.BATTERY)
                    .setBaseValue(Math.max(0, current - drain));
            checkBatteryThresholds(golem);
        }

        // ── Charging logging ─────────────────────────────────────────────────
        boolean chargingNow = chargePhase == StateMachine.ChargePhase.CHARGING;
        if (chargingNow && !wasChargingLastTick) {
            Dev.log(String.format("[Battery] Began charging: %d%%", getBatteryPercent(golem)));
        } else if (!chargingNow && wasChargingLastTick) {
            Dev.log(String.format("[Battery] Stopped charging: %d%%", getBatteryPercent(golem)));
        }
        wasChargingLastTick = chargingNow;

        // ── Panicking short-circuit ──────────────────────────────────────────
        // Panic overrides charging behavior entirely (battery just drains faster).
        if (isPanicking) return;

        // ── State machine ────────────────────────────────────────────────────
        double battery = golem.getAttributeValue(CugoAttributes.BATTERY);
        switch (chargePhase) {
            case IDLE -> {
                if (battery <= WARNING_THRESHOLD) {
                    chargePhase = StateMachine.ChargePhase.SEEKING;
                    golem.setState(CopperGolemState.IDLE);
                    Dev.log(String.format("[Battery] Low power. Seeking charge: %d%%",
                            getBatteryPercent(golem)));
                }
            }
            case SEEKING -> tickSeeking(golem, level);
            case DOCKING -> tickDocking(golem, level);
            case CHARGING -> tickCharging(golem, level);
        }
    }

    // ── SEEKING ──────────────────────────────────────────────────────────────

    private static final double DOCK_RANGE_SQ = 4.0; // 2 blocks — switch to TMC within this range

    private void tickSeeking(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (isInChargePosition(golem, level)) {
            dock(golem);
            return;
        }

        if (chargeTarget == null) {
            chargeTarget = findNearestChargePosition(golem, level);
            if (chargeTarget == null) return;
            Dev.log("[Battery] Target locked: " + chargeTarget.toShortString());
            // Hand off to pathfinder for world-aware navigation
            golem.getNavigation().moveTo(
                    chargeTarget.getX() + 0.5,
                    chargeTarget.getY(),
                    chargeTarget.getZ() + 0.5,
                    1.0
            );
        }

        double dx = golem.getX() - (chargeTarget.getX() + 0.5);
        double dy = golem.getY() - chargeTarget.getY();
        double dz = golem.getZ() - (chargeTarget.getZ() + 0.5);
        double xzDistSq = dx * dx + dz * dz;
        double yDist = Math.abs(dy);

        if (xzDistSq <= DOCK_RANGE_SQ && yDist <= 1.5) {
            golem.getNavigation().stop();
            chargePhase = StateMachine.ChargePhase.DOCKING;
            Dev.log("[Battery] Switching to TMC for final approach.");
            return;
        }

        // Re-issue path if navigator gave up
        if (golem.getNavigation().isDone()) {
            golem.getNavigation().moveTo(
                    chargeTarget.getX() + 0.5,
                    chargeTarget.getY(),
                    chargeTarget.getZ() + 0.5,
                    1.0
            );
        }
    }

    private void tickDocking(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (isInChargePosition(golem, level)) {
            dock(golem);
            dockingTimeout = 0;
            return;
        }

        if (chargeTarget == null) {
            chargePhase = StateMachine.ChargePhase.SEEKING;
            return;
        }

        dockingTimeout++;
        if (dockingTimeout > DOCKING_TIMEOUT_MAX) {
            Dev.log("[Battery] TMC couldn't dock. Re-seeking.");
            chargePhase = StateMachine.ChargePhase.SEEKING;
            chargeTarget = null;
            dockingTimeout = 0;
            return;
        }

        double dx = golem.getX() - (chargeTarget.getX() + 0.5);
        double dy = golem.getY() - chargeTarget.getY();
        double dz = golem.getZ() - (chargeTarget.getZ() + 0.5);
        double yDist = Math.abs(dy);

        // If Y is still off, let the pathfinder handle it — TMC can't jump
        if (yDist > 0.5) {
            if (golem.getNavigation().isDone()) {
                golem.getNavigation().moveTo(
                        chargeTarget.getX() + 0.5,
                        chargeTarget.getY(),
                        chargeTarget.getZ() + 0.5,
                        1.0
                );
            }
            return;
        }

        // Y is good — TMC handles XZ precision
        if (tightMoveControl != null) {
            tightMoveControl.setWantedPosition(
                    chargeTarget.getX() + 0.5,
                    chargeTarget.getY(),
                    chargeTarget.getZ() + 0.5,
                    1.0
            );
        }
    }

    // ── CHARGING ─────────────────────────────────────────────────────────────

    private void tickCharging(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        // Hard-stop movement every tick while docked.
        golem.getNavigation().stop();
        if (tightMoveControl != null) tightMoveControl.forceStop();

        // Grace period before declaring dock lost.
        if (!isInChargePosition(golem, level)) {
            chargeGraceTicks++;
            if (chargeGraceTicks > CHARGE_GRACE) {
                Dev.log("[Battery] Lost dock. Resuming seek.");
                chargePhase = StateMachine.ChargePhase.SEEKING;
                chargeTarget = null;
                chargeGraceTicks = 0;
            }
            return;
        }

        chargeGraceTicks = 0;
        chargeTicks++;
        applyRecharge(golem, level);

        if (getBatteryPercent(golem) >= 100) {
            Dev.log("[Battery] Fully charged.");
            reset(golem);
        }
    }

    // ── Docking ──────────────────────────────────────────────────────────────

    private void dock(@NotNull CopperGolem golem) {
        golem.getNavigation().stop();
        if (tightMoveControl != null) tightMoveControl.forceStop();
        chargeTicks = 0;
        chargeGraceTicks = 0;
        chargePhase = StateMachine.ChargePhase.CHARGING;
        golem.setState(CopperGolemState.IDLE);
        Dev.log("[Battery] Docked.");
    }

    // ── Recharge application ─────────────────────────────────────────────────

    private void applyRecharge(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        double maxBattery = ConfigHandler.getConfig().batteryLife * 60 * 20.0;
        double chargePerTick = maxBattery / CHARGE_TICKS;
        double current = golem.getAttributeValue(CugoAttributes.BATTERY);
        golem.getAttribute(CugoAttributes.BATTERY)
                .setBaseValue(Math.min(current + chargePerTick, maxBattery));

        // Redstone particle effect while charging.
        if (level.getRandom().nextFloat() < 0.15f) {
            DustParticleOptions particle = new DustParticleOptions(0xff0000, 1.0f);
            level.sendParticles(particle,
                    golem.getRandomX(0.5), golem.getY(), golem.getRandomZ(0.5),
                    3, 0.1, 0.1, 0.1, 0.05);
        }
    }

    // ── Charge position logic ────────────────────────────────────────────────

    /**
     * Returns true if the golem's current feet position qualifies as a charge spot.
     * Valid spots: the signal source block itself, or any of its 6 face-adjacent neighbors.
     */
    public static boolean isInChargePosition(@NotNull CopperGolem golem,
                                             @NotNull ServerLevel level) {
        BlockPos feet = BlockPos.containing(golem.getX(), golem.getY(), golem.getZ());
        return isValidChargeStandingPos(level, feet);
    }

    /**
     * A position is valid if it IS an active signal source, or is face-adjacent to one.
     * Diagonal positions do NOT count.
     */
    public static boolean isValidChargeStandingPos(@NotNull ServerLevel level,
                                                   @NotNull BlockPos pos) {
        // Y: standing directly in/on the signal source
        if (isActiveSignalSource(level, pos)) return true;

        // Y: face-adjacent to a signal source (N/S/E/W/Up/Down — the cross)
        for (Direction dir : Direction.values()) {
            if (isActiveSignalSource(level, pos.relative(dir))) return true;
        }

        // U: block directly above pos is itself adjacent to a signal source above it
        // i.e. the torch is at pos.above().above()
        if (isActiveSignalSource(level, pos.above().above())) return true;

        return false;
    }

    /**
     * A block is an active signal source if it implements isSignalSource and is
     * currently emitting signal in at least one direction.
     */
    private static boolean isActiveSignalSource(@NotNull ServerLevel level,
                                                @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isSignalSource()) return false;
        for (Direction dir : Direction.values()) {
            if (state.getSignal(level, pos, dir) > 0) return true;
        }
        return false;
    }

    /**
     * Scans nearby blocks to find the closest valid standing position adjacent
     * to an active signal source that the golem can physically occupy.
     */
    private @Nullable BlockPos findNearestChargePosition(@NotNull CopperGolem golem,
                                                         @NotNull ServerLevel level) {
        BlockPos origin = golem.blockPosition();
        int range = 16;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-range, -4, -range),
                origin.offset(range, 4, range))) {

            if (!isValidChargeStandingPos(level, pos)) continue;

            // Golem must be able to stand here: needs floor, needs headroom, must be passable.
            BlockState here = level.getBlockState(pos);
            if (!here.isAir() && !here.isSignalSource()) continue;

            BlockPos below = pos.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;

            BlockPos above = pos.above();
            if (level.getBlockState(above).isSuffocating(level, above)) continue;

            double dist = pos.distSqr(origin);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos.immutable();
            }
        }
        return nearest;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private int getBatteryPercent(@NotNull CopperGolem golem) {
        double max = ConfigHandler.getConfig().batteryLife * 60 * 20.0;
        double current = golem.getAttributeValue(CugoAttributes.BATTERY);
        return (int) ((current / max) * 100);
    }

    private void checkBatteryThresholds(@NotNull CopperGolem golem) {
        int percent = getBatteryPercent(golem);
        loggedThresholds.removeIf(threshold -> {
            if (percent <= threshold) {
                Dev.log(String.format("[Battery] Warning: %d%% remaining.", threshold));
                return true;
            }
            return false;
        });
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    public void reset(@Nullable CopperGolem golem) {
        chargePhase = StateMachine.ChargePhase.IDLE;
        chargeTarget = null;
        chargeTicks = 0;
        pathCooldown = 0;
        chargeGraceTicks = 0;
        loggedThresholds.addAll(Set.of(75, 50, 40, 30, 20));
        if (golem != null) golem.setState(CopperGolemState.IDLE);
    }
}