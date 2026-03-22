package com.shapecraft.generation;

public record GenerationResult(
        String generationId,
        String modelJson,
        String upperModelJson,
        String modelJsonOpen,
        String upperModelJsonOpen,
        String blockType,
        String displayName,
        String textureTints,
        int inputTokens,
        int outputTokens
) {}
