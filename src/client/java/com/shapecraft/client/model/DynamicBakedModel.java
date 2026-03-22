package com.shapecraft.client.model;

import com.shapecraft.block.BlockHalf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Baked model that delegates to BakedModelCache for its quads.
 * Pure cache lookup — no baking, no atlas access, no Minecraft.getInstance() in getQuads().
 * Safe to call from any thread (e.g. Sodium chunk worker threads).
 */
public class DynamicBakedModel implements BakedModel {

    private final int slotIndex;
    private final Direction facing;
    private final BlockHalf half;
    private final boolean open;

    public DynamicBakedModel(int slotIndex, Direction facing, BlockHalf half, boolean open) {
        this.slotIndex = slotIndex;
        this.facing = facing;
        this.half = half;
        this.open = open;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        BakedModelCache.FacingQuads fq = BakedModelCache.getQuads(slotIndex, half, open, facing);
        if (fq == null) {
            return Collections.emptyList();
        }
        if (direction == null) {
            return fq.unculledQuads();
        }
        return fq.faceQuads().getOrDefault(direction, Collections.emptyList());
    }

    @Override
    public boolean useAmbientOcclusion() {
        BakedModelCache.FacingQuads fq = BakedModelCache.getQuads(slotIndex, half, open, facing);
        return fq != null && fq.ambientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        BakedModelCache.FacingQuads fq = BakedModelCache.getQuads(slotIndex, half, open, facing);
        if (fq != null && fq.particleSprite() != null) {
            return fq.particleSprite();
        }
        // Fallback: get missing texture from block atlas
        TextureAtlas atlas = Minecraft.getInstance().getModelManager()
                .getAtlas(InventoryMenu.BLOCK_ATLAS);
        return atlas.getSprite(ResourceLocation.withDefaultNamespace("missingno"));
    }

    @Override
    public ItemTransforms getTransforms() {
        return ItemTransforms.NO_TRANSFORMS;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
