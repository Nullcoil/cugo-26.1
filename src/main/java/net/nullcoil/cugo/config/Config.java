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

    public int xzInteractRange = 2;
    public int yInteractRange = 6;
    public int maxStackSize = 64;

    public int batteryLife = 10; // in minutes
    public int chargeTime = 30; // in seconds
    public float panicWasteMultiplier = 20;

    public Config() {}
}
