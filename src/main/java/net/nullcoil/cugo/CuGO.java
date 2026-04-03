package net.nullcoil.cugo;

import net.fabricmc.api.ModInitializer;
import net.nullcoil.cugo.attribute.CugoAttributes;
import net.nullcoil.cugo.config.ConfigHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CuGO implements ModInitializer {
	public static final String MOD_ID = "cugo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Copper Golem Overcomplications");
		CugoAttributes.register();
		ConfigHandler.register();
	}
}