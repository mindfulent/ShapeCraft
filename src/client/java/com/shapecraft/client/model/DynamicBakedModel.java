package com.shapecraft.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Baked model that stores pre-built quads per direction.
 */
public class DynamicBakedModel implements BakedModel {

    private final Map<Direction, List<BakedQuad>> faceQuads;
    private final List<BakedQuad> unculledQuads;
    private final boolean ambientOcclusion;
    private final TextureAtlasSprite particleSprite;

    public DynamicBakedModel(Map<Direction, List<BakedQuad>> faceQuads,
                             List<BakedQuad> unculledQuads,
                             boolean ambientOcclusion,
                             TextureAtlasSprite particleSprite) {
        this.faceQuads = faceQuads;
        this.unculledQuads = unculledQuads;
        this.ambientOcclusion = ambientOcclusion;
        this.particleSprite = particleSprite;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        if (direction == null) {
            return unculledQuads;
        }
        return faceQuads.getOrDefault(direction, Collections.emptyList());
    }

    @Override
    public boolean useAmbientOcclusion() {
        return ambientOcclusion;
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
        return particleSprite;
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
