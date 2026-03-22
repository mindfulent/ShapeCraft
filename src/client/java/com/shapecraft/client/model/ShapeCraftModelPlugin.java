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
     * Returns the BlockModelRotation for a normalized door model.
     *
     * The rotation depends on the model's thin axis (detected by normalizeDoorPanel)
     * and whether the door is open (after X↔Z swap, thin axis flips).
     *
     * For a normalized panel flush with the 16-side of its thin axis:
     *
     * Closed thin-Z (panel at Z≈16):
     *   NORTH→Y180, SOUTH→Y0, EAST→Y90, WEST→Y270
     *
     * Closed thin-X (panel at X≈16):
     *   NORTH→Y90, SOUTH→Y270, EAST→Y0, WEST→Y180
     *
     * Open thin-Z→X (panel was thin-Z, X↔Z swap made it thin-X at X≈16):
     *   NORTH→Y0, SOUTH→Y180, EAST→Y270, WEST→Y90
     *
     * Open thin-X→Z (panel was thin-X, X↔Z swap made it thin-Z at Z≈16):
     *   NORTH→Y90, SOUTH→Y270, EAST→Y0, WEST→Y180
     */
    /**
     * Minecraft Y-rotation convention (clockwise from above):
     *   Y90:  (x,z) → (16-z, x)
     *   Y180: (x,z) → (16-x, 16-z)
     *   Y270: (x,z) → (z, 16-x)
     */
    public static BlockModelRotation getDoorRotation(Direction facing, boolean open, boolean originalThinZ) {
        if (!open) {
            if (originalThinZ) {
                // Panel normalized to Z≈16. Map to correct block edge:
                return switch (facing) {
                    case NORTH -> BlockModelRotation.X0_Y180;  // Z≈16 → Z≈0
                    case SOUTH -> BlockModelRotation.X0_Y0;    // Z≈16 stays
                    case EAST  -> BlockModelRotation.X0_Y270;  // Z≈16 → X≈16
                    case WEST  -> BlockModelRotation.X0_Y90;   // Z≈16 → X≈0
                    default -> BlockModelRotation.X0_Y0;
                };
            } else {
                // Panel normalized to X≈16. Map to correct block edge:
                return switch (facing) {
                    case NORTH -> BlockModelRotation.X0_Y270;  // X≈16 → Z≈0
                    case SOUTH -> BlockModelRotation.X0_Y90;   // X≈16 → Z≈16
                    case EAST  -> BlockModelRotation.X0_Y0;    // X≈16 stays
                    case WEST  -> BlockModelRotation.X0_Y180;  // X≈16 → X≈0
                    default -> BlockModelRotation.X0_Y0;
                };
            }
        } else {
            // After X↔Z swap, thin axis is flipped
            if (originalThinZ) {
                // Was thin-Z, now thin-X at X≈16 after swap:
                return switch (facing) {
                    case NORTH -> BlockModelRotation.X0_Y0;    // X≈16 stays (east=open-north)
                    case SOUTH -> BlockModelRotation.X0_Y180;  // X≈16 → X≈0
                    case EAST  -> BlockModelRotation.X0_Y90;   // X≈16 → Z≈16
                    case WEST  -> BlockModelRotation.X0_Y270;  // X≈16 → Z≈0
                    default -> BlockModelRotation.X0_Y0;
                };
            } else {
                // Was thin-X, now thin-Z at Z≈16 after swap:
                return switch (facing) {
                    case NORTH -> BlockModelRotation.X0_Y180;  // Z≈16 → Z≈0
                    case SOUTH -> BlockModelRotation.X0_Y0;    // Z≈16 stays
                    case EAST  -> BlockModelRotation.X0_Y270;  // Z≈16 → X≈16
                    case WEST  -> BlockModelRotation.X0_Y90;   // Z≈16 → X≈0
                    default -> BlockModelRotation.X0_Y0;
                };
            }
        }
    }
}
