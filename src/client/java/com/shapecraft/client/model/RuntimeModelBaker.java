package com.shapecraft.client.model;

import com.shapecraft.block.BlockHalf;
import com.shapecraft.client.ShapeCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Hot-swap utility that bakes model JSON into BakedModelCache at runtime,
 * without triggering a full resource reload.
 * All methods must be called on the render thread.
 */
public class RuntimeModelBaker {

    private static final Direction[] HORIZONTAL_FACINGS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    /**
     * Bake a single slot's model JSON (and optional upper/open models) into the cache and refresh chunks.
     */
    public static void bakeAndCache(int slotIndex, String modelJson, String upperModelJson) {
        ModelCache.ModelData data = ModelCache.get(slotIndex);
        Function<Material, TextureAtlasSprite> spriteGetter = getSpriteGetter();
        boolean isDoor = data != null && data.isDoor();

        // Bake closed LOWER variant
        bakeHalf(slotIndex, BlockHalf.LOWER, modelJson, spriteGetter, false, isDoor);

        // Bake closed UPPER variant if present
        if (upperModelJson != null && !upperModelJson.isEmpty()) {
            bakeHalf(slotIndex, BlockHalf.UPPER, upperModelJson, spriteGetter, false, isDoor);
        }

        // Bake open variants if present
        if (data != null) {
            bakeOpenVariants(slotIndex, data, spriteGetter);
        }

        // Re-mesh all chunks so placed blocks pick up the new model
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }

