package com.shapecraft.generation;

public record GenerationResult(
        String generationId,
        String modelJson,
        String displayName,
        String textureTints,
        int inputTokens,
        int outputTokens
) {}
