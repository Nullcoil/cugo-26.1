package net.nullcoil.cugo.config;

public class Config {
    public boolean debugMode = true;
    public boolean redstoneBoost = true;
    public boolean rechargeableStatues = true;
    public boolean rechargeableGolems = false;
    public boolean damageItemsOnUse = true;
    public boolean barrelAsOutput = true;
    public boolean shulkerAsOutput = true;

    public int wanderRadius = 10;
    public int wanderTime = 15;
    public int lingerTime = 5;
    public int searchRadius = 32;

    public int panicTime = 5;

    public double xzInteractRange = 1.5;
    public double yInteractRange = 6;
    public int maxStackSize = 64;

    public int batteryLife = 10; // in minutes
    public int warningThreshold = 1; // in minutes
    public int batteryDrain = 1;
    public int chargeTime = 30; // in seconds. By default, from 0% to 100%, it takes 30 seconds.
    public int panicWasteMultiplier = 20; // while panicked, charge time goes down by this value per tick.

    public boolean passiveCharge = true; // if true, CuGO can charge by casually walking through a valid charge location
    public double passiveChargeRateMultiplier = 0.25; // if passiveCharge is true, this affects the rate of charge

    public boolean consumeConsumables = true;
    public int losVerticalThreshold = 1;

    public Config() {}
}
