package net.nullcoil.cugo.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.brain.movecontrol.TightMoveControl;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.MobMoveControlAccessor;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;

public class CugoBrain implements CugoBehavior {
    private StateMachine.State currentState = StateMachine.State.WANDERING;

    private RandomWanderBehavior wanderBehavior;
    private LingerBehavior lingerBehavior;
    private PingChestsBehavior pingBehavior;
    private PanicBehavior panicBehavior;
    private SelfPreservationBehavior preservationBehavior;
    private FetchItemBehavior fetchBehavior;
    private SortItemBehavior sortBehavior;
    private BatteryBehavior batteryBehavior;
    private PassivePowerBehavior passivePower;

    private MoveControl vanillaMoveControl;
    private TightMoveControl tightMoveControl;

    private CopperGolem self;

    public void onAttach(@NotNull CopperGolem golem) {
        this.wanderBehavior = new RandomWanderBehavior();
        this.lingerBehavior = new LingerBehavior(3);
        this.pingBehavior = new PingChestsBehavior();
        this.panicBehavior = new PanicBehavior();
        this.preservationBehavior = new SelfPreservationBehavior();
        this.fetchBehavior = new FetchItemBehavior();
        this.sortBehavior = new SortItemBehavior();
        this.batteryBehavior = new BatteryBehavior();

        this.passivePower = new PassivePowerBehavior();

        this.vanillaMoveControl = golem.getMoveControl();
        this.tightMoveControl = new TightMoveControl(golem);
        this.batteryBehavior.setMoveControl(tightMoveControl); // ← wire it in

        this.self = golem;
    }

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (ConfigHandler.getConfig().rechargeableGolems) {
            this.passivePower.tick(golem, level);

            batteryBehavior.setPanicking(currentState == StateMachine.State.PANIC);
            batteryBehavior.tick(golem, level);

            boolean needsPrecision = batteryBehavior.needsCharge();
            MoveControl current = ((MobMoveControlAccessor) golem).cugo$getMoveControl();

            if (needsPrecision && current != tightMoveControl) {
                ((MobMoveControlAccessor) golem).cugo$setMoveControl(tightMoveControl);
            } else if (!needsPrecision && current == tightMoveControl) {
                ((MobMoveControlAccessor) golem).cugo$setMoveControl(vanillaMoveControl);
            }
        }

        // ── 1. PANIC ─────────────────────────────────────────────────────────
        if (currentState == StateMachine.State.PANIC) {
            panicBehavior.tick(golem, level);
            if (panicBehavior.isSafe(golem)) {
                transitionToWander();
            }
            return;
        }

        if (ConfigHandler.getConfig().rechargeableGolems && batteryBehavior.needsCharge()) {
            return;
        }

        // ── 2. SELF PRESERVATION ─────────────────────────────────────────────
        if (preservationBehavior.canPerform(golem)) {
            preservationBehavior.tick(golem, level);
            return;
        }

        boolean holdingItem = !golem.getMainHandItem().isEmpty();

        // ── 3. SORTING ───────────────────────────────────────────────────────
        if (currentState == StateMachine.State.SORTING) {
            if(golem.getMainHandItem().isEmpty()) {
                sortBehavior.reset();
                fetchBehavior.reset();
                transitionToWander();
                return;
            }
            sortBehavior.tick(golem, level);

            if (sortBehavior.isDone()) {
                sortBehavior.reset();
                BlockPos returnTarget = fetchBehavior.getLastFetchedFrom(); // grab before reset
                fetchBehavior.reset();
                fetchBehavior.primeWithTarget(returnTarget);
                currentState = StateMachine.State.FETCHING;
            }
            return;
        }

        // ── 4. FETCHING ──────────────────────────────────────────────────────
        if (currentState == StateMachine.State.FETCHING) {
            fetchBehavior.tick(golem, level);

            if (!golem.getMainHandItem().isEmpty()) {
                sortBehavior.reset();
                sortBehavior.primeWithMemory(
                        fetchBehavior.getLastFetchedFrom(),
                        fetchBehavior.getLastFetchedItem()
                );
                fetchBehavior.reset();
                currentState = StateMachine.State.SORTING;
            } else if (fetchBehavior.isDone()) {
                fetchBehavior.reset();
                transitionToWander();
            }
            return;
        }

        // ── 5. ROUTINE: WANDER → LINGER → PING ──────────────────────────────
        switch (currentState) {
            case WANDERING -> {
                wanderBehavior.tick(golem, level);

                // wanderTime expired → break out of routine, go fetch.
                if (wanderBehavior.wanderTimeExpired()) {
                    wanderBehavior.resetWanderChance();
                    fetchBehavior.reset();
                    currentState = StateMachine.State.FETCHING;
                    return;
                }

                // Fibo chance triggered linger.
                if (wanderBehavior.shouldLinger()) {
                    lingerBehavior.reset();
                    currentState = StateMachine.State.LINGERING;
                }
            }
            case LINGERING -> {
                lingerBehavior.tick(golem, level);
                if (lingerBehavior.isFinished()) {
                    wanderBehavior.falsifyLinger();
                    currentState = StateMachine.State.PINGING;
                }
            }
            case PINGING -> {
                pingBehavior.tick(golem, level);
                wanderBehavior.resetWanderChance();
                // After ping, resume wandering (routine loop continues).
                currentState = StateMachine.State.WANDERING;
            }
        }
    }

    public void onPhysicalDamage(DamageSource source) {
        this.currentState = StateMachine.State.PANIC;
        if (this.panicBehavior != null) this.panicBehavior.reset();
        if (this.fetchBehavior != null) this.fetchBehavior.fullReset();
        if (this.sortBehavior != null) this.sortBehavior.fullReset();
        if (this.self != null) {
            ((MobMoveControlAccessor)self).cugo$setMoveControl(vanillaMoveControl);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void transitionToWander() {
        currentState = StateMachine.State.WANDERING;
        wanderBehavior.resetWanderChance();
        wanderBehavior.resetWanderTime(); // Fresh budget every time we enter WANDER
    }
}