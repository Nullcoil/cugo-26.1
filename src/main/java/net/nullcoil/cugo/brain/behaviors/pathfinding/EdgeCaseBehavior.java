package net.nullcoil.cugo.brain.behaviors.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.nullcoil.cugo.brain.memories.ChestMemory;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.Dev;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class EdgeCaseBehavior extends AbstractChestInteractionBehavior {

    private @Nullable BlockPos homeChest = null;
    private boolean swapOccurred = false;
    private boolean placementSucceeded = false;

    public void primeWithHome(@Nullable BlockPos home) {
        this.homeChest = home;
    }

    public boolean didSwap() {
        return swapOccurred;
    }

    public boolean didPlace() {
        return placementSucceeded;
    }

    // ------------------------------------------------------------------------
    // Abstract method implementations
    // ------------------------------------------------------------------------

    @Override
    protected void buildQueue(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        Set<BlockPos> seenChests = accessor.cugo$getSeenChests();
        chestQueue = buildCopperChestQueue(accessor, seenChests, level);
    }

    @Override
    protected boolean isValidTarget(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CopperChestBlock;
    }

    @Override
    protected void onOpenAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                   @Nullable Container inventory, @NotNull BlockPos chestPos) {
        boolean canPlace = inventory != null && hasRoom(inventory, golem.getMainHandItem());
        golem.setState(canPlace ? CopperGolemState.DROPPING_ITEM : CopperGolemState.DROPPING_NO_ITEM);
        golem.playSound(canPlace ? SoundEvents.COPPER_GOLEM_ITEM_DROP : SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
    }

    @Override
    protected void onCloseAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                    @NotNull BlockPos chestPos) {
        level.playSound(null, chestPos, SoundEvents.COPPER_CHEST_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    @Override
    protected void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                  @Nullable Container inventory, @NotNull BlockPos chestPos) {
        if (inventory != null && hasRoom(inventory, golem.getMainHandItem())) {
            insertReverse(inventory, golem, level);
            recordRummaged(golem, level, chestPos, inventory);
            if (!swapOccurred) placementSucceeded = true;
            phase = StateMachine.Phase.DONE;
            return;
        }
        recordRummaged(golem, level, chestPos, inventory);
        Dev.log("[EdgeCase] No room in " + chestPos + ". Advancing.");
        advanceToNextChest(golem, level);
    }

    // ------------------------------------------------------------------------
    // EdgeCase-specific helpers
    // ------------------------------------------------------------------------

    private List<BlockPos> buildCopperChestQueue(@NotNull CugoNBTAccessor accessor,
                                                 @NotNull Set<BlockPos> seenChests,
                                                 @NotNull ServerLevel level) {
        List<BlockPos> nonHome = new ArrayList<>();
        List<BlockPos> fallback = new ArrayList<>();
        for (BlockPos pos : seenChests) {
            if (!isValidTarget(level, pos)) continue;
            if (pos.equals(homeChest)) {
                fallback.add(pos);
            } else {
                nonHome.add(pos);
            }
        }
        Comparator<BlockPos> byStackCount = Comparator.comparingInt(pos -> countStacks(accessor, pos));
        nonHome.sort(byStackCount);
        if (nonHome.isEmpty()) {
            fallback.sort(byStackCount);
            return fallback;
        }
        return nonHome;
    }

    private int countStacks(@NotNull CugoNBTAccessor accessor, @NotNull BlockPos pos) {
        for (ChestMemory memory : accessor.cugo$getRummagedChests()) {
            if (memory.pos().equals(pos)) return memory.items().size();
        }
        return Integer.MAX_VALUE;
    }

    private boolean hasRoom(@NotNull Container container, @NotNull ItemStack stack) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    private void insertReverse(@NotNull Container container, @NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        ItemStack hand = golem.getMainHandItem().copy();
        int size = container.getContainerSize();

        // Pass 1: merge into matching non-full stacks (back to front)
        for (int i = size - 1; i >= 0 && !hand.isEmpty(); i--) {
            ItemStack slot = container.getItem(i);
            if (ItemStack.isSameItemSameComponents(slot, hand) && slot.getCount() < slot.getMaxStackSize()) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int toAdd = Math.min(space, hand.getCount());
                slot.grow(toAdd);
                hand.shrink(toAdd);
                container.setChanged();
            }
        }

        // Pass 2: place remainder into the last empty slot
        if (!hand.isEmpty()) {
            for (int i = size - 1; i >= 0; i--) {
                if (container.getItem(i).isEmpty()) {
                    container.setItem(i, hand.copy());
                    hand.setCount(0);
                    container.setChanged();
                    break;
                }
            }
        }

        // Pass 3: memory-guided swap
        if (!hand.isEmpty()) {
            for (int i = size - 1; i >= 0; i--) {
                ItemStack slot = container.getItem(i);
                if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, hand)) continue;
                if (knownToWoodenChest(slot, accessor)) {
                    ItemStack displaced = slot.copy();
                    container.setItem(i, hand.copy());
                    container.setChanged();
                    hand = displaced;
                    swapOccurred = true;
                    break;
                }
            }
        }

        golem.setItemInHand(InteractionHand.MAIN_HAND, hand.isEmpty() ? ItemStack.EMPTY : hand);
    }

    private boolean knownToWoodenChest(@NotNull ItemStack stack, @NotNull CugoNBTAccessor accessor) {
        for (ChestMemory memory : accessor.cugo$getRummagedChests()) {
            StateMachine.Container type = memory.type();
            if (type != StateMachine.Container.CHEST && type != StateMachine.Container.DOUCHE) continue;
            for (ItemStack remembered : memory.items()) {
                if (ItemStack.isSameItemSameComponents(remembered, stack)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------------

    @Override
    public void reset() {
        super.reset();
        swapOccurred = false;
        placementSucceeded = false;
        // homeChest is not cleared – it's set by primeWithHome each time.
    }
}