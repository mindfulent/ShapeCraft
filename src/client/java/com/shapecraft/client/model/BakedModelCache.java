package com.shapecraft.client.model;

import com.shapecraft.block.BlockHalf;
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
 * Shared mutable store of baked quad data, keyed by (slotIndex, half).
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

    public record CacheKey(int slotIndex, BlockHalf half, boolean open) {}

    private static final ConcurrentHashMap<CacheKey, BakedSlotData> cache = new ConcurrentHashMap<>();

    public record FacingQuads(
            Map<Direction, List<BakedQuad>> faceQuads,
            List<BakedQuad> unculledQuads,
            boolean ambientOcclusion,
            TextureAtlasSprite particleSprite
    ) {}

    public record BakedSlotData(Map<Direction, FacingQuads> facingVariants) {}

    public static void put(int slotIndex, BlockHalf half, BakedSlotData data) {
        put(slotIndex, half, false, data);
    }

    public static void put(int slotIndex, BlockHalf half, boolean open, BakedSlotData data) {
        cache.put(new CacheKey(slotIndex, half, open), data);
    }

    @Nullable
    public static BakedSlotData get(int slotIndex, BlockHalf half) {
        return cache.get(new CacheKey(slotIndex, half, false));
    }

    public static void clear() {
        cache.clear();
    }

    /**
     * Atomically merge a single facing variant into the cache for a slot+half.
     * Used by DynamicBlockModel.bake() which is called per block state (per facing × half).
     * Uses compute() for atomic read-modify-write.
     */
    public static void mergeFacing(int slotIndex, BlockHalf half, boolean open, Direction facing, FacingQuads fq) {
        CacheKey key = new CacheKey(slotIndex, half, open);
        cache.compute(key, (k, existing) -> {
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
     * Look up quads for a specific slot, half, and facing direction.
     * Pure lookup — returns null if no data exists (slot not generated).
     */
    @Nullable
    public static FacingQuads getQuads(int slotIndex, BlockHalf half, boolean open, Direction facing) {
        BakedSlotData slotData = cache.get(new CacheKey(slotIndex, half, open));
        if (slotData == null && open) {
            // Fallback to closed model if no open variant exists
            slotData = cache.get(new CacheKey(slotIndex, half, false));
        }
        if (slotData == null) return null;
        return slotData.facingVariants().get(facing);
    }
}
