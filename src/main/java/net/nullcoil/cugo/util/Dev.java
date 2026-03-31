package net.nullcoil.cugo.util;

import net.nullcoil.cugo.CuGO;
import net.nullcoil.cugo.config.ConfigHandler;

public class Dev {
    public static void log(Object obj) {
        if(ConfigHandler.getConfig().debugMode) CuGO.LOGGER.info("[CuGO DEBUG] {}", obj.toString());
    }
}
