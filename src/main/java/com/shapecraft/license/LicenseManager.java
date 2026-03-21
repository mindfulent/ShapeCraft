package com.shapecraft.license;

import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.config.DailyCapTracker;
import com.shapecraft.config.LicenseStore;

import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public class LicenseManager {
    private final LicenseStore store;
    private final LicenseValidator validator;
    private final DailyCapTracker dailyCapTracker = new DailyCapTracker();

    private MinecraftServer server;
    private LicenseState state = LicenseState.UNINITIALIZED;
    private String licenseKey;
    private String serverId;
    private String instanceUuid;
    private Instant lastValidated;
    private String expiresAt;
    private int trialGenerationsRemaining;
    private int monthlyUsed;
    private Instant graceStart;

    public LicenseManager(LicenseStore store, LicenseValidator validator) {
        this.store = store;
        this.validator = validator;
    }

    public void initialize(MinecraftServer server, String serverIp, int serverPort) {
        this.server = server;
        LicenseStore.LicenseData data = store.load();
        if (data != null) {
            this.licenseKey = data.licenseKey();
            this.serverId = data.serverId();
            this.state = data.state() != null ? data.state() : LicenseState.UNINITIALIZED;
            this.lastValidated = data.lastValidated() != null ? Instant.parse(data.lastValidated()) : null;
            this.expiresAt = data.expiresAt();
            this.trialGenerationsRemaining = data.trialGenerationsRemaining();
            this.monthlyUsed = data.monthlyUsed();
            this.instanceUuid = data.instanceUuid();
            ShapeCraft.LOGGER.info("Loaded license: state={}, key={}...", state,
                    licenseKey != null && licenseKey.length() > 8 ? licenseKey.substring(0, 8) : "null");
        }

        if (instanceUuid == null) {
            instanceUuid = UUID.randomUUID().toString();
        }
        serverId = computeServerId(serverIp, serverPort, instanceUuid);

        if (state == LicenseState.UNINITIALIZED) {
            provisionTrial();
        }
    }

    private void provisionTrial() {
        ShapeCraft.LOGGER.info("No license found, provisioning trial...");
        validator.provisionTrial(serverId, "Minecraft Server", ShapeCraftConstants.MOD_VERSION)
                .thenAccept(response -> server.execute(() -> {
                    licenseKey = response.licenseKey();
                    trialGenerationsRemaining = response.generationCredits();
                    state = LicenseState.TRIAL;
                    lastValidated = Instant.now();
                    persist();
                    // Wire license key to backend client
                    ShapeCraft.getInstance().getBackendClient().setAuthToken(licenseKey);
                    ShapeCraft.LOGGER.info("Trial provisioned: {} generations", response.generationCredits());
                }))
                .exceptionally(e -> {
                    server.execute(() -> {
                        ShapeCraft.LOGGER.error("Failed to provision trial: {}", e.getMessage());
                        state = LicenseState.EXPIRED;
                    });
                    return null;
                });
    }

    public void periodicValidation(int totalPlayers) {
        if (licenseKey == null || state == LicenseState.UNINITIALIZED) return;

        if (state != LicenseState.EXPIRED && lastValidated != null &&
                Duration.between(lastValidated, Instant.now()).toHours() < ShapeCraftConstants.VALIDATION_HOURS) {
            return;
        }

        validator.validate(licenseKey, serverId, ShapeCraftConstants.MOD_VERSION, totalPlayers)
                .thenAccept(response -> server.execute(() -> {
                    lastValidated = Instant.now();
                    if (response.valid()) {
                        state = LicenseState.valueOf(response.state());
                        expiresAt = response.expiresAt();
                        graceStart = null;
                        if (state == LicenseState.TRIAL && response.generationsRemaining() >= 0) {
                            trialGenerationsRemaining = Math.min(trialGenerationsRemaining, response.generationsRemaining());
                        }
                    } else {
                        state = LicenseState.valueOf(response.state());
                        if (state == LicenseState.EXPIRED) {
                            graceStart = null;
                        }
                    }
                    persist();
                }))
                .exceptionally(e -> {
                    server.execute(() -> {
                        ShapeCraft.LOGGER.warn("Validation failed: {}", e.getMessage());
                        if (state == LicenseState.ACTIVE && graceStart == null) {
                            state = LicenseState.GRACE;
                            graceStart = Instant.now();
                            persist();
                        }
                    });
                    return null;
                });

        if (state == LicenseState.GRACE && graceStart != null) {
            if (Duration.between(graceStart, Instant.now()).toDays() >= ShapeCraftConstants.GRACE_DAYS) {
                state = LicenseState.EXPIRED;
                persist();
            }
        }
    }

    public void activate(String code) {
        validator.activate(code, serverId)
                .thenAccept(response -> server.execute(() -> {
                    licenseKey = response.licenseKey();
                    expiresAt = response.expiresAt();
                    state = LicenseState.ACTIVE;
                    lastValidated = Instant.now();
                    trialGenerationsRemaining = -1;
                    monthlyUsed = 0;
                    graceStart = null;
                    persist();
                    // Wire new license key to backend client
                    ShapeCraft.getInstance().getBackendClient().setAuthToken(licenseKey);
                    ShapeCraft.LOGGER.info("License activated successfully");
                }))
                .exceptionally(e -> {
                    ShapeCraft.LOGGER.error("Activation failed: {}", e.getMessage());
                    return null;
                });
    }

    public boolean canGenerate(UUID playerUuid) {
        if (!isFeatureEnabled()) return false;
        if (state == LicenseState.TRIAL && trialGenerationsRemaining <= 0) return false;
        if (state == LicenseState.ACTIVE && monthlyUsed >= ShapeCraftConstants.MONTHLY_GENERATIONS) return false;
        return dailyCapTracker.canGenerate(playerUuid);
    }

    public String getCannotGenerateReason(UUID playerUuid) {
        if (state == LicenseState.EXPIRED) return "License expired. Use /shapecraft activate <code> to reactivate.";
        if (state == LicenseState.UNINITIALIZED) return "License not initialized yet. Please wait...";
        if (state == LicenseState.TRIAL && trialGenerationsRemaining <= 0)
            return "Trial expired (0 generations remaining). Visit " + ShapeCraftConstants.UPGRADE_URL;
        if (state == LicenseState.ACTIVE && monthlyUsed >= ShapeCraftConstants.MONTHLY_GENERATIONS)
            return "Monthly generation limit reached (" + ShapeCraftConstants.MONTHLY_GENERATIONS + ").";
        if (!dailyCapTracker.canGenerate(playerUuid))
            return "Daily generation limit reached (" + ShapeCraftConstants.DEFAULT_DAILY_CAP + "/day). Try again tomorrow.";
        return "Generation not available.";
    }

    public void recordGeneration(UUID playerUuid) {
        dailyCapTracker.recordGeneration(playerUuid);
        if (state == LicenseState.TRIAL) {
            trialGenerationsRemaining = Math.max(0, trialGenerationsRemaining - 1);
        } else if (state == LicenseState.ACTIVE) {
            monthlyUsed++;
        }
        persist();
    }

    public boolean isFeatureEnabled() {
        return state == LicenseState.TRIAL || state == LicenseState.ACTIVE || state == LicenseState.GRACE;
    }

    public LicenseState getState() { return state; }
    public String getLicenseKey() { return licenseKey; }
    public String getServerId() { return serverId; }
    public String getExpiresAt() { return expiresAt; }
    public int getTrialGenerationsRemaining() { return trialGenerationsRemaining; }
    public int getMonthlyUsed() { return monthlyUsed; }
    public Instant getLastValidated() { return lastValidated; }
    public DailyCapTracker getDailyCapTracker() { return dailyCapTracker; }

    private void persist() {
        store.save(new LicenseStore.LicenseData(
                licenseKey, serverId, state,
                lastValidated != null ? lastValidated.toString() : null,
                expiresAt, trialGenerationsRemaining, monthlyUsed, instanceUuid
        ));
    }

    private static String computeServerId(String ip, int port, String instanceUuid) {
        try {
            String input = ip + ":" + port + ":" + instanceUuid;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
