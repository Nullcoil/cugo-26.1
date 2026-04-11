package net.nullcoil.cugo.brain.behaviors.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.nullcoil.cugo.brain.memories.ChestMemory;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.Dev;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SortItemBehavior extends AbstractChestInteractionBehavior {

    private @Nullable BlockPos memorizedChest = null;
    private ItemStack memorizedItem = ItemStack.EMPTY;
    private boolean lastSortSucceeded = false;

    // ------------------------------------------------------------------------
    // Abstract method implementations
    // ------------------------------------------------------------------------

    @Override
    protected void buildQueue(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        if (accessor.cugo$getSeenChests().isEmpty()) {
            chestQueue.clear();
            return;
        }
        chestQueue = buildChestQueue(golem, accessor.cugo$getSeenChests(), level);
    }

    @Override
    protected boolean isValidTarget(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)
                || (ConfigHandler.getConfig().barrelAsOutput && state.is(Blocks.BARREL))
                || (ConfigHandler.getConfig().shulkerAsOutput && state.is(BlockTags.SHULKER_BOXES));
    }

    @Override
    protected void onOpenAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                   @Nullable Container inventory, @NotNull BlockPos chestPos) {
        boolean canPlace = inventory != null && purityCheck(golem, inventory);
        golem.setState(canPlace ? CopperGolemState.DROPPING_ITEM : CopperGolemState.DROPPING_NO_ITEM);
        golem.playSound(canPlace ? SoundEvents.COPPER_GOLEM_ITEM_DROP : SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
    }

    @Override
    protected void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                  @Nullable Container inventory, @NotNull BlockPos chestPos) {
        if (inventory != null && purityCheck(golem, inventory)) {
            insertStack(inventory, golem);
            Dev.log("[SortItem] Item placed into " + chestPos);
            recordRummaged(golem, level, chestPos, inventory);
            lastSortSucceeded = true;
            phase = StateMachine.Phase.DONE;
            return;
        }
        recordRummaged(golem, level, chestPos, inventory);
        Dev.log("[SortItem] No placement in " + chestPos + ". Advancing.");
        advanceToNextChest(golem, level);
        lastSortSucceeded = false;
    }

    // ------------------------------------------------------------------------
    // Sort-specific helpers
    // ------------------------------------------------------------------------

    private List<BlockPos> buildChestQueue(@NotNull CopperGolem golem,
                                           @NotNull Set<BlockPos> seenChests,
                                           @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        List<BlockPos> queue = new ArrayList<>();

        if (memorizedChest != null && isValidTarget(level, memorizedChest)) {
            queue.add(memorizedChest);
        }

        if (!memorizedItem.isEmpty()) {
            for (ChestMemory memory : accessor.cugo$getRummagedChests()) {
                if (memory.pos().equals(memorizedChest)) continue;
                for (ItemStack remembered : memory.items()) {
                    if (ItemStack.isSameItemSameComponents(remembered, memorizedItem)) {
                        if (isValidTarget(level, memory.pos())) {
                            queue.add(memory.pos());
                        }
                        break;
                    }
                }
            }
        }

        for (BlockPos pos : seenChests) {
            if (!pos.equals(memorizedChest) && isValidTarget(level, pos)) {
                if (queue.stream().noneMatch(p -> p.equals(pos))) {
                    queue.add(pos);
                }
            }
        }
        return queue;
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

    private boolean purityCheck(@NotNull CopperGolem golem, @NotNull Container inventory) {
        if (golem.getMainHandItem().isEmpty()) return false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, golem.getMainHandItem())) {
                return true;
            }
        }
        return inventory.isEmpty();
    }

    // ------------------------------------------------------------------------
    // Public API for CugoBrain
    // ------------------------------------------------------------------------

    public void primeWithMemory(@Nullable BlockPos sourceChest, @NotNull ItemStack item) {
        this.memorizedChest = sourceChest;
        this.memorizedItem = item;
    }

    public boolean lastSortSucceeded() {
        return lastSortSucceeded;
    }

    public void fullReset() {
        reset();
        memorizedChest = null;
        memorizedItem = ItemStack.EMPTY;
    }

    @Override
    public void reset() {
        super.reset();
        lastSortSucceeded = false;
        // Do not clear memorizedChest/item here – fullReset does that.
    }
}