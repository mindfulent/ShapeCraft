package com.shapecraft.block;

import com.mojang.serialization.MapCodec;
import com.shapecraft.ShapeCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class PoolBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<PoolBlock> CODEC = simpleCodec(PoolBlock::new);
    public static final EnumProperty<BlockHalf> HALF = EnumProperty.create("half", BlockHalf.class);
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    // Pre-computed door collision shapes for closed state.
    // Our FACING = opposite of vanilla's, so closed rotation = standard + 90°.
    // WEST→Y0: X=0..3, NORTH→Y90: Z=0..3, EAST→Y180: X=13..16, SOUTH→Y270: Z=13..16
    private static final VoxelShape DOOR_CLOSED_WEST  = Block.box(0, 0, 0, 3, 16, 16);
    private static final VoxelShape DOOR_CLOSED_NORTH = Block.box(0, 0, 0, 16, 16, 3);
    private static final VoxelShape DOOR_CLOSED_EAST  = Block.box(13, 0, 0, 16, 16, 16);
    private static final VoxelShape DOOR_CLOSED_SOUTH = Block.box(0, 0, 13, 16, 16, 16);

    // Pre-computed door collision shapes for open state (X↔Z swap of closed + same rotation).
    // WEST→Z=0..3, NORTH→X=13..16, EAST→Z=13..16, SOUTH→X=0..3
    private static final VoxelShape DOOR_OPEN_WEST  = Block.box(0, 0, 0, 16, 16, 3);
    private static final VoxelShape DOOR_OPEN_NORTH = Block.box(13, 0, 0, 16, 16, 16);
    private static final VoxelShape DOOR_OPEN_EAST  = Block.box(0, 0, 13, 16, 16, 16);
    private static final VoxelShape DOOR_OPEN_SOUTH = Block.box(0, 0, 0, 3, 16, 16);

    private final int slotIndex;

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    public PoolBlock(Properties properties) {
        this(properties, -1);
    }

    public PoolBlock(Properties properties, int slotIndex) {
        super(properties);
        this.slotIndex = slotIndex;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, BlockHalf.LOWER)
                .setValue(OPEN, false));
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, OPEN);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();

        // Check if this slot is tall
        if (slotIndex >= 0) {
            BlockPoolManager pool = ShapeCraft.getInstance().getBlockPoolManager();
            BlockPoolManager.BlockSlotData data = pool.getSlot(slotIndex);
            if (data != null && data.isTall()) {
                BlockPos above = pos.above();
                // Must have room above and be in world bounds
                if (!level.isInWorldBounds(above) || !level.getBlockState(above).canBeReplaced(context)) {
                    return null; // Prevents placement
                }
            }
        }

        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HALF, BlockHalf.LOWER)
                .setValue(OPEN, false);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.getShape(state, level, pos, context);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PoolBlockEntity poolBe) {
            if (poolBe.isDoor()) {
                Direction facing = state.getValue(FACING);
                return state.getValue(OPEN) ? getDoorOpenShape(facing) : getDoorClosedShape(facing);
            }
            VoxelShape cached = poolBe.getCachedShape();
            if (cached != null) {
                return cached;
            }
        }
        return Shapes.block();
    }

    private static VoxelShape getDoorClosedShape(Direction facing) {
        Direction lookup = DoorDebugState.rotateFacing(facing, DoorDebugState.hitboxClosedOffset);
        return switch (lookup) {
            case WEST -> DOOR_CLOSED_WEST;
            case NORTH -> DOOR_CLOSED_NORTH;
            case EAST -> DOOR_CLOSED_EAST;
            case SOUTH -> DOOR_CLOSED_SOUTH;
            default -> DOOR_CLOSED_WEST;
        };
    }

    private static VoxelShape getDoorOpenShape(Direction facing) {
        Direction lookup = DoorDebugState.rotateFacing(facing, DoorDebugState.hitboxOpenOffset);
        return switch (lookup) {
            case WEST -> DOOR_OPEN_WEST;
            case NORTH -> DOOR_OPEN_NORTH;
            case EAST -> DOOR_OPEN_EAST;
            case SOUTH -> DOOR_OPEN_SOUTH;
            default -> DOOR_OPEN_WEST;
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        // Check block type — use BlockPoolManager (always available server-side)
        BlockPoolManager pool = ShapeCraft.getInstance().getBlockPoolManager();
        BlockPoolManager.BlockSlotData data = slotIndex >= 0 ? pool.getSlot(slotIndex) : null;
        if (data == null || !data.isDoor()) return InteractionResult.PASS;

        // Toggle OPEN
        boolean nowOpen = !state.getValue(OPEN);
        level.setBlock(pos, state.setValue(OPEN, nowOpen), Block.UPDATE_ALL);

        // Toggle partner half
        BlockHalf half = state.getValue(HALF);
        BlockPos partnerPos = half == BlockHalf.LOWER ? pos.above() : pos.below();
        BlockState partnerState = level.getBlockState(partnerPos);
        if (partnerState.getBlock() instanceof PoolBlock pb && pb.getSlotIndex() == this.slotIndex) {
            level.setBlock(partnerPos, partnerState.setValue(OPEN, nowOpen), Block.UPDATE_ALL);
        }

        // Sound
        level.playSound(null, pos,
                nowOpen ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0f, level.getRandom().nextFloat() * 0.1f + 0.9f);

        return InteractionResult.SUCCESS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide() && slotIndex >= 0) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PoolBlockEntity poolBe) {
                BlockPoolManager pool = ShapeCraft.getInstance().getBlockPoolManager();
                BlockPoolManager.BlockSlotData data = pool.getSlot(slotIndex);
                if (data != null) {
                    poolBe.setSlotIndex(slotIndex);
                    poolBe.setDisplayName(data.displayName());
                    poolBe.setModelJson(data.modelJson());
                    poolBe.setBlockType(data.blockType());
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);

                    // Place upper half for tall blocks
                    if (data.isTall()) {
                        BlockPos above = pos.above();
                        BlockState upperState = state
                                .setValue(HALF, BlockHalf.UPPER)
                                .setValue(FACING, state.getValue(FACING));
                        level.setBlock(above, upperState, Block.UPDATE_ALL);

                        BlockEntity upperBe = level.getBlockEntity(above);
                        if (upperBe instanceof PoolBlockEntity upperPoolBe) {
                            upperPoolBe.setSlotIndex(slotIndex);
                            upperPoolBe.setDisplayName(data.displayName());
                            upperPoolBe.setModelJson(data.upperModelJson());
                            upperPoolBe.setBlockType(data.blockType());
                            level.sendBlockUpdated(above, upperState, upperState, Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            removePartner(level, pos, state);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Only act if block type actually changed (not just state change)
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide()) {
                removePartner(level, pos, state);
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        } else {
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }

    /**
     * Remove the partner half of a tall block (if present).
     * LOWER looks up, UPPER looks down. Verifies same block type and slot index.
     */
    private void removePartner(Level level, BlockPos pos, BlockState state) {
        BlockHalf half = state.getValue(HALF);
        BlockPos partnerPos = half == BlockHalf.LOWER ? pos.above() : pos.below();
        BlockState partnerState = level.getBlockState(partnerPos);

        if (partnerState.getBlock() instanceof PoolBlock partnerBlock
                && partnerBlock.slotIndex == this.slotIndex) {
            // Verify it's the opposite half
            BlockHalf partnerHalf = partnerState.getValue(HALF);
            if ((half == BlockHalf.LOWER && partnerHalf == BlockHalf.UPPER)
                    || (half == BlockHalf.UPPER && partnerHalf == BlockHalf.LOWER)) {
                // Remove partner — use destroyBlock(false) to avoid recursive drops
                level.destroyBlock(partnerPos, false);
            }
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PoolBlockEntity(pos, state);
    }
}
