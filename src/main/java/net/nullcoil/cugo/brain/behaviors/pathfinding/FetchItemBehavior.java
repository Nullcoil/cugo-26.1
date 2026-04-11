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
import net.nullcoil.cugo.brain.behaviors.pathfinding.AbstractChestInteractionBehavior;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.Dev;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FetchItemBehavior extends AbstractChestInteractionBehavior {

    private @Nullable BlockPos lastFetchedFrom = null;
    private ItemStack lastFetchedItem = ItemStack.EMPTY;

    // ------------------------------------------------------------------------
    // Abstract method implementations
    // ------------------------------------------------------------------------

    @Override
    protected void buildQueue(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        BlockPos home = accessor.cugo$getHome();
        if (home == null) {
            Dev.log("[FetchItem] No home chest known. Queue empty.");
            chestQueue.clear();
            return;
        }

        chestQueue = buildCopperChestQueue(home, accessor.cugo$getSeenChests(), level);
    }

    @Override
    protected boolean isValidTarget(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CopperChestBlock;
    }

    @Override
    protected void onOpenAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                   @Nullable Container inventory, @NotNull BlockPos chestPos) {
        boolean hasItem = inventory != null && !isEmpty(inventory);
        golem.setState(hasItem ? CopperGolemState.GETTING_ITEM : CopperGolemState.GETTING_NO_ITEM);
        golem.playSound(hasItem ? SoundEvents.COPPER_GOLEM_ITEM_GET : SoundEvents.COPPER_GOLEM_ITEM_NO_GET);
    }

    @Override
    protected void onCloseAnimation(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                    @NotNull BlockPos chestPos) {
        level.playSound(null, chestPos, SoundEvents.COPPER_CHEST_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    @Override
    protected void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                  @Nullable Container inventory, @NotNull BlockPos chestPos) {
        if (inventory != null && !isEmpty(inventory)) {
            ItemStack grabbed = extractStack(inventory, golem);
            if (!grabbed.isEmpty()) {
                golem.setItemInHand(InteractionHand.MAIN_HAND, grabbed);
                lastFetchedFrom = chestPos;
                lastFetchedItem = grabbed.copy();
                Dev.log("[FetchItem] Grabbed " + grabbed.getCount() + "x " + grabbed.getItem() + " from " + chestPos);
                recordRummaged(golem, level, chestPos, inventory);
                phase = StateMachine.Phase.DONE;
                return;
            }
        }
        recordRummaged(golem, level, chestPos, inventory);
        Dev.log("[FetchItem] No item grabbed from " + chestPos + ". Advancing.");
        advanceToNextChest(golem, level);
    }

    // ------------------------------------------------------------------------
    // Fetch-specific helpers
    // ------------------------------------------------------------------------

    private List<BlockPos> buildCopperChestQueue(@NotNull BlockPos home,
                                                 @NotNull Set<BlockPos> seenChests,
                                                 @NotNull ServerLevel level) {
        List<BlockPos> queue = new ArrayList<>();
        if (lastFetchedFrom != null && isValidTarget(level, lastFetchedFrom)) {
            queue.add(lastFetchedFrom);
        }
        if (!home.equals(lastFetchedFrom)) {
            queue.add(home);
        }
        for (BlockPos pos : seenChests) {
            if (!pos.equals(home) && !pos.equals(lastFetchedFrom) && isValidTarget(level, pos)) {
                queue.add(pos);
            }
        }
        return queue;
    }

    private ItemStack extractStack(@NotNull Container container, @NotNull CopperGolem golem) {
        int max = ConfigHandler.getConfig().maxStackSize;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty()) {
                int toTake = Math.min(slot.getCount(), max);
                ItemStack taken = slot.copyWithCount(toTake);
                slot.shrink(toTake);
                if (slot.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                container.setChanged();
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isEmpty(@NotNull Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Public API for CugoBrain
    // ------------------------------------------------------------------------

    public void primeWithTarget(@Nullable BlockPos target) {
        this.lastFetchedFrom = target;
    }

    public @Nullable BlockPos getLastFetchedFrom() {
        return lastFetchedFrom;
    }

    public ItemStack getLastFetchedItem() {
        return lastFetchedItem.copy();
    }

    public void fullReset() {
        reset();
        lastFetchedFrom = null;
        lastFetchedItem = ItemStack.EMPTY;
    }

    @Override
    public void reset() {
        super.reset();
        // Do not clear lastFetchedFrom/item here – fullReset does that.
    }
}