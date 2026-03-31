package net.nullcoil.cugo.util;

import net.minecraft.world.entity.ai.control.MoveControl;

public interface MobMoveControlAccessor {
    void cugo$setMoveControl(MoveControl moveControl);
    MoveControl cugo$getMoveControl();
}