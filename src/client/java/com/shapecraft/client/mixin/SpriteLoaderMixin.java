package com.shapecraft.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.shapecraft.client.render.DynamicTextureManager;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;
import java.util.Map;

/**
 * Injects ShapeCraft's dynamically generated (tinted) textures into the block atlas
 * during resource reload. This allows generated blocks to use tinted vanilla textures.
 */
@Mixin(SpriteLoader.class)
public class SpriteLoaderMixin {

    /**
     * After sprites are collected from sprite sources, add our generated textures.
     * Targets the local variable holding the sprite contents map in the load method.
     */
    @ModifyVariable(
            method = "stitch",
            at = @At("HEAD"),
            argsOnly = true
    )
    private List<SpriteContents> shapecraft$injectGeneratedSprites(List<SpriteContents> original) {
        Map<ResourceLocation, NativeImage> generated = DynamicTextureManager.getGeneratedTextures();
        if (generated.isEmpty()) {
            return original;
        }

        // Create a mutable copy
        List<SpriteContents> modified = new java.util.ArrayList<>(original);

        for (var entry : generated.entrySet()) {
            ResourceLocation id = entry.getKey();
            NativeImage image = entry.getValue();

            try {
                // Create SpriteContents from the NativeImage
                SpriteContents contents = new SpriteContents(
                        id,
                        new FrameSize(image.getWidth(), image.getHeight()),
                        image,
                        ResourceMetadata.EMPTY
                );
                modified.add(contents);
            } catch (Exception e) {
                // Don't crash on texture injection failure
            }
        }

        return modified;
    }
}
