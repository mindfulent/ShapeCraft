package com.shapecraft;

public final class ShapeCraftConstants {
    public static final String MOD_ID = "shapecraft";
    public static final String MOD_VERSION = "0.3.0";
    public static final int PROTOCOL_VERSION = 1;

    // Block pool
    public static final int DEFAULT_POOL_SIZE = 64;
    public static final String POOL_BLOCK_PREFIX = "custom_";

    // Generation limits
    public static final int MAX_PROMPT_LENGTH = 200;
    public static final int MAX_ELEMENTS = 16;
    public static final int DEFAULT_DAILY_CAP = 10;

    // License
    public static final int TRIAL_GENERATIONS = 50;
    public static final int MONTHLY_GENERATIONS = 250;
    public static final int VALIDATION_HOURS = 24;
    public static final int GRACE_DAYS = 7;
    public static final int VALIDATION_POLL_TICKS = 6000; // ~5 minutes

    // URLs
    public static final String UPGRADE_URL = "https://theblockacademy.com/shapecraft";
    public static final String DISCORD_URL = "https://discord.gg/ggvCkBD9vm";

    // Backend
    public static final int GENERATION_TIMEOUT_SECONDS = 30;
    public static final int GENERATION_THREAD_POOL_SIZE = 2;

    private ShapeCraftConstants() {}
}
