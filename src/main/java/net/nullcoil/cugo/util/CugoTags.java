package net.nullcoil.cugo.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.nullcoil.cugo.CuGO;

public class CugoTags {
    public static final TagKey<Block> LOS_IGNORED =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(CuGO.MOD_ID, "los_ignored"));

    public static void register() {
        CuGO.LOGGER.info("Registering tags for CuGO");
    }
}
