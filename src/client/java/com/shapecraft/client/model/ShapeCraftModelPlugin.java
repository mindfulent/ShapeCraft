package com.shapecraft.client.model;

import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.block.BlockHalf;
import com.shapecraft.block.PoolBlock;
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
        // Clear stale quads (baked against previous atlas layout).
        // bake() will re-populate the cache using the engine's spriteGetter.
        BakedModelCache.clear();
        ShapeCraftClient.LOGGER.info("[Model] Registering block state resolvers for {} pool blocks (cache cleared)",
                ShapeCraftConstants.DEFAULT_POOL_SIZE);

        for (int i = 0; i < ShapeCraftConstants.DEFAULT_POOL_SIZE; i++) {
            final int slotIndex = i;
            pluginContext.registerBlockStateResolver(ShapeCraft.POOL_BLOCKS[slotIndex], context -> {
                for (BlockState state : context.block().getStateDefinition().getPossibleStates()) {
                    Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
                    BlockHalf half = state.getValue(PoolBlock.HALF);
                    boolean open = state.getValue(PoolBlock.OPEN);
                    // DynamicBlockModel.bake() writes to BakedModelCache using engine's spriteGetter.
                    context.setModel(state, new DynamicBlockModel(slotIndex, facing, half, open));
                }
            });
        }
    }

    /**
     * Returns the BlockModelRotation for a given FACING direction.
     * Standard mapping: NORTH=Y0 base.
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

    /**
     * Returns the BlockModelRotation for doors, compensating for PoolBlock's .getOpposite()
     * FACING convention. PoolBlock stores FACING = opposite of vanilla DoorBlock's convention,
     * so we apply vanilla's rotation for facing.getOpposite().
     *
     * Closed = standard block rotation + 90° (compensates for the opposite FACING).
     * Open = standard block rotation + 180° (the X↔Z swap handles the hinge rotation,
     * so only the 180° offset is needed, not the full 270° that +90° closed + 90° open would give).
     *
     * Closed mapping (our FACING → rotation):
     *   WEST→Y0, NORTH→Y90, EAST→Y180, SOUTH→Y270
     * Open mapping (our FACING → rotation):
     *   WEST→Y90, NORTH→Y180, EAST→Y270, SOUTH→Y0
     */
    public static BlockModelRotation getDoorRotation(Direction facing, boolean open) {
        int baseY = switch (facing) {
            case NORTH -> 0;
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        int offset = open ? 180 : 90;
        return BlockModelRotation.by(0, (baseY + offset) % 360);
    }
}
