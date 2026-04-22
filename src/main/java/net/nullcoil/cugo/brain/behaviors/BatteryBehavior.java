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
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.Dev;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public class BatteryBehavior implements CugoBehavior {

    private static final int WARNING_THRESHOLD = ConfigHandler.getConfig().warningThreshold * 20 * 60;
    private static final int CHARGE_TICKS = ConfigHandler.getConfig().chargeTime * 20;
    private static final int DRAIN_PER_TICK = ConfigHandler.getConfig().batteryDrain;
    private static final int PANIC_DRAIN_MULTIPLIER = ConfigHandler.getConfig().panicWasteMultiplier;
    private static final int CHARGE_GRACE = 5;
    private static final int DOCKING_TIMEOUT_MAX = 60;

    private StateMachine.ChargePhase chargePhase = StateMachine.ChargePhase.IDLE;
    private @Nullable BlockPos target = null;
    private int chargeTicks = 0;
    private int pathCooldown = 0;
    private int chargeGraceTicks = 0;
    private int dockingTimeout = 0;
    private boolean isPanicking = false;
    private boolean wasChargingLastTick = false;

    private final Set<Integer> loggedThresholds = new HashSet<>(Set.of(75, 50, 40, 30, 20));

    // ── External wiring ──────────────────────────────────────────────────────

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
        if (chargePhase != StateMachine.ChargePhase.CHARGING) {
            double current = golem.getAttributeValue(CugoAttributes.BATTERY);
            double drain = isPanicking ? DRAIN_PER_TICK * PANIC_DRAIN_MULTIPLIER : DRAIN_PER_TICK;
            golem.getAttribute(CugoAttributes.BATTERY)
                    .setBaseValue(Math.max(0, current - drain));
            checkBatteryThresholds(golem);
        }

        // ── Charging logging ─────────────────────────────────────────────────
        boolean chargingNow = chargePhase == StateMachine.ChargePhase.CHARGING;
        if (chargingNow && !wasChargingLastTick)
            Dev.log(String.format("[BB] Began charging: %d%%", getBatteryPercent(golem)));
        else if (!chargingNow && wasChargingLastTick)
            Dev.log(String.format("[BB] Stopped charging: %d%%", getBatteryPercent(golem)));
        wasChargingLastTick = chargingNow;

        if (isPanicking) return;

        // ── State machine ────────────────────────────────────────────────────
        double battery = golem.getAttributeValue(CugoAttributes.BATTERY);
        switch (chargePhase) {
            case IDLE -> {
                if (battery <= WARNING_THRESHOLD) {
                    chargePhase = StateMachine.ChargePhase.SEEKING;
                    golem.setState(CopperGolemState.IDLE);
                    Dev.log(String.format("[BB] Low power. Seeking charge: %d%%", getBatteryPercent(golem)));
                }
            }
            case SEEKING -> tickSeeking(golem, level);
            case CHARGING -> {
                if (battery < ConfigHandler.getConfig().batteryLife * 60 * 20) {
                    applyRecharge(golem, level);
                } else if (battery == ConfigHandler.getConfig().batteryLife * 60 * 20) {
                    chargePhase = StateMachine.ChargePhase.IDLE;
                    golem.setState(CopperGolemState.IDLE);
                    Dev.log(String.format("[BB] Should be at max power. Current battery: %d%%", getBatteryPercent(golem)));
                }
            }
        }
    }

    // ── Recharge application ─────────────────────────────────────────────────

    private void applyRecharge(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        double maxBattery = ConfigHandler.getConfig().batteryLife * 60 * 20.0;
        double chargePerTick = maxBattery / CHARGE_TICKS;
        double current = golem.getAttributeValue(CugoAttributes.BATTERY);
        golem.getAttribute(CugoAttributes.BATTERY)
                .setBaseValue(Math.min(current + chargePerTick, maxBattery));

        if (level.getRandom().nextFloat() < 0.15f) {
            DustParticleOptions particle = new DustParticleOptions(0xff0000, 1.0f);
            level.sendParticles(particle,
                    golem.getRandomX(0.5), golem.getY(), golem.getRandomZ(0.5),
                    3, 0.1, 0.1, 0.1, 0.05);
        }
    }

    // ── Find the target ──────────────────────────────────────────────────────

    private void tickSeeking(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (inChargeBubble(level, golem.blockPosition())) {
            Dev.log("[BB] Golem in bubble. Golem pos: " + golem.blockPosition());
            golem.getNavigation().stop();
            chargePhase = StateMachine.ChargePhase.CHARGING;
            return;
        }

        // If our target is no longer a valid signal source, discard it and re-search
        if (target != null && !isActiveSignalSource(level, target)) {
            Dev.log("[BB] Target lost signal, re-seeking: " + target);
            golem.getNavigation().stop();
            target = null;
        }

        if (target == null) {
            target = getChargeTarget(golem, level);
            if (target == null) {
                Dev.log("[BB] No target found");
                return;
            }
            Dev.log("[BB] Target locked: " + target);
        }

        golem.getNavigation().moveTo(target.getX()+0.5, target.getY(), target.getZ()+0.5, 1.1);

        if(golem.distanceToSqr(target.getX(), target.getY(), target.getZ()) < 2.5) {
            Dev.log("[BB] Golem is close.");
        }
    }

    // ── Charge position logic ────────────────────────────────────────────────

    @Nullable
    private BlockPos getChargeTarget(@NotNull CopperGolem golem,  @NotNull ServerLevel level) {
        BlockPos origin = golem.blockPosition();
        int range = ConfigHandler.getConfig().searchRadius;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-range, -2, -range),
                origin.offset(range, 4, range))) {
            if(!isActiveSignalSource(level, pos)) continue;

            double dist = pos.distSqr(origin);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos.immutable();
            }
        }
        return nearest;
    }

    private static boolean isActiveSignalSource(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isSignalSource()) return false;
        for (Direction dir : Direction.values()) if (state.getSignal(level, pos, dir) > 0) return true;
        return false;
    }

    public static boolean inChargeBubble(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        // start with the strict bubble:
        if(isActiveSignalSource(level, pos)) return true; // if standing in something like a lit redstone torch
        if(level.hasNeighborSignal(pos)) return true;     // if adjacent to active signal source
        BlockPos neighbor;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            neighbor = pos.relative(dir);
            if (level.hasNeighborSignal(neighbor) || isActiveSignalSource(level, neighbor)) return true;
            // ^are we standing directly next to the bubble or an active signal source?^
        }
        BlockPos above = pos.relative(Direction.UP);
        BlockPos below = pos.relative(Direction.DOWN);

        // Standing under the bubble, or antenna is touching bubble
        if (level.hasNeighborSignal(above)
                || level.hasNeighborSignal(above.relative(Direction.UP))
                || isActiveSignalSource(level, above)
                || isActiveSignalSource(level, above.relative(Direction.UP))) return true;

        if (level.hasNeighborSignal(below) || isActiveSignalSource(level, below)) return true; //standing under bubble

        return false;
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
        target = null;
        chargeTicks = 0;
        pathCooldown = 0;
        chargeGraceTicks = 0;
        dockingTimeout = 0;
        loggedThresholds.addAll(Set.of(75, 50, 40, 30, 20));
        if (golem != null) golem.setState(CopperGolemState.IDLE);
    }
}