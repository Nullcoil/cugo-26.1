package net.nullcoil.cugo.util;

import net.minecraft.core.BlockPos;
import net.nullcoil.cugo.brain.memories.ChestMemory;

import java.util.List;
import java.util.Set;

public interface CugoNBTAccessor {
    void cugo$setHome(BlockPos pos);
    BlockPos cugo$getHome();

    Set<BlockPos> cugo$getSeenChests();
    void cugo$addSeenChests(BlockPos set);
    void cugo$removeSeenChests(BlockPos set);

    // Rummaged chests: chests the golem has physically opened this task cycle.
    // Cleared when the grab behavior resets (item found or queue exhausted).
    List<ChestMemory> cugo$getRummagedChests();
    void cugo$addRummagedChest(ChestMemory memory);
    void cugo$clearRummagedChests();
}