        ShapeCraftClient.LOGGER.info("[Model] Runtime-baked slot {} (tall={}, door={})",
                slotIndex,
                upperModelJson != null && !upperModelJson.isEmpty(),
                data != null && data.isDoor());
    }

    /**
     * Backward-compatible overload for single-block models.
     */
    public static void bakeAndCache(int slotIndex, String modelJson) {
        bakeAndCache(slotIndex, modelJson, "");
    }

    /**
     * Bake multiple slots at once, then refresh chunks a single time.
     */
    public static void bakeAndCacheBatch(Map<Integer, ModelCache.ModelData> slotDataMap) {
        if (slotDataMap.isEmpty()) return;

        Function<Material, TextureAtlasSprite> spriteGetter = getSpriteGetter();

        for (var entry : slotDataMap.entrySet()) {
            int slotIndex = entry.getKey();
            ModelCache.ModelData data = entry.getValue();

            boolean isDoor = data.isDoor();
            bakeHalf(slotIndex, BlockHalf.LOWER, data.modelJson(), spriteGetter, false, isDoor);

            if (data.upperModelJson() != null && !data.upperModelJson().isEmpty()) {
                bakeHalf(slotIndex, BlockHalf.UPPER, data.upperModelJson(), spriteGetter, false, isDoor);
            }

            // Bake open variants
            bakeOpenVariants(slotIndex, data, spriteGetter);
        }

        // Single chunk re-mesh for all baked slots
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }

        ShapeCraftClient.LOGGER.info("[Model] Runtime-baked {} slots in batch",
                slotDataMap.size());
    }

    private static void bakeOpenVariants(int slotIndex, ModelCache.ModelData data,
                                          Function<Material, TextureAtlasSprite> spriteGetter) {
        boolean isDoor = data.isDoor();

        if (isDoor) {
            // Doors: normalize to edge (get thin axis), apply X↔Z swap, bake with axis-aware rotation.
            DynamicBlockModel.NormalizedDoor lowerNorm = DynamicBlockModel.normalizeDoorPanel(data.modelJson());
            String openLower = DynamicBlockModel.transformDoorOpen(lowerNorm.json());
            bakeHalfDoorOpen(slotIndex, BlockHalf.LOWER, openLower, spriteGetter, lowerNorm.thinAlongZ());

            if (data.upperModelJson() != null && !data.upperModelJson().isEmpty()) {
                DynamicBlockModel.NormalizedDoor upperNorm = DynamicBlockModel.normalizeDoorPanel(data.upperModelJson());
                String openUpper = DynamicBlockModel.transformDoorOpen(upperNorm.json());
                bakeHalfDoorOpen(slotIndex, BlockHalf.UPPER, openUpper, spriteGetter, upperNorm.thinAlongZ());
            }
        } else {
            // Non-doors: bake separate open variant JSON if present
            String openJson = data.modelJsonOpen();
            if (openJson != null && !openJson.isEmpty()) {
                bakeHalf(slotIndex, BlockHalf.LOWER, openJson, spriteGetter, true, false);
            }

            String upperOpenJson = data.upperModelJsonOpen();
            if (upperOpenJson != null && !upperOpenJson.isEmpty()) {
                bakeHalf(slotIndex, BlockHalf.UPPER, upperOpenJson, spriteGetter, true, false);
            } else if (openJson != null && !openJson.isEmpty()
                    && data.upperModelJson() != null && !data.upperModelJson().isEmpty()) {
                // Tall block with open lower but no open upper: fall back to closed upper
                bakeHalf(slotIndex, BlockHalf.UPPER, data.upperModelJson(), spriteGetter, true, false);
            }
        }
    }

    private static void bakeHalf(int slotIndex, BlockHalf half, String modelJson,
                                  Function<Material, TextureAtlasSprite> spriteGetter, boolean open) {
        bakeHalf(slotIndex, half, modelJson, spriteGetter, open, false);
    }

    private static void bakeHalf(int slotIndex, BlockHalf half, String modelJson,
                                  Function<Material, TextureAtlasSprite> spriteGetter,
                                  boolean open, boolean isDoor) {
        boolean thinAlongZ = true; // default
        if (isDoor) {
            DynamicBlockModel.NormalizedDoor norm = DynamicBlockModel.normalizeDoorPanel(modelJson);
            modelJson = norm.json();
            thinAlongZ = norm.thinAlongZ();
        }
        final boolean finalThinZ = thinAlongZ;
        Map<Direction, BakedModelCache.FacingQuads> variants = new EnumMap<>(Direction.class);
        for (Direction facing : HORIZONTAL_FACINGS) {
            BlockModelRotation rotation = isDoor
                    ? ShapeCraftModelPlugin.getDoorRotation(facing, false, finalThinZ)
                    : ShapeCraftModelPlugin.getBlockRotation(facing);
            BakedModelCache.FacingQuads fq = DynamicBlockModel.bakeQuads(modelJson, slotIndex, rotation, spriteGetter);
            if (fq != null) {
                variants.put(facing, fq);
            }
        }
        BakedModelCache.put(slotIndex, half, open, new BakedModelCache.BakedSlotData(variants));
    }

    /**
     * Bake door open state: JSON has already been normalized + X↔Z swapped.
     * Uses axis-aware rotation based on the original thin axis.
     */
    private static void bakeHalfDoorOpen(int slotIndex, BlockHalf half, String transformedJson,
                                          Function<Material, TextureAtlasSprite> spriteGetter,
                                          boolean originalThinZ) {
        Map<Direction, BakedModelCache.FacingQuads> variants = new EnumMap<>(Direction.class);
        for (Direction facing : HORIZONTAL_FACINGS) {
            BlockModelRotation rotation = ShapeCraftModelPlugin.getDoorRotation(facing, true, originalThinZ);
            BakedModelCache.FacingQuads fq = DynamicBlockModel.bakeQuads(transformedJson, slotIndex, rotation, spriteGetter);
            if (fq != null) {
                variants.put(facing, fq);
            }
        }
        BakedModelCache.put(slotIndex, half, true, new BakedModelCache.BakedSlotData(variants));
    }

    /**
     * Get a sprite getter that resolves from the current block atlas.
     */
    private static Function<Material, TextureAtlasSprite> getSpriteGetter() {
        Minecraft mc = Minecraft.getInstance();
        var atlas = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        return material -> atlas.getSprite(material.texture());
    }
}
