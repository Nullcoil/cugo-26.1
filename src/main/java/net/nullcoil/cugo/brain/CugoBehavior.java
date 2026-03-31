package net.nullcoil.cugo.brain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public interface CugoBehavior {
    void tick(CopperGolem golem, ServerLevel level);
}
