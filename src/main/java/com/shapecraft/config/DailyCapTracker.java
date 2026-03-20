package com.shapecraft.config;

import com.shapecraft.ShapeCraftConstants;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player daily generation counts. Resets at midnight UTC.
 */
public class DailyCapTracker {

    private final Map<UUID, DailyCount> playerCounts = new ConcurrentHashMap<>();

    public boolean canGenerate(UUID playerUuid) {
        DailyCount count = playerCounts.get(playerUuid);
        if (count == null) return true;
        if (!count.date.equals(today())) return true;
        return count.count < ShapeCraftConstants.DEFAULT_DAILY_CAP;
    }

    public int getRemainingToday(UUID playerUuid) {
        DailyCount count = playerCounts.get(playerUuid);
        if (count == null || !count.date.equals(today())) {
            return ShapeCraftConstants.DEFAULT_DAILY_CAP;
        }
        return Math.max(0, ShapeCraftConstants.DEFAULT_DAILY_CAP - count.count);
    }

    public void recordGeneration(UUID playerUuid) {
        LocalDate today = today();
        playerCounts.compute(playerUuid, (key, existing) -> {
            if (existing == null || !existing.date.equals(today)) {
                return new DailyCount(today, 1);
            }
            return new DailyCount(today, existing.count + 1);
        });
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private record DailyCount(LocalDate date, int count) {}
}
