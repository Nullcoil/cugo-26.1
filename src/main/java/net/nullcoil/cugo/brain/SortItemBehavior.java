package net.nullcoil.cugo.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.Debug;
import net.nullcoil.cugo.util.DoubleChestHelper;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SortItemBehavior implements CugoBehavior {
    private StateMachine.Phase phase = StateMachine.Phase.IDLE;
    private List<BlockPos> chestQueue = new ArrayList<>();
    private @Nullable BlockPos currentTarget = null;
    private int openingTimer = 0;
    private int pathCooldown = 0;
    private static final int OPEN_DURATION = 60;

    @Override
    public void tick(CopperGolem golem, ServerLevel level) {
        if (golem.getMainHandItem().isEmpty()) {
            Debug.log("[SortItem] Item in hand disappeared. Aborting behavior.");
            return;
        }

        if (pathCooldown > 0) pathCooldown--;

        switch(phase) {
            case IDLE -> startBehavior(golem, level);
            case PATHING ->  tickPathing(golem, level);
            case OPENING ->  tickOpening(golem, level);
            case DONE ->  reset();
        }
    }

    private void startBehavior(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;

        if (accessor.cugo$getSeenChests().isEmpty()) {
            Debug.log("[SortItem] No chests found. Aborting behavior.");
            phase = StateMachine.Phase.IDLE;
            return;
        }

        chestQueue = buildChestQueue(accessor.cugo$getSeenChests(), level);
        if(chestQueue.isEmpty()) {
            Debug.log("[SortItem] No chests found. Aborting behavior.");
            phase = StateMachine.Phase.IDLE;
            return;
        }

        advanceToNextChest(golem, level);
    }

    private void tickPathing(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (currentTarget == null) {
            Debug.log("[SortItem] currentTarget is lost during PATHING. Aborting.");
            phase = StateMachine.Phase.DONE;
            return;
        }

        if (!isValidContainer(level, currentTarget)) {
            Debug.log("[SortItem] Target chest at " + currentTarget + " no longer valid. Skipping.");
            advanceToNextChest(golem, level);
            return;
        }

        if (pathCooldown == 0 || golem.getNavigation().isDone()) {
            if (!isCloseEnough(golem, currentTarget)) {
                golem.getNavigation().moveTo(
                        currentTarget.getX() + 0.5,
                        currentTarget.getY(),
                        currentTarget.getZ() + 0.5,
                        1.0d
                );
                pathCooldown = 20;
            }
        }

        if (isCloseEnough(golem, currentTarget)) {
            Debug.log("[SortItem] Arrived at chest " + currentTarget + ". Beginning open sequence.");
            golem.getNavigation().stop();
            openingTimer = 0;
            phase = StateMachine.Phase.OPENING;
        }
    }

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
            BlockState state = level.getBlockState(currentTarget);
            Debug.log("[SortChest] Opening chest at " + currentTarget + " | block=" + state.getBlock());

            golem.setOpenedChestPos(currentTarget);
            level.blockEvent(currentTarget, state.getBlock(), 1, 1);
            level.playSound(null, currentTarget, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS);

            Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
            if (inventory != null && !isEmpty(inventory)) {
                golem.setState(CopperGolemState.DROPPING_ITEM);
                golem.playSound(SoundEvents.COPPER_GOLEM_ITEM_DROP);
            } else {
                golem.setState(CopperGolemState.DROPPING_NO_ITEM);
                golem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
            }
        }

        if (openingTimer >= OPEN_DURATION) resolveOpening(golem, level);
    }

    private void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
        golem.setState(CopperGolemState.IDLE);
        golem.clearOpenedChestPos();
        level.blockEvent(currentTarget, level.getBlockState(currentTarget).getBlock(), 1, 0);
        level.playSound(null, currentTarget, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);

        if (inventory != null && purityCheck(golem, inventory)) {
            insertStack(inventory, golem);
            Debug.log("[SortItem] Item placed into " + currentTarget + ".");
            phase = StateMachine.Phase.DONE;
            return;
        }

        Debug.log("[SortChest] No item placed into " + currentTarget + ". Advancing to next chest.");
        advanceToNextChest(golem, level);
    }

    private void advanceToNextChest(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        golem.clearOpenedChestPos();
        while (!chestQueue.isEmpty()) {
            BlockPos candidate = chestQueue.remove(0);
            if (isValidContainer(level, candidate)) {
                currentTarget = candidate;
                golem.getNavigation().moveTo(
                        candidate.getX() + 0.5,
                        candidate.getY(),
                        candidate.getZ() + 0.5,
                        1.0d
                );
                pathCooldown = 20;
                phase = StateMachine.Phase.PATHING;
                Debug.log("[SortItem] Next target: " + candidate);
                return;
            }
        }

        Debug.log("[SortItem] Queue exhausted. No item found.");
        phase = StateMachine.Phase.DONE;
    }

    public void reset() {
        phase = StateMachine.Phase.IDLE;
        chestQueue.clear();
        currentTarget = null;
        openingTimer = 0;
        pathCooldown = 0;
    }

    private void insertStack(@NotNull Container container, @NotNull CopperGolem golem) {
        ItemStack hand = golem.getMainHandItem();

        for (int i = 0; i < container.getContainerSize(); i++) {
            if (hand.isEmpty()) break;
            ItemStack slot = container.getItem(i);

            if (ItemStack.isSameItemSameComponents(slot, hand)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int toAdd = Math.min(space, hand.getCount());
                slot.grow(toAdd);
                hand.shrink(toAdd);
                container.setChanged();
            } else if (slot.isEmpty()) {
                container.setItem(i, hand.copy());
                hand.setCount(0);
                container.setChanged();
                break;
            }
        }

        golem.setItemInHand(InteractionHand.MAIN_HAND, hand.isEmpty() ? ItemStack.EMPTY : hand);
    }

    private void recordRummaged(
            @NotNull CopperGolem golem,
            @NotNull ServerLevel level,
            @NotNull BlockPos pos,
            @NotNull Container inventory
    ) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        BlockState state = level.getBlockState(pos);
        StateMachine.Container type = new StateMachine().determineContainerType(state);

        List<ItemStack> snapshot = new ArrayList<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty()) snapshot.add(s.copy());
        }

        accessor.cugo$addRummagedChest(new ChestMemory(pos, snapshot, type));
        Debug.log("[SortChest] Recorded rummage at " + pos + " (" + snapshot.size() + " slot(s) with items)");
    }

    private boolean purityCheck(@NotNull CopperGolem golem, @NotNull Container inventory) {
        if(golem.getMainHandItem().isEmpty()) return false;

        List<ItemStack> items = new ArrayList<>();
        for(int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) items.add(stack);
        }

        for (ItemStack item : items) {
            if (ItemStack.isSameItemSameComponents(item, golem.getMainHandItem())) return true;
        }
        return inventory.isEmpty();
    }

    private List<BlockPos> buildChestQueue(
            @NotNull Set<BlockPos> seenChests,
            @NotNull ServerLevel level
    ) {
        List<BlockPos> queue = new ArrayList<>();

        for (BlockPos pos : seenChests) {
            if (isValidContainer(level, pos)) queue.add(pos);
        }
        return queue;
    }

    private boolean isValidContainer(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)
                || (ConfigHandler.getConfig().barrelAsOutput && state.is(Blocks.BARREL))
                || (ConfigHandler.getConfig().shulkerAsOutput && state.is(BlockTags.SHULKER_BOXES));
    }

    private boolean isCloseEnough(@NotNull CopperGolem golem, @NotNull BlockPos target) {
        double dx = golem.getX() - (target.getX() + 0.5);
        double dz = golem.getZ() - (target.getZ() + 0.5);
        double dy = golem.getY() - target.getY();
        double xzRange = ConfigHandler.getConfig().xzInteractRange;
        double yRange = ConfigHandler.getConfig().yInteractRange;
        double xzDistSq = dx * dx + dz * dz;
        return xzDistSq <= (xzRange * xzRange) && Math.abs(dy) <= yRange;
    }

    private boolean isEmpty(@NotNull Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    public boolean isDone() {
        return phase == StateMachine.Phase.DONE;
    }

    public boolean isIdle() {
        return phase == StateMachine.Phase.IDLE;
    }
}
