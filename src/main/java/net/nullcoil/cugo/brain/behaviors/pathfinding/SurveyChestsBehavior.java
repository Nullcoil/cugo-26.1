package net.nullcoil.cugo.brain.behaviors.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
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
import java.util.stream.Collectors;

public class SurveyChestsBehavior extends AbstractChestInteractionBehavior {
    @Override
    protected void buildQueue(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        CugoNBTAccessor acc = (CugoNBTAccessor) golem;
        if (acc.cugo$getSeenChests().isEmpty()) {
            chestQueue.clear();
            return;
        }
        chestQueue = buildChestQueue(golem, acc.cugo$getSeenChests(), level);
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
                                   @Nullable Container inventory, @NotNull BlockPos chest) {
        golem.setState(CopperGolemState.DROPPING_NO_ITEM);
        golem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
    }

    @Override
    protected void resolveOpening(@NotNull CopperGolem golem, @NotNull ServerLevel level,
                                  @Nullable Container inventory, @NotNull BlockPos chest) {
        if (inventory != null) {
            recordRummaged(golem, level, chest, inventory);
            phase = StateMachine.Phase.DONE;
            Dev.log("[Survey] Recorded Rummage in " + chest + ". Advancing.");
            advanceToNextChest(golem, level);
        }
    }

    private List<BlockPos> buildChestQueue(@NotNull CopperGolem golem,
                                           @NotNull Set<BlockPos> seenChests,
                                           @NotNull ServerLevel level) {
        CugoNBTAccessor acc = (CugoNBTAccessor) golem;
        List<BlockPos> queue = new ArrayList<>();

        Set<BlockPos> rummagedPositions = acc.cugo$getRummagedChests()
                .stream()
                .map(ChestMemory::pos)
                .collect(Collectors.toSet());

        List<BlockPos> unrummaged = new ArrayList<>();
        List<BlockPos> rummaged   = new ArrayList<>();

        for (BlockPos pos : seenChests) {
            if (!isValidTarget(level, pos)) continue;
            if (queue.stream().anyMatch(p -> p.equals(pos))) continue;

            if (rummagedPositions.contains(pos)) {
                rummaged.add(pos);
            } else {
                unrummaged.add(pos);
            }
        }

        queue.addAll(unrummaged);
        queue.addAll(rummaged);

        return queue;
    }
}