package net.nullcoil.cugo.mixin.mob;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.nullcoil.cugo.util.MobMoveControlAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Mob.class)
public abstract class MobMoveMixin implements MobMoveControlAccessor {
    @Shadow protected MoveControl moveControl;

    @Override
    public void cugo$setMoveControl(MoveControl moveControl) {
        this.moveControl = moveControl;
    }

    @Override
    public MoveControl cugo$getMoveControl() {
        return this.moveControl;
    }
}