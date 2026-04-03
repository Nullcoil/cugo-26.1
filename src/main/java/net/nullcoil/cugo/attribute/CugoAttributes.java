package net.nullcoil.cugo.attribute;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.nullcoil.cugo.CuGO;

public class CugoAttributes {
    public static final Holder<Attribute> BATTERY = Registry.registerForHolder(
            BuiltInRegistries.ATTRIBUTE,
            Identifier.fromNamespaceAndPath(CuGO.MOD_ID, "battery_life"),
            new RangedAttribute("cugo.attribute.battery_life",
                    12000,
                    0,
                    12000)
                    .setSyncable(true)
    );

    public static void register() {
        CuGO.LOGGER.info("Registering CuGO Attributes");
    }
}
