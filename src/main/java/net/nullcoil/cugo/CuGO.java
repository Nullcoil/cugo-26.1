package net.nullcoil.cugo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.nullcoil.cugo.attribute.CugoAttributes;
import net.nullcoil.cugo.command.CugoCommands;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CuGO implements ModInitializer {
	public static final String MOD_ID = "cugo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Copper Golem Overcomplications");
		CugoAttributes.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				CugoCommands.register(dispatcher)
		);
		ConfigHandler.register();
		CugoTags.register();
	}
}