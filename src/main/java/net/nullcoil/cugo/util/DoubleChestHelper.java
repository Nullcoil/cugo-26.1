package net.nullcoil.cugo.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class DoubleChestHelper {
    public static BlockPos getRepresentativePos(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if(!(state.getBlock() instanceof ChestBlock)) return pos;

        ChestType type = state.getValue(ChestBlock.TYPE);
        if(type == ChestType.LEFT) {
            Direction directionToRight = ChestBlock.getConnectedDirection(state);
            return pos.relative(directionToRight);
        }

        return pos;
    }

    public static AABB getInteractionBox(Level level, BlockPos pos,
                                         double horiz, double vert) {
        AABB base = getChestBounds(level, pos); // already covers both halves
        return base.inflate(horiz, vert, horiz);
    }

    @Nullable
    public static BlockPos getOtherHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return null;
        Direction connectedDir = ChestBlock.getConnectedDirection(state);
        return pos.relative(connectedDir);
    }

    public static AABB getChestBounds(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        AABB box = new AABB(pos);

        if(!(state.getBlock() instanceof ChestBlock)) return box;

        if(state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction connectedDir = ChestBlock.getConnectedDirection(state);
            BlockPos connectedPos = pos.relative(connectedDir);

            box = box.minmax(new AABB(connectedPos));
        }

        return box;
    }

    @Nullable
    public static Container getInventory(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // Double chest handling
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, false);
        }

        // General container (barrels, shulker boxes, etc.)
        if (level.getBlockEntity(pos) instanceof Container container) {
            return container;
        }

        return null;
    }

    public static boolean isReachable(Level level, Vec3 entityPos, BlockPos chestPos, double reachDistance) {
        AABB chestBox = getChestBounds(level, chestPos);
        return chestBox.inflate(reachDistance).contains(entityPos);
    }
}
