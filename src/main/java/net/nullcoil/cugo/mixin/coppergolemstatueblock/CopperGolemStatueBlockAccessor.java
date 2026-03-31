package net.nullcoil.cugo.mixin.coppergolemstatueblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CopperGolemStatueBlock.class)
public interface CopperGolemStatueBlockAccessor {
    @Invoker("updatePose")
    void invokeUpdatePose(Level level, BlockState blockState, BlockPos blockPos, Player player);
}
