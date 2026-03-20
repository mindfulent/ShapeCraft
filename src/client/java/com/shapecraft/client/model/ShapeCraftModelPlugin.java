package com.shapecraft.client.model;

import com.mojang.math.Transformation;
import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.client.ShapeCraftClient;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registers BlockStateResolvers for all 64 pool blocks.
 * When a slot has model data in the cache, it returns a DynamicBlockModel;
 * otherwise it falls back to the default (missing model).
 */
public class ShapeCraftModelPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        ShapeCraftClient.LOGGER.info("[Model] Registering block state resolvers for {} pool blocks",
                ShapeCraftConstants.DEFAULT_POOL_SIZE);

        for (int i = 0; i < ShapeCraftConstants.DEFAULT_POOL_SIZE; i++) {
            final int slotIndex = i;
            pluginContext.registerBlockStateResolver(ShapeCraft.POOL_BLOCKS[slotIndex], context -> {
                ModelCache.ModelData modelData = ModelCache.get(slotIndex);

                for (BlockState state : context.block().getStateDefinition().getPossibleStates()) {
                    if (modelData != null) {
                        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
                        BlockModelRotation rotation = getBlockRotation(facing);

                        DynamicBlockModel unbaked = new DynamicBlockModel(
                                modelData.modelJson(), slotIndex, rotation);
                        context.setModel(state, unbaked);
                    } else {
                        // No model data — use missing model placeholder
                        // getOrLoadModel with missing ID will return the missing model
                        context.setModel(state, context.getOrLoadModel(
                                net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/stone")));
                    }
                }
            });
        }
    }

    private static float getYRotation(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 270;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
    }

    /**
     * Returns the BlockModelRotation for a given FACING direction.
     */
    public static BlockModelRotation getBlockRotation(Direction facing) {
        return switch (facing) {
            case NORTH -> BlockModelRotation.X0_Y0;
            case EAST -> BlockModelRotation.X0_Y90;
            case SOUTH -> BlockModelRotation.X0_Y180;
            case WEST -> BlockModelRotation.X0_Y270;
            default -> BlockModelRotation.X0_Y0;
        };
    }
}
