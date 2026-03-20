package com.shapecraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shapecraft.ShapeCraft;
import com.shapecraft.license.LicenseState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class LicenseStore {
    private static final Path LICENSE_FILE = Path.of("config/shapecraft/license.json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public record LicenseData(
            String licenseKey,
            String serverId,
            LicenseState state,
            String lastValidated,
            String expiresAt,
            int trialGenerationsRemaining,
            int monthlyUsed,
            String instanceUuid
    ) {}

    public LicenseData load() {
        if (!Files.exists(LICENSE_FILE)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(LICENSE_FILE)) {
            return gson.fromJson(reader, LicenseData.class);
        } catch (IOException e) {
            ShapeCraft.LOGGER.error("Failed to load license.json", e);
            return null;
        }
    }

    public void save(LicenseData data) {
        try {
            Files.createDirectories(LICENSE_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(LICENSE_FILE)) {
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            ShapeCraft.LOGGER.error("Failed to save license.json", e);
        }
    }
}
