package com.shapecraft.client.model;

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
     * Bake a single slot's model JSON into the cache and refresh chunks.
     */
    public static void bakeAndCache(int slotIndex, String modelJson) {
        Function<Material, TextureAtlasSprite> spriteGetter = getSpriteGetter();

        Map<Direction, BakedModelCache.FacingQuads> variants = new EnumMap<>(Direction.class);
        for (Direction facing : HORIZONTAL_FACINGS) {
            BlockModelRotation rotation = ShapeCraftModelPlugin.getBlockRotation(facing);
            BakedModelCache.FacingQuads fq = DynamicBlockModel.bakeQuads(modelJson, slotIndex, rotation, spriteGetter);
            if (fq != null) {
                variants.put(facing, fq);
            }
        }

        BakedModelCache.put(slotIndex, new BakedModelCache.BakedSlotData(variants));

        // Re-mesh all chunks so placed blocks pick up the new model
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }

        ShapeCraftClient.LOGGER.info("[Model] Runtime-baked slot {} ({} variants)",
                slotIndex, variants.size());
    }

    /**
     * Bake multiple slots at once, then refresh chunks a single time.
     */
    public static void bakeAndCacheBatch(Map<Integer, String> slotJsons) {
        if (slotJsons.isEmpty()) return;

        Function<Material, TextureAtlasSprite> spriteGetter = getSpriteGetter();

        for (var entry : slotJsons.entrySet()) {
            int slotIndex = entry.getKey();
            String modelJson = entry.getValue();

            Map<Direction, BakedModelCache.FacingQuads> variants = new EnumMap<>(Direction.class);
            for (Direction facing : HORIZONTAL_FACINGS) {
                BlockModelRotation rotation = ShapeCraftModelPlugin.getBlockRotation(facing);
                BakedModelCache.FacingQuads fq = DynamicBlockModel.bakeQuads(modelJson, slotIndex, rotation, spriteGetter);
                if (fq != null) {
                    variants.put(facing, fq);
                }
            }

            BakedModelCache.put(slotIndex, new BakedModelCache.BakedSlotData(variants));
        }

        // Single chunk re-mesh for all baked slots
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }

        ShapeCraftClient.LOGGER.info("[Model] Runtime-baked {} slots in batch",
                slotJsons.size());
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
