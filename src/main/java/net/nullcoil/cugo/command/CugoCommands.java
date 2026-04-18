package net.nullcoil.cugo.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.phys.AABB;
import net.nullcoil.cugo.attribute.CugoAttributes;
import net.nullcoil.cugo.config.ConfigHandler;

public class CugoCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("cugo")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .then(Commands.literal("battery")
                                .then(Commands.literal("set")
                                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> setBattery(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "percent"))))))
        );
    }

    private static int setBattery(CommandSourceStack source, int percent) {
        double max = ConfigHandler.getConfig().batteryLife * 60 * 20.0;
        double targetValue = max * (percent / 100.0);

        int[] count = {0}; // effectively final wrapper

        for (ServerLevel level : source.getServer().getAllLevels()) {
            AABB everywhere = new AABB(
                    -29999984, level.getMinY(), -29999984,
                    29999984, level.getMaxY(), 29999984
            );

            level.getEntitiesOfClass(CopperGolem.class, everywhere).forEach(golem -> {
                var attr = golem.getAttribute(CugoAttributes.BATTERY);
                if (attr != null) {
                    attr.setBaseValue(targetValue);
                    count[0]++;
                }
            });
        }

        source.sendSuccess(() -> Component.literal(
                String.format("Set battery to %d%% on %d CuGO(s).", percent, count[0])), true);
        return count[0];
    }
}