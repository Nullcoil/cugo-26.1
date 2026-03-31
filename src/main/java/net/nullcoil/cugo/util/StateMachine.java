package net.nullcoil.cugo.util;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

public class StateMachine {
    public enum State {
        WANDERING,
        LINGERING,
        PINGING,
        FETCHING,
        SORTING,
        PANIC
    }

    public enum Phase {
        IDLE,
        PATHING,
        OPENING,
        DONE
    }

    public enum PanicPhase {
        RUNNING,
        SEEKING_GUARDIAN,
        HIDING
    }

    public enum Container {
        CHEST,
        DOUCHE,   // Double Chest
        COPPER,
        DOUCOP,   // Double Copper Chest
        TRAPPED,
        DOUTRA,   // Double Trapped Chest
        BARREL,
        SHULKER   // Shulker Box
    }

    public Container determineContainerType(BlockState state) {
        if (state.getBlock() instanceof BarrelBlock) return Container.BARREL;
        if (state.getBlock() instanceof ShulkerBoxBlock) return Container.SHULKER;

        if (state.getBlock() instanceof ChestBlock) {
            boolean isDouble = state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE;

            if (state.is(BlockTags.COPPER_CHESTS)) {
                return isDouble ? Container.DOUCOP : Container.COPPER;
            }

            if (state.getBlock() == Blocks.TRAPPED_CHEST) {
                return isDouble ? Container.DOUTRA : Container.TRAPPED;
            }

            return isDouble ? Container.DOUCHE : Container.CHEST;
        }

        return Container.CHEST;
    }
}