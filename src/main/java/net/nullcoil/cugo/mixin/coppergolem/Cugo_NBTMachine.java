package net.nullcoil.cugo.mixin.coppergolem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.nullcoil.cugo.brain.memories.ChestMemory;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.StateMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(CopperGolem.class)
public class Cugo_NBTMachine implements CugoNBTAccessor {

    @Unique private BlockPos homePos = null;
    @Unique private Set<BlockPos> seenChests = new HashSet<>();
    @Unique private List<ChestMemory> rummagedChests = new ArrayList<>();
    @Unique private Map<BlockPos, BlockPos> accessPositions = new HashMap<>();

    // -------------------------------------------------------------------------
    // SAVE
    // -------------------------------------------------------------------------

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void cugo$saveData(ValueOutput output, CallbackInfo ci) {
        if (this.homePos != null) {
            output.putLong("HomePos", this.homePos.asLong());
        }

        if (!this.seenChests.isEmpty()) {
            ValueOutput.ValueOutputList seenList = output.childrenList("SeenChests");
            for (BlockPos pos : this.seenChests) {
                seenList.addChild().putLong("Pos", pos.asLong());
            }
        }

        if (!this.rummagedChests.isEmpty()) {
            ValueOutput.ValueOutputList rumList = output.childrenList("RummagedChests");
            for (ChestMemory memory : this.rummagedChests) {
                ValueOutput entry = rumList.addChild();
                entry.putLong("Pos", memory.pos().asLong());
                entry.putString("Type", memory.type().name());

                // Items snapshot (may be null if chest was empty when recorded)
                List<ItemStack> items = memory.items();
                if (items != null && !items.isEmpty()) {
                    ValueOutput.ValueOutputList itemList = entry.childrenList("Items");
                    for (ItemStack stack : items) {
                        // Use vanilla codec helpers — same approach used by LootTable / Inventory saves.
                        ValueOutput itemEntry = itemList.addChild();
                        itemEntry.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                        itemEntry.putInt("count", stack.getCount());
                    }
                }
            }
        }

        if (!this.accessPositions.isEmpty()) {
            ValueOutput.ValueOutputList accessList = output.childrenList("AccessPositions");
            for (Map.Entry<BlockPos, BlockPos> entry : this.accessPositions.entrySet()) {
                ValueOutput e = accessList.addChild();
                e.putLong("ChestPos", entry.getKey().asLong());
                e.putLong("AccessPos", entry.getValue().asLong());
            }
        }
    }

    // -------------------------------------------------------------------------
    // LOAD
    // -------------------------------------------------------------------------

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void cugo$loadData(ValueInput input, CallbackInfo ci) {
        long home = input.getLongOr("HomePos", Long.MIN_VALUE);
        this.homePos = (home != Long.MIN_VALUE) ? BlockPos.of(home) : null;

        this.seenChests.clear();
        input.childrenListOrEmpty("SeenChests").forEach(entry -> {
            long l = entry.getLongOr("Pos", Long.MIN_VALUE);
            if (l != Long.MIN_VALUE) this.seenChests.add(BlockPos.of(l));
        });

        this.rummagedChests.clear();
        input.childrenListOrEmpty("RummagedChests").forEach(entry -> {
            long l = entry.getLongOr("Pos", Long.MIN_VALUE);
            if (l == Long.MIN_VALUE) return;

            BlockPos pos = BlockPos.of(l);

            StateMachine.Container type;
            try {
                type = StateMachine.Container.valueOf(entry.getStringOr("Type", "CHEST"));
            } catch (IllegalArgumentException e) {
                type = StateMachine.Container.CHEST;
            }

            List<ItemStack> items = new ArrayList<>();
            entry.childrenListOrEmpty("Items").forEach(itemEntry -> {
                String id = itemEntry.getStringOr("id", "minecraft:air");
                int count = itemEntry.getIntOr("count", 1);
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)), count);
                if (!stack.isEmpty()) items.add(stack);
            });

            this.rummagedChests.add(new ChestMemory(pos, items, type));
        });

        this.accessPositions.clear();
        input.childrenListOrEmpty("AccessPositions").forEach(entry -> {
            long chestL  = entry.getLongOr("ChestPos",  Long.MIN_VALUE);
            long accessL = entry.getLongOr("AccessPos", Long.MIN_VALUE);
            if (chestL != Long.MIN_VALUE && accessL != Long.MIN_VALUE) {
                this.accessPositions.put(BlockPos.of(chestL), BlockPos.of(accessL));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Accessor implementations
    // -------------------------------------------------------------------------

    @Override public void cugo$setHome(BlockPos pos) { this.homePos = pos; }
    @Override public BlockPos cugo$getHome() { return this.homePos; }

    @Override public Set<BlockPos> cugo$getSeenChests() { return this.seenChests; }
    @Override public void cugo$addSeenChests(BlockPos pos) { this.seenChests.add(pos); }
    @Override public void cugo$removeSeenChests(BlockPos pos) { this.seenChests.remove(pos); }

    @Override public List<ChestMemory> cugo$getRummagedChests() { return this.rummagedChests; }
    @Override public void cugo$addRummagedChest(ChestMemory memory) { this.rummagedChests.add(memory); }
    @Override public void cugo$clearRummagedChests() { this.rummagedChests.clear(); }

    @Override public void cugo$setAccessPos(BlockPos chestPos, BlockPos accessPos) {
        this.accessPositions.put(chestPos, accessPos);
    }
    @Override public BlockPos cugo$getAccessPos(BlockPos chestPos) {
        return this.accessPositions.get(chestPos);
    }
    @Override public void cugo$removeAccessPos(BlockPos chestPos) {
        this.accessPositions.remove(chestPos);
    }
}