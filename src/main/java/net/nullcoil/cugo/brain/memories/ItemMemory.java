package net.nullcoil.cugo.brain.memories;

import net.minecraft.world.item.ItemStack;

public record ItemMemory(ItemStack item, boolean sorted) {
    public ItemMemory(ItemStack item) { this(item, true); }
}
