package net.nullcoil.cugo.brain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.golem.CopperGolem;
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

    private CopperGolem self;

    public void onAttach(@NotNull CopperGolem golem) {
        this.self = golem;
        this.wanderBehavior = new RandomWanderBehavior();
        this.lingerBehavior = new LingerBehavior(3);
        this.pingBehavior = new PingChestsBehavior();
        this.panicBehavior = new PanicBehavior();
        this.preservationBehavior = new SelfPreservationBehavior();
        this.fetchBehavior = new FetchItemBehavior();
        this.sortBehavior = new SortItemBehavior();
    }

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {

        // ── 1. PANIC ─────────────────────────────────────────────────────────
        if (currentState == StateMachine.State.PANIC) {
            panicBehavior.tick(golem, level);
            if (panicBehavior.isSafe(golem)) {
                transitionToWander();
            }
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
            sortBehavior.tick(golem, level);

            if (sortBehavior.isDone()) {
                sortBehavior.reset();
                if (!golem.getMainHandItem().isEmpty()) {
                    // Still holding something — sort didn't place everything,
                    // try again next tick (stays in SORTING) or fall through to FETCH.
                    // Per spec: successful sort → go directly to FETCH.
                    fetchBehavior.reset();
                    currentState = StateMachine.State.FETCHING;
                } else {
                    // Item was placed — go straight to FETCH.
                    fetchBehavior.reset();
                    currentState = StateMachine.State.FETCHING;
                }
            }
            return;
        }

        // ── 4. FETCHING ──────────────────────────────────────────────────────
        if (currentState == StateMachine.State.FETCHING) {
            fetchBehavior.tick(golem, level);

            if (!golem.getMainHandItem().isEmpty()) {
                // Picked something up — go sort it.
                fetchBehavior.reset();
                sortBehavior.reset();
                currentState = StateMachine.State.SORTING;
            } else if (fetchBehavior.isDone()) {
                // Exhausted all chests, nothing grabbed — back to wandering.
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
        if (this.self != null) this.self.getNavigation().stop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void transitionToWander() {
        currentState = StateMachine.State.WANDERING;
        wanderBehavior.resetWanderChance();
        wanderBehavior.resetWanderTime(); // Fresh budget every time we enter WANDER
    }
}