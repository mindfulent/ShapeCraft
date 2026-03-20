package com.shapecraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.shapecraft.client.ShapeCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tinted textures for ShapeCraft blocks.
 *
 * Loads vanilla base textures, applies color tints, and stores the resulting
 * NativeImage data for injection into the block atlas during resource reload.
 *
 * Tint format in texture_tints JSON:
 * {
 *   "#texture_var": { "color": "#FF8800", "strength": 0.5 },
 *   "minecraft:block/oak_planks": { "color": "#CC0000", "strength": 0.3 }
 * }
 */
public class DynamicTextureManager {

    private static final Map<ResourceLocation, NativeImage> generatedTextures = new ConcurrentHashMap<>();

    /**
     * Process texture tints for a model and generate tinted NativeImages.
     *
     * @param textureTintsJson JSON string of texture tint definitions
     * @param textureMap       The model's texture variable to path mapping
     * @return Map of generated texture ResourceLocations to their original base textures
     */
    public static Map<String, ResourceLocation> processTints(
            String textureTintsJson, Map<String, String> textureMap) {

        Map<String, ResourceLocation> remappings = new ConcurrentHashMap<>();

        if (textureTintsJson == null || textureTintsJson.isEmpty() || textureTintsJson.equals("{}")) {
            return remappings;
        }

        try {
            var tints = com.google.gson.JsonParser.parseString(textureTintsJson).getAsJsonObject();

            for (var entry : tints.entrySet()) {
                String key = entry.getKey();
                var tintDef = entry.getValue().getAsJsonObject();

                String colorHex = tintDef.get("color").getAsString();
                float strength = tintDef.has("strength") ? tintDef.get("strength").getAsFloat() : 0.5f;

                // Resolve the actual texture path
                String basePath = key;
                if (key.startsWith("#")) {
                    String varName = key.substring(1);
                    basePath = textureMap.getOrDefault(varName, null);
                    if (basePath == null) continue;
                }

                // Generate deterministic ID
                ResourceLocation generatedId = generateId(basePath, colorHex, strength);

                // Create tinted texture if not already cached
                if (!generatedTextures.containsKey(generatedId)) {
                    NativeImage tinted = createTintedTexture(basePath, colorHex, strength);
                    if (tinted != null) {
                        generatedTextures.put(generatedId, tinted);
                    }
                }

                if (generatedTextures.containsKey(generatedId)) {
                    remappings.put(key, generatedId);
                }
            }
        } catch (Exception e) {
            ShapeCraftClient.LOGGER.warn("[Texture] Failed to process tints: {}", e.getMessage());
        }

        return remappings;
    }

    /**
     * Get all generated textures for atlas injection.
     */
    public static Map<ResourceLocation, NativeImage> getGeneratedTextures() {
        return Map.copyOf(generatedTextures);
    }

    /**
     * Clear all cached textures. Called on resource reload.
     */
    public static void clear() {
        // Note: NativeImages in the map are owned by the atlas after injection,
        // so we don't close them here
        generatedTextures.clear();
    }

    private static NativeImage createTintedTexture(String basePath, String colorHex, float strength) {
        try {
            // Parse color
            int color = parseColor(colorHex);
            int tintR = (color >> 16) & 0xFF;
            int tintG = (color >> 8) & 0xFF;
            int tintB = color & 0xFF;

            // Load base texture
            ResourceLocation texLoc;
            if (basePath.contains(":")) {
                texLoc = ResourceLocation.parse(basePath);
            } else {
                texLoc = ResourceLocation.withDefaultNamespace(basePath);
            }

            ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(
                    texLoc.getNamespace(),
                    "textures/" + texLoc.getPath() + ".png"
            );

            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texturePath);
            if (resource.isEmpty()) {
                ShapeCraftClient.LOGGER.warn("[Texture] Base texture not found: {}", texturePath);
                return null;
            }

            NativeImage base;
            try (InputStream is = resource.get().open()) {
                base = NativeImage.read(is);
            }

            // Create tinted copy
            NativeImage tinted = new NativeImage(base.getWidth(), base.getHeight(), false);

            for (int y = 0; y < base.getHeight(); y++) {
                for (int x = 0; x < base.getWidth(); x++) {
                    int pixel = base.getPixelRGBA(x, y);
                    // NativeImage uses ABGR format
                    int a = (pixel >> 24) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int r = pixel & 0xFF;

                    // Blend: result = original * (1 - strength) + tint * strength
                    int newR = Math.round(r * (1 - strength) + tintR * strength);
                    int newG = Math.round(g * (1 - strength) + tintG * strength);
                    int newB = Math.round(b * (1 - strength) + tintB * strength);

                    newR = Math.clamp(newR, 0, 255);
                    newG = Math.clamp(newG, 0, 255);
                    newB = Math.clamp(newB, 0, 255);

                    tinted.setPixelRGBA(x, y, (a << 24) | (newB << 16) | (newG << 8) | newR);
                }
            }

            base.close();
            return tinted;

        } catch (IOException e) {
            ShapeCraftClient.LOGGER.warn("[Texture] Failed to load/tint texture {}: {}", basePath, e.getMessage());
            return null;
        }
    }

    private static ResourceLocation generateId(String basePath, String colorHex, float strength) {
        String input = basePath + "|" + colorHex + "|" + strength;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return ResourceLocation.fromNamespaceAndPath("shapecraft", "block/generated/" + sb);
        } catch (NoSuchAlgorithmException e) {
            // Fallback: use hash code
            return ResourceLocation.fromNamespaceAndPath("shapecraft",
                    "block/generated/" + Integer.toHexString(input.hashCode()));
        }
    }

    private static int parseColor(String hex) {
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        return Integer.parseInt(clean, 16);
    }
}
