package com.shapecraft.block;

import com.mojang.serialization.MapCodec;
import com.shapecraft.ShapeCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class PoolBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<PoolBlock> CODEC = simpleCodec(PoolBlock::new);

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
                .setValue(FACING, Direction.NORTH));
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PoolBlockEntity poolBe) {
            VoxelShape cached = poolBe.getCachedShape();
            if (cached != null) {
                return cached;
            }
        }
        return Shapes.block();
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
                }
            }
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PoolBlockEntity(pos, state);
    }
}
