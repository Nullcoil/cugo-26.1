package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CopperGolem.class)
public class Cugo_DropEquipmentMixin {
    @Inject(method = "dropEquipment", at = @At("TAIL"))
    private void cugo$dropHeldItemOnDeath(ServerLevel level, CallbackInfo ci) {
        CopperGolem golem = (CopperGolem) (Object) this;
        ItemStack heldItem = golem.getMainHandItem();

        if(!heldItem.isEmpty()) {
            golem.spawnAtLocation(level, heldItem);
            golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }
}
