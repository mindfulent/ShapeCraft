package com.shapecraft.generation;

import java.time.Instant;
import java.util.UUID;

public record GenerationRequest(
        String requestId,
        UUID playerUuid,
        String playerName,
        String description,
        Instant requestedAt
) {
    public static GenerationRequest create(UUID playerUuid, String playerName, String description) {
        return new GenerationRequest(
                UUID.randomUUID().toString(),
                playerUuid,
                playerName,
                description,
                Instant.now()
        );
    }
}
