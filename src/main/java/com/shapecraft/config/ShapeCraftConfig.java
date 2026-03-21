package com.shapecraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ShapeCraftConfig {

    private static final Path CONFIG_FILE = Path.of("config/shapecraft/config.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int poolSize = ShapeCraftConstants.DEFAULT_POOL_SIZE;
    private int maxGenerationsPerPlayerPerDay = ShapeCraftConstants.DEFAULT_DAILY_CAP;
    private int maxPromptLength = ShapeCraftConstants.MAX_PROMPT_LENGTH;
    private String backendUrl = "https://theblock.academy/api";
    private boolean contentFilterEnabled = true;
    private boolean debug = false;

    public static ShapeCraftConfig load() {
        if (!Files.exists(CONFIG_FILE)) {
            ShapeCraftConfig config = new ShapeCraftConfig();
            config.save();
            return config;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            ShapeCraftConfig config = GSON.fromJson(reader, ShapeCraftConfig.class);
            return config != null ? config : new ShapeCraftConfig();
        } catch (IOException e) {
            ShapeCraft.LOGGER.error("Failed to load config.json", e);
            return new ShapeCraftConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            ShapeCraft.LOGGER.error("Failed to save config.json", e);
        }
    }

    public int getPoolSize() { return poolSize; }
    public int getMaxGenerationsPerPlayerPerDay() { return maxGenerationsPerPlayerPerDay; }
    public int getMaxPromptLength() { return maxPromptLength; }
    public String getBackendUrl() { return backendUrl; }
    public boolean isContentFilterEnabled() { return contentFilterEnabled; }
    public boolean isDebug() { return debug; }
}
