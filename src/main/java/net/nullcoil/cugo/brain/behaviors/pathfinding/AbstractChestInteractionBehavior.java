package net.nullcoil.cugo.brain.behaviors.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nullcoil.cugo.brain.CugoBehavior;
import net.nullcoil.cugo.brain.memories.ChestMemory;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class AbstractChestInteractionBehavior implements CugoBehavior {

    protected StateMachine.Phase phase = StateMachine.Phase.IDLE;

    protected List<BlockPos> chestQueue = new ArrayList<>();
    protected @Nullable BlockPos currentTarget = null;
    protected int openingTimer = 0;
    protected int pathCooldown = 0;        // used only to avoid spamming moveTo calls


    protected static final int OPEN_DURATION = 60;

    // ------------------------------------------------------------------------
    // Abstract methods (unchanged)
    // ------------------------------------------------------------------------

    protected abstract void buildQueue(@NotNull CopperGolem golem, @NotNull ServerLevel level);
    protected abstract boolean isValidTarget(@NotNull ServerLevel level, @NotNull BlockPos pos);
    protected abstract void onOpenAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                            @Nullable Container inventory, @NotNull BlockPos chestPos);
    protected abstract void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                           @Nullable Container inventory, @NotNull BlockPos chestPos);

    protected void onCloseAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                    @NotNull BlockPos chestPos) {}

    // ------------------------------------------------------------------------
    // Main tick (simplified)
    // ------------------------------------------------------------------------

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
            case DONE -> {}
        }
    }

    // ------------------------------------------------------------------------
    // New pathfinding – exactly like BatteryBehavior
    // ------------------------------------------------------------------------

    private boolean isInInteractionBox(@NotNull CopperGolem golem, @NotNull BlockPos chestPos) {
        double xzRange = ConfigHandler.getConfig().xzInteractRange;
        double yRange  = ConfigHandler.getConfig().yInteractRange;
        AABB box = DoubleChestHelper.getInteractionBox(golem.level(), chestPos, xzRange, yRange);
        return box.contains(golem.getX(), golem.getY() + 0.5, golem.getZ());
    }

    private void tickPathing(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (currentTarget == null) { phase = StateMachine.Phase.DONE; return; }

        if (isInInteractionBox(golem, currentTarget)) {
            golem.getNavigation().stop();
            openingTimer = 0;
            phase = StateMachine.Phase.OPENING;
            return;
        }

        if (!hasLineOfSight(level, golem, currentTarget)) {
            Dev.log("[AbstractChest] No LOS to " + currentTarget + " — skipping.");
            advanceToNextChest(golem, level);
            return;
        }

        if (!isValidTarget(level, currentTarget)) {
            advanceToNextChest(golem, level);
            return;
        }

        // Just pathfind toward the chest center — navigator picks the route
        if (pathCooldown == 0 || golem.getNavigation().isDone()) {
            Vec3 center = AABB.ofSize(
                            Vec3.atCenterOf(currentTarget), 0, 0, 0) // just the center point
                    .getCenter(); // or simply:
            double cx = currentTarget.getX() + 0.5;
            double cy = currentTarget.getY();
            double cz = currentTarget.getZ() + 0.5;
            golem.getNavigation().moveTo(cx, cy, cz, 1.1);
            pathCooldown = 10;
        }
    }



    // ------------------------------------------------------------------------
    // Opening sequence
    // ------------------------------------------------------------------------

    private void tickOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        // In tickOpening, at the top of the openingTimer == 1 block:
        assert currentTarget != null;
        if (!hasLineOfSight(level, golem, currentTarget)) {
            Dev.log("[AbstractChest] Lost LOS during opening — aborting.");
            golem.clearOpenedChestPos();
            advanceToNextChest(golem, level);
            return;
        }

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
            // Try rep pos first (RIGHT), then other half (LEFT)
            BlockPos openFrom = resolveOpenSide(level, currentTarget);
            golem.setOpenedChestPos(openFrom);
            openContainer(level, openFrom);

            Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
            onOpenAnimation(golem, level, inventory, currentTarget);
        }

        if (openingTimer >= OPEN_DURATION) {
            BlockPos openFrom = resolveOpenSide(level, currentTarget);
            Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
            golem.setState(CopperGolemState.IDLE);
            golem.clearOpenedChestPos();
            closeContainer(level, openFrom);
            onCloseAnimation(golem, level, currentTarget);
            resolveOpening(golem, level, inventory, currentTarget);
        }
    }

    private @NotNull BlockPos resolveOpenSide(@NotNull ServerLevel level, @NotNull BlockPos repPos) {
        // Rep pos is always RIGHT. Try it first.
        BlockState state = level.getBlockState(repPos);
        if (state.getBlock() instanceof ChestBlock) return repPos;

        // Fall back to the other half (LEFT)
        BlockPos other = DoubleChestHelper.getOtherHalf(level, repPos);
        if (other != null) {
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() instanceof ChestBlock) return other;
        }

        return repPos; // last resort
    }

    // ------------------------------------------------------------------------
    // Queue management (simplified – no approach positions)
    // ------------------------------------------------------------------------

    protected void advanceToNextChest(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        golem.clearOpenedChestPos();
        while (!chestQueue.isEmpty()) {
            BlockPos candidate = chestQueue.remove(0);
            if (isValidTarget(level, candidate)) {
                currentTarget = candidate;
                pathCooldown = 0;
                phase = StateMachine.Phase.PATHING;
                Dev.log("[AbstractChest] Next target: " + candidate);
                return;
            }
        }
        phase = StateMachine.Phase.DONE;
    }

    // ------------------------------------------------------------------------
    // Helpers (unchanged)
    // ------------------------------------------------------------------------

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

    private boolean hasLineOfSight(@NotNull ServerLevel level, @NotNull CopperGolem golem, @NotNull BlockPos chestPos) {
        int dy = chestPos.getY() - golem.blockPosition().getY();
        if (dy >= -1 && dy <= 2) return true;
        Vec3 eyes = golem.getEyePosition();
        Vec3 chestCenter = Vec3.atCenterOf(chestPos);

        // Step along the line from eyes to chest center
        Vec3 delta = chestCenter.subtract(eyes);
        double distance = delta.length();
        int steps = (int) Math.ceil(distance * 2); // 2 samples per block
        Vec3 step = delta.scale(1.0 / steps);

        for (int i = 1; i < steps; i++) {
            Vec3 point = eyes.add(step.scale(i));
            BlockPos blockAt = BlockPos.containing(point);

            // Skip the chest block itself and the golem's own position
            if (blockAt.equals(chestPos) || blockAt.equals(golem.blockPosition())) continue;

            BlockState state = level.getBlockState(blockAt);
            if (state.isAir()) continue;
            if (state.is(CugoTags.LOS_IGNORED)) continue;
            if (!state.isSolid()) continue;

            Dev.log("[AbstractChest] LOS blocked at " + blockAt + " by " + state.getBlock());
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------------

    public void reset() {
        phase = StateMachine.Phase.IDLE;
        chestQueue.clear();
        currentTarget = null;
        openingTimer = 0;
        pathCooldown = 0;
    }

    public boolean isDone() {
        return phase == StateMachine.Phase.DONE;
    }

    public boolean isIdle() {
        return phase == StateMachine.Phase.IDLE;
    }
}