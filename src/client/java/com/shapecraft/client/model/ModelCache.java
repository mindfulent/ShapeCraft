package com.shapecraft.client.model;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of generated model data, keyed by pool slot index.
 */
public class ModelCache {

    private static final Map<Integer, ModelData> cache = new ConcurrentHashMap<>();

    public static void put(int slotIndex, ModelData data) {
        cache.put(slotIndex, data);
    }

    @Nullable
    public static ModelData get(int slotIndex) {
        return cache.get(slotIndex);
    }

    public static boolean has(int slotIndex) {
        return cache.containsKey(slotIndex);
    }

    public static void clear() {
        cache.clear();
    }

    public static int size() {
        return cache.size();
    }

    public static Map<Integer, ModelData> getAll() {
        return Map.copyOf(cache);
    }

    public record ModelData(
            int slotIndex,
            String displayName,
            String modelJson,
            String upperModelJson,
            String modelJsonOpen,
            String upperModelJsonOpen,
            String blockType,
            String textureTints
    ) {
        public ModelData(int slotIndex, String displayName, String modelJson, String upperModelJson, String textureTints) {
            this(slotIndex, displayName, modelJson, upperModelJson, "", "", "", textureTints);
        }

        public boolean isDoor() {
            return "door".equals(blockType);
        }
    }
}
