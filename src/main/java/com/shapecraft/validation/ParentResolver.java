package com.shapecraft.validation;

import com.google.gson.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lazy-loaded registry of pre-computed parent model data (elements + textures).
 * Resolves parent-only models at runtime so they render correctly.
 */
public final class ParentResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("ShapeCraft");
    private static final String RESOURCE_PATH = "/data/shapecraft/parent_models.json";

    public record ResolvedParent(JsonArray elements, Map<String, String> textures, boolean ambientOcclusion) {}

    private static volatile Map<String, ResolvedParent> registry;

    /**
     * Looks up a parent by reference (e.g. "minecraft:block/cube_column" or "block/cube_column").
     * Returns null if the parent is unknown or has no elements.
     */
    public static @Nullable ResolvedParent resolve(String parentRef) {
        ensureLoaded();
        String key = parentRef.replace("minecraft:", "");
        return registry.get(key);
    }

    private static void ensureLoaded() {
        if (registry != null) return;
        synchronized (ParentResolver.class) {
            if (registry != null) return;
            registry = loadRegistry();
        }
    }

    private static Map<String, ResolvedParent> loadRegistry() {
        Map<String, ResolvedParent> map = new HashMap<>();
        try (InputStream is = ParentResolver.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                LOGGER.error("[ParentResolver] Resource not found: {}", RESOURCE_PATH);
                return map;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : root.entrySet()) {
                String key = entry.getKey();
                JsonObject obj = entry.getValue().getAsJsonObject();

                JsonArray elements = obj.has("elements") ? obj.getAsJsonArray("elements") : new JsonArray();
                if (elements.isEmpty()) continue; // Skip display-only parents (block/block, block/thin_block)

                Map<String, String> textures = new HashMap<>();
                if (obj.has("textures")) {
                    JsonObject texObj = obj.getAsJsonObject("textures");
                    for (var tex : texObj.entrySet()) {
                        textures.put(tex.getKey(), tex.getValue().getAsString());
                    }
                }

                boolean ao = !obj.has("ambientocclusion") || obj.get("ambientocclusion").getAsBoolean();

                map.put(key, new ResolvedParent(elements, textures, ao));
            }
            LOGGER.info("[ParentResolver] Loaded {} parent models", map.size());
        } catch (Exception e) {
            LOGGER.error("[ParentResolver] Failed to load parent registry: {}", e.getMessage());
        }
        return map;
    }

    private ParentResolver() {}
}
