package com.shapecraft.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared mutable store of baked quad data, keyed by slot index.
 * DynamicBakedModel delegates to this cache for its quads.
 * Thread-safe: ConcurrentHashMap for atomic slot-level updates.
 *
 * Two writers:
 * - DynamicBlockModel.bake() during resource reload (engine's spriteGetter, guaranteed correct)
 * - RuntimeModelBaker at runtime for hot-swap (atlas sprites from current atlas)
 *
 * getQuads() is a pure lookup — no baking, no atlas access.
 */
public class BakedModelCache {

    private static final ConcurrentHashMap<Integer, BakedSlotData> cache = new ConcurrentHashMap<>();

    public record FacingQuads(
            Map<Direction, List<BakedQuad>> faceQuads,
            List<BakedQuad> unculledQuads,
            boolean ambientOcclusion,
            TextureAtlasSprite particleSprite
    ) {}

    public record BakedSlotData(Map<Direction, FacingQuads> facingVariants) {}

    public static void put(int slotIndex, BakedSlotData data) {
        cache.put(slotIndex, data);
    }

    @Nullable
    public static BakedSlotData get(int slotIndex) {
        return cache.get(slotIndex);
    }

    public static void clear() {
        cache.clear();
    }

    /**
     * Atomically merge a single facing variant into the cache for a slot.
     * Used by DynamicBlockModel.bake() which is called per block state (per facing).
     * Uses compute() for atomic read-modify-write.
     */
    public static void mergeFacing(int slotIndex, Direction facing, FacingQuads fq) {
        cache.compute(slotIndex, (key, existing) -> {
            Map<Direction, FacingQuads> variants;
            if (existing != null) {
                variants = new EnumMap<>(existing.facingVariants());
            } else {
                variants = new EnumMap<>(Direction.class);
            }
            variants.put(facing, fq);
            return new BakedSlotData(variants);
        });
    }

    /**
     * Look up quads for a specific slot and facing direction.
     * Pure lookup — returns null if no data exists (slot not generated).
     */
    @Nullable
    public static FacingQuads getQuads(int slotIndex, Direction facing) {
        BakedSlotData slotData = cache.get(slotIndex);
        if (slotData == null) return null;
        return slotData.facingVariants().get(facing);
    }
}
