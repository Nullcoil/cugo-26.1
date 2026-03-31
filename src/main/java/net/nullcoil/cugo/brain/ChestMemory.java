package net.nullcoil.cugo.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.nullcoil.cugo.util.StateMachine;

import java.util.List;

public record ChestMemory(BlockPos pos, List<ItemStack> items, StateMachine.Container type) {
    public ChestMemory(BlockPos pos, StateMachine.Container type) {
        this(pos, null, type);
    }
}
