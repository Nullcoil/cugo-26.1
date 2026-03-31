package net.nullcoil.cugo.util;

import net.minecraft.world.level.block.WeatheringCopper;

public interface CugoWeatheringAccessor {
    void cugo$setWeatherState(WeatheringCopper.WeatherState state);

    boolean cugo$isWaxed();
    void cugo$setWaxed(boolean waxed);

    void cugo$convertToStatue(boolean randomizePose);

    public void cugo$startShutdown();
    public WeatheringCopper.WeatherState cugo$getWeatherState();
    public WeatheringCopper.WeatherState cugo$getPreviousWeatherState();
}