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
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.Dev;
import net.nullcoil.cugo.util.DoubleChestHelper;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FetchItemBehavior implements CugoBehavior {

    private StateMachine.Phase phase = StateMachine.Phase.IDLE;
    private List<BlockPos> chestQueue = new ArrayList<>();
    private @Nullable BlockPos currentTarget = null;
    private int openingTimer = 0;
    private int pathCooldown = 0;
    private static final int OPEN_DURATION = 60;
    private @Nullable BlockPos lastFetchedFrom = null;
    private ItemStack lastFetchedItem = ItemStack.EMPTY;

    // --- Entry Point ---

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        // ABORT: Already holding something — hand the control back immediately.
        if (!golem.getMainHandItem().isEmpty()) {
            Dev.log("[FetchChest] Item in hand detected mid-behavior. Aborting.");
            return; // don't reset — let CugoBrain handle it
        }

        if (pathCooldown > 0) pathCooldown--;

        switch (phase) {
            case IDLE -> startBehavior(golem, level);
            case PATHING -> tickPathing(golem, level);
            case OPENING -> tickOpening(golem, level);
            case DONE -> reset(); // Safety net; CugoBrain should transition us away.
        }
    }

    // --- StateMachine.Phase: IDLE → build queue and pick first target ---

    private void startBehavior(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        BlockPos home = accessor.cugo$getHome();

        if (home == null) {
            Dev.log("[FetchChest] No home chest known. Behavior cannot start.");
            phase = StateMachine.Phase.DONE;
            return;
        }

        // Build the queue: home first, then every other copper chest from seenChests.
        chestQueue = buildCopperChestQueue(home, accessor.cugo$getSeenChests(), level);

        if (chestQueue.isEmpty()) {
            Dev.log("[FetchChest] No valid copper chests found. Behavior cannot start.");
            phase = StateMachine.Phase.DONE;
            return;
        }

        advanceToNextChest(golem, level);
    }

    // --- StateMachine.Phase: PATHING → walk toward currentTarget ---

    private void tickPathing(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        if (currentTarget == null) {
            Dev.log("[FetchChest] currentTarget lost during PATHING. Aborting.");
            phase = StateMachine.Phase.DONE;
            return;
        }

        if (!isValidCopperChest(level, currentTarget)) {
            Dev.log("[FetchChest] Target chest at " + currentTarget + " no longer valid. Skipping.");
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
            Dev.log("[FetchChest] Arrived at chest " + currentTarget + ". Beginning open sequence.");
            golem.getNavigation().stop();
            openingTimer = 0;
            phase = StateMachine.Phase.OPENING;
        }
    }

    // --- StateMachine.Phase: OPENING → play animation, read chest, maybe grab item ---

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
            Dev.log("[FetchChest] Opening chest at " + currentTarget + " | block=" + state.getBlock());

            golem.setOpenedChestPos(currentTarget);
            level.blockEvent(currentTarget, state.getBlock(), 1, 1);
            level.playSound(null, currentTarget, SoundEvents.COPPER_CHEST_OPEN, SoundSource.BLOCKS);

            Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
            if (inventory != null && !isEmpty(inventory)) {
                golem.setState(CopperGolemState.GETTING_ITEM);
                golem.playSound(SoundEvents.COPPER_GOLEM_ITEM_GET);
            } else {
                golem.setState(CopperGolemState.GETTING_NO_ITEM);
                golem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_GET);
            }
        }

        if (openingTimer >= OPEN_DURATION) {
            resolveOpening(golem, level);
        }
    }

    private void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        Container inventory = DoubleChestHelper.getInventory(level, currentTarget);
        golem.setState(CopperGolemState.IDLE);
        golem.clearOpenedChestPos();
        level.blockEvent(currentTarget, level.getBlockState(currentTarget).getBlock(), 1, 0);
        level.playSound(null, currentTarget, SoundEvents.COPPER_CHEST_CLOSE, SoundSource.BLOCKS);

        if (inventory != null && !isEmpty(inventory)) {
            ItemStack grabbed = extractStack(inventory, golem);
            if (!grabbed.isEmpty()) {
                golem.setItemInHand(InteractionHand.MAIN_HAND, grabbed);
                lastFetchedFrom = currentTarget;
                lastFetchedItem = grabbed.copy();
                Dev.log("[FetchChest] Grabbed " + grabbed.getCount() + "x " + grabbed.getItem() + " from " + currentTarget);
                recordRummaged(golem, level, currentTarget, inventory);
                phase = StateMachine.Phase.DONE;
                return;
            }
        }

        recordRummaged(golem, level, currentTarget, inventory);
        Dev.log("[FetchChest] No item grabbed from " + currentTarget + ". Advancing to next chest.");
        advanceToNextChest(golem, level);
    }

    private void advanceToNextChest(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        golem.clearOpenedChestPos();
        while (!chestQueue.isEmpty()) {
            BlockPos candidate = chestQueue.remove(0);
            if (isValidCopperChest(level, candidate)) {
                currentTarget = candidate;
                golem.getNavigation().moveTo(
                        candidate.getX() + 0.5,
                        candidate.getY(),
                        candidate.getZ() + 0.5,
                        1.0d
                );
                pathCooldown = 20;
                phase = StateMachine.Phase.PATHING;
                Dev.log("[FetchChest] Next target: " + candidate);
                return;
            }
        }

        Dev.log("[FetchChest] Queue exhausted. No item found.");
        phase = StateMachine.Phase.DONE;
    }

    public void reset() {
        phase = StateMachine.Phase.IDLE;
        chestQueue.clear();
        currentTarget = null;
        openingTimer = 0;
        pathCooldown = 0;
    }

    public void fullReset() {
        reset();
        lastFetchedFrom = null;
        lastFetchedItem = ItemStack.EMPTY;
    }

    private List<BlockPos> buildCopperChestQueue(
            @NotNull BlockPos home,
            @NotNull Set<BlockPos> seenChests,
            @NotNull ServerLevel level
    ) {
        List<BlockPos> queue = new ArrayList<>();

        // If we have a remembered source, go there first
        if (lastFetchedFrom != null && isValidCopperChest(level, lastFetchedFrom)) {
            queue.add(lastFetchedFrom);
        }

        // Home next (if not already added)
        if (!home.equals(lastFetchedFrom)) {
            queue.add(home);
        }

        // Rest of seen copper chests
        for (BlockPos pos : seenChests) {
            if (!pos.equals(home) && !pos.equals(lastFetchedFrom) && isValidCopperChest(level, pos)) {
                queue.add(pos);
            }
        }
        return queue;
    }

    private boolean isValidCopperChest(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CopperChestBlock && state.is(BlockTags.COPPER_CHESTS);
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

    /**
     * Extract up to maxStackSize items from the first non-empty slot.
     */
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

    public void primeWithTarget(@Nullable BlockPos target) {
        this.lastFetchedFrom = target;
    }

    /**
     * Snapshot the chest's current contents into the rummaged-chests NBT list.
     * If the inventory is null or empty we still record the position so we
     * know we visited it.
     */
    private void recordRummaged(
            @NotNull CopperGolem golem,
            @NotNull ServerLevel level,
            @NotNull BlockPos pos,
            @Nullable Container inventory
    ) {
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
        Dev.log("[FetchChest] Recorded rummage at " + pos + " (" + snapshot.size() + " slot(s) with items).");
    }

    // --- State queries used by CugoBrain ---

    public boolean isDone() {
        return phase == StateMachine.Phase.DONE;
    }

    public boolean isIdle() {
        return phase == StateMachine.Phase.IDLE;
    }

    public @Nullable BlockPos getLastFetchedFrom() { return lastFetchedFrom; }

    public ItemStack getLastFetchedItem() { return lastFetchedItem.copy(); }
}