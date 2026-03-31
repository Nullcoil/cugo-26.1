package net.nullcoil.cugo.util;

import net.minecraft.world.level.block.WeatheringCopper;

public interface CugoWeatheringAccessor {
    WeatheringCopper.WeatherState cugo$getWeatherState();
    void cugo$setWeatherState(WeatheringCopper.WeatherState state);
    WeatheringCopper.WeatherState cugo$getPreviousWeatherState();

    boolean cugo$isWaxed();
    void cugo$setWaxed(boolean waxed);

    void cugo$convertToStatue(boolean randomizePose);

    void cugo$startShutdown();
}