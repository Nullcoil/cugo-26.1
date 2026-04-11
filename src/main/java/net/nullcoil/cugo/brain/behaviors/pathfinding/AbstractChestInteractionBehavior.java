package net.nullcoil.cugo.brain.behaviors.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nullcoil.cugo.brain.CugoBehavior;
import net.nullcoil.cugo.brain.memories.ChestMemory;
import net.nullcoil.cugo.brain.behaviors.pathfinding.movecontrol.TightMoveControl;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractChestInteractionBehavior implements CugoBehavior {

    // Shared phases (can be extended by subclasses if needed)
    protected StateMachine.Phase phase = StateMachine.Phase.IDLE;

    // Shared fields
    protected List<BlockPos> chestQueue = new ArrayList<>();
    protected @Nullable BlockPos currentTarget = null;
    protected @Nullable BlockPos currentApproachPos = null;
    protected int openingTimer = 0;
    protected int pathCooldown = 0;
    protected int stallTimer = 0;
    protected double lastX = Double.NaN, lastZ = Double.NaN;
    protected boolean usingTMC = false;

    protected TightMoveControl tightMoveControl;
    protected MoveControl vanillaMoveControl;

    protected static final int OPEN_DURATION = 60;

    // ------------------------------------------------------------------------
    // Abstract methods – each behavior implements its own logic
    // ------------------------------------------------------------------------

    /** Build the chest queue (order to visit) based on behavior-specific rules. */
    protected abstract void buildQueue(@NotNull CopperGolem golem, @NotNull ServerLevel level);

    /** Return true if the block at pos is a valid container for this behavior. */
    protected abstract boolean isValidTarget(@NotNull ServerLevel level, @NotNull BlockPos pos);

    /** Called on the first tick of opening (tickOpening timer == 1). Set golem state and play sounds. */
    protected abstract void onOpenAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                            @Nullable Container inventory, @NotNull BlockPos chestPos);

    /** Called after OPEN_DURATION ticks. Perform the actual item transfer (extract, insert, swap). */
    protected abstract void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                           @Nullable Container inventory, @NotNull BlockPos chestPos);

    /**
     * Optional hook for closing sound/animation. Default does nothing.
     * Override if needed (e.g., FetchItemBehavior plays copper chest close).
     */
    protected void onCloseAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                    @NotNull BlockPos chestPos) {
        // default no-op
    }

    // ------------------------------------------------------------------------
    // Public API (implements CugoBehavior)
    // ------------------------------------------------------------------------

    public void setMoveControls(TightMoveControl tmc, MoveControl vanilla) {
        this.tightMoveControl = tmc;
        this.vanillaMoveControl = vanilla;
    }

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (pathCooldown > 0) pathCooldown--;

        switch (phase) {
            case IDLE -> {
                buildQueue(golem, level);
                if (chestQueue.isEmpty()) {
                    Dev.log("[AbstractChest] Queue empty, finishing.");
                    phase = StateMachine.Phase.DONE;
                } else {
                    advanceToNextChest(golem, level);
                }
            }
            case PATHING -> tickPathing(golem, level);
            case OPENING -> tickOpening(golem, level);
            case DONE -> {
                // Subclasses may override reset or do nothing; CugoBrain will handle transition.
            }
        }
    }

    // ------------------------------------------------------------------------
    // Shared pathfinding logic (identical for all behaviors)
    // ------------------------------------------------------------------------

    private void tickPathing(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (currentTarget == null) {
            Dev.log("[AbstractChest] currentTarget lost during PATHING. Aborting.");
            phase = StateMachine.Phase.DONE;
            return;
        }

        if (!isValidTarget(level, currentTarget)) {
            Dev.log("[AbstractChest] Target chest at " + currentTarget + " no longer valid. Skipping.");
            advanceToNextChest(golem, level);
            return;
        }

        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        BlockPos accessPos = accessor.cugo$getAccessPos(currentTarget);

        // Stall detection – only update if we're not using TMC yet, because TMC moves differently
        if (!usingTMC) {
            double cx = golem.getX(), cz = golem.getZ();
            if (!Double.isNaN(lastX)) {
                double movedSq = (cx - lastX) * (cx - lastX) + (cz - lastZ) * (cz - lastZ);
                stallTimer = movedSq < 0.01 ? stallTimer + 1 : 0;
            }
            lastX = cx;
            lastZ = cz;
        } else {
            // When using TMC, reset stall timer periodically because TMC moves in very small steps
            // Actually, TMC's movement is handled separately; we'll let the arrival condition work.
            // We don't increment stallTimer while in TMC.
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 1. If we are already close enough to the chest (interaction range), just open it.
        //    This overrides any stall detection.
        // ──────────────────────────────────────────────────────────────────────────
        if (isCloseEnough(golem, currentTarget)) {
            if (!canAccessChest(level, golem, currentTarget)) {
                Dev.log("[AbstractChest] No line of sight to chest " + currentTarget + " – skipping.");
                advanceToNextChest(golem, level);
                return;
            }
            Dev.log("[AbstractChest] Already close enough – opening " + currentTarget);
            restoreVanilla(golem);
            golem.getNavigation().stop();
            openingTimer = 0;
            stallTimer = 0;
            phase = StateMachine.Phase.OPENING;
            return;
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 2. If navigation is still computing (pathCooldown > 0) or we just started,
        //    give it more time before any stall logic.
        // ──────────────────────────────────────────────────────────────────────────
        if (pathCooldown > 0 || (Double.isNaN(lastX) && Double.isNaN(lastZ))) {
            // Still initializing, don't skip yet
            return;
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 3. Stall guard for vanilla navigation – only after 2 seconds (40 ticks)
        //    of no progress, and only if we're not already trying TMC.
        // ──────────────────────────────────────────────────────────────────────────
        if (!usingTMC && stallTimer >= 40) {
            Dev.log("[AbstractChest] Stalled for 2 seconds without progress. Trying to recover...");

            // If we have an access position and it's not too far, switch to TMC as a last resort
            if (accessPos != null) {
                double dx = golem.getX() - (accessPos.getX() + 0.5);
                double dz = golem.getZ() - (accessPos.getZ() + 0.5);
                double distSq = dx * dx + dz * dz;
                if (distSq <= 10.0) { // within 3.16 blocks
                    Dev.log("[AbstractChest] Switching to TMC to nudge into position.");
                    golem.getNavigation().stop();
                    usingTMC = true;
                    stallTimer = 0; // reset stall timer for TMC
                    ((MobMoveControlAccessor) golem).cugo$setMoveControl(tightMoveControl);
                    if (currentApproachPos != null) {
                        tightMoveControl.setWantedPosition(
                                currentApproachPos.getX() + 0.5,
                                currentApproachPos.getY(),
                                currentApproachPos.getZ() + 0.5,
                                1.0
                        );
                    } else {
                        tightMoveControl.setWantedPosition(
                                currentTarget.getX() + 0.5,
                                currentTarget.getY(),
                                currentTarget.getZ() + 0.5,
                                1.0
                        );
                    }
                    return;
                }
            }

            // No TMC possible – give up on this chest
            Dev.log("[AbstractChest] Stalled and unreachable – skipping " + currentTarget);
            restoreVanilla(golem);
            stallTimer = 0;
            advanceToNextChest(golem, level);
            return;
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 4. TMC arrival check (if using TMC)
        // ──────────────────────────────────────────────────────────────────────────
        if (usingTMC && currentApproachPos != null) {
            double dx = golem.getX() - (currentApproachPos.getX() + 0.5);
            double dz = golem.getZ() - (currentApproachPos.getZ() + 0.5);
            double dy = golem.getY() - currentApproachPos.getY();
            if (dx * dx + dz * dz < 0.04 && Math.abs(dy) < ConfigHandler.getConfig().losVerticalThreshold) {
                if (!canAccessChest(level, golem, currentTarget)) {
                    Dev.log("[AbstractChest] No line of sight to chest " + currentTarget + " – skipping.");
                    advanceToNextChest(golem, level);
                    return;
                }
                Dev.log("[AbstractChest] TMC arrived at access pos. Opening.");
                restoreVanilla(golem);
                golem.getNavigation().stop();
                openingTimer = 0;
                stallTimer = 0;
                phase = StateMachine.Phase.OPENING;
                return;
            }
            // TMC is active, just let it run
            return;
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 5. Switch to TMC early if we are close to the access position but not moving
        // ──────────────────────────────────────────────────────────────────────────
        if (!usingTMC && accessPos != null) {
            double dx = golem.getX() - (accessPos.getX() + 0.5);
            double dz = golem.getZ() - (accessPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (distSq <= 6.0) { // within 2.45 blocks – close enough for TMC to finish
                Dev.log("[AbstractChest] Close to target, switching to TMC for final approach.");
                golem.getNavigation().stop();
                usingTMC = true;
                stallTimer = 0;
                ((MobMoveControlAccessor) golem).cugo$setMoveControl(tightMoveControl);
                if (currentApproachPos != null) {
                    tightMoveControl.setWantedPosition(
                            currentApproachPos.getX() + 0.5,
                            currentApproachPos.getY(),
                            currentApproachPos.getZ() + 0.5,
                            1.0
                    );
                } else {
                    tightMoveControl.setWantedPosition(
                            currentTarget.getX() + 0.5,
                            currentTarget.getY(),
                            currentTarget.getZ() + 0.5,
                            1.0
                    );
                }
                return;
            }
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 6. Vanilla navigation – request a new path if needed
        // ──────────────────────────────────────────────────────────────────────────
        if (!usingTMC) {
            if (pathCooldown == 0 || golem.getNavigation().isDone()) {
                if (currentApproachPos != null && !isCloseEnough(golem, currentTarget)) {
                    golem.getNavigation().moveTo(
                            currentApproachPos.getX() + 0.5,
                            currentApproachPos.getY(),
                            currentApproachPos.getZ() + 0.5,
                            1.0
                    );
                    pathCooldown = 20;
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Shared opening sequence
    // ------------------------------------------------------------------------

    private void tickOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        golem.getNavigation().stop();
        if (currentTarget == null) {
            phase = StateMachine.Phase.DONE;
            return;
        }

        openingTimer++;
        golem.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
        );

        if (openingTimer == 1) {
            if (!canAccessChest(level, golem, currentTarget)) {
                Dev.log("[AbstractChest] No line of sight - skipping " + currentTarget);
                advanceToNextChest(golem, level);
                return;
            }
            BlockState state = level.getBlockState(currentTarget);
            Dev.log("[AbstractChest] Opening " + state.getBlock() + " at " + currentTarget);

            golem.setOpenedChestPos(currentTarget);
            openContainer(level, currentTarget);

            Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
            onOpenAnimation(golem, level, inventory, currentTarget);
        }

        if (openingTimer >= OPEN_DURATION) {
            Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
            golem.setState(CopperGolemState.IDLE);
            golem.clearOpenedChestPos();
            closeContainer(level, currentTarget);
            onCloseAnimation(golem, level, currentTarget);

            resolveOpening(golem, level, inventory, currentTarget);
            // After resolve, either phase becomes DONE or we call advanceToNextChest()
            // Subclasses must set phase appropriately.
        }
    }

    // ------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------

    protected void advanceToNextChest(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        restoreVanilla(golem);
        golem.clearOpenedChestPos();
        while (!chestQueue.isEmpty()) {
            BlockPos candidate = chestQueue.remove(0);
            if (isValidTarget(level, candidate)) {
                currentTarget = candidate;
                stallTimer = 0;
                lastX = lastZ = Double.NaN;
                currentApproachPos = getBestApproachPos(golem, candidate, level);
                golem.getNavigation().moveTo(
                        currentApproachPos.getX() + 0.5,
                        currentApproachPos.getY(),
                        currentApproachPos.getZ() + 0.5,
                        1.0
                );
                pathCooldown = 20;
                phase = StateMachine.Phase.PATHING;
                Dev.log("[AbstractChest] Next target: " + candidate);
                return;
            }
        }
        Dev.log("[AbstractChest] Queue exhausted.");
        phase = StateMachine.Phase.DONE;
    }

    protected BlockPos getBestApproachPos(@NotNull CopperGolem golem, @NotNull BlockPos chestPos, @NotNull ServerLevel level) {
        BlockPos golemPos = golem.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = chestPos.relative(dir);
            BlockPos below = candidate.below();
            if (!level.getBlockState(below).isSolid()) continue;
            if (!level.getBlockState(candidate).isAir()) continue;
            if (!level.getBlockState(candidate.above()).isAir()) continue;

            double dist = candidate.distSqr(golemPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best != null ? best : chestPos;
    }

    protected void restoreVanilla(@NotNull CopperGolem golem) {
        if (usingTMC) {
            ((MobMoveControlAccessor) golem).cugo$setMoveControl(vanillaMoveControl);
            usingTMC = false;
        }
    }

    protected boolean isCloseEnough(@NotNull CopperGolem golem, @NotNull BlockPos target) {
        double dx = golem.getX() - (target.getX() + 0.5);
        double dz = golem.getZ() - (target.getZ() + 0.5);
        double dy = golem.getY() - target.getY();
        double xzRange = ConfigHandler.getConfig().xzInteractRange;
        double yRange = ConfigHandler.getConfig().yInteractRange;
        return (dx * dx + dz * dz) <= (xzRange * xzRange) && Math.abs(dy) <= yRange;
    }

    protected void recordRummaged(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                  @NotNull BlockPos pos, @Nullable Container inventory) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        BlockState state = level.getBlockState(pos);
        StateMachine.Container type = new StateMachine().determineContainerType(state);

        List<ItemStack> snapshot = new ArrayList<>();
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (!s.isEmpty()) snapshot.add(s.copy());
            }
        }
        accessor.cugo$addRummagedChest(new ChestMemory(pos, snapshot, type));
        Dev.log("[AbstractChest] Recorded rummage at " + pos + " (" + snapshot.size() + " slots).");
    }

    protected void openContainer(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BARREL)) {
            level.setBlock(pos, state.setValue(BarrelBlock.OPEN, true), 3);
            level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
        } else {
            level.blockEvent(pos, state.getBlock(), 1, 1);
            level.playSound(null, pos,
                    state.is(BlockTags.SHULKER_BOXES) ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.CHEST_OPEN,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    protected void closeContainer(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BARREL)) {
            level.setBlock(pos, state.setValue(BarrelBlock.OPEN, false), 3);
            level.playSound(null, pos, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
        } else {
            level.blockEvent(pos, state.getBlock(), 1, 0);
            level.playSound(null, pos,
                    state.is(BlockTags.SHULKER_BOXES) ? SoundEvents.SHULKER_BOX_CLOSE : SoundEvents.CHEST_CLOSE,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    private boolean canAccessChest(ServerLevel level, CopperGolem golem, BlockPos chestPos) {
        double dy = golem.getY() - (chestPos.getY() + 0.5);
        int verticalDifference = (int) Math.abs(dy);

        // If chest is within 1 block vertically (floor/ceiling adjacent), always allow
        if (verticalDifference <= ConfigHandler.getConfig().losVerticalThreshold) {
            return true;
        }

        // Otherwise require line of sight
        Vec3 eyePos = golem.getEyePosition();
        Vec3 chestCenter = Vec3.atCenterOf(chestPos);
        BlockHitResult hit = level.clip(new ClipContext(
                eyePos, chestCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                golem
        ));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(chestPos);
    }

    // ------------------------------------------------------------------------
    // Reset (override in subclasses and call super)
    // ------------------------------------------------------------------------

    public void reset() {
        phase = StateMachine.Phase.IDLE;
        chestQueue.clear();
        currentTarget = null;
        currentApproachPos = null;
        openingTimer = 0;
        pathCooldown = 0;
        stallTimer = 0;
        lastX = lastZ = Double.NaN;
        usingTMC = false;
    }

    public boolean isDone() {
        return phase == StateMachine.Phase.DONE;
    }

    public boolean isIdle() {
        return phase == StateMachine.Phase.IDLE;
    }
}