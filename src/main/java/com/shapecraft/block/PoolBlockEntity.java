package com.shapecraft.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shapecraft.ShapeCraft;
import com.shapecraft.validation.ParentResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class PoolBlockEntity extends BlockEntity {

    private int slotIndex = -1;
    private String displayName = "";
    private String modelJson = "";
    private @Nullable VoxelShape cachedShape = null;

    public PoolBlockEntity(BlockPos pos, BlockState state) {
        super(ShapeCraft.POOL_BLOCK_ENTITY_TYPE, pos, state);
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
        setChanged();
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        setChanged();
    }

    public String getModelJson() {
        return modelJson;
    }

    public void setModelJson(String modelJson) {
        this.modelJson = modelJson;
        computeShape();
        setChanged();
    }

    /**
     * Computes a VoxelShape from the model JSON elements.
     * Union of all element bounding boxes.
     */
    public void computeShape() {
        if (modelJson == null || modelJson.isEmpty()) {
            cachedShape = Shapes.block();
            return;
        }

        try {
            JsonObject model = JsonParser.parseString(modelJson).getAsJsonObject();

            // Resolve elements: inline first, then parent fallback
            JsonArray elements;
            if (model.has("elements")) {
                elements = model.getAsJsonArray("elements");
            } else if (model.has("parent")) {
                ParentResolver.ResolvedParent resolved = ParentResolver.resolve(
                        model.get("parent").getAsString());
                if (resolved == null || resolved.elements().isEmpty()) {
                    cachedShape = Shapes.block();
                    return;
                }
                elements = resolved.elements();
            } else {
                cachedShape = Shapes.block();
                return;
            }

            VoxelShape combined = Shapes.empty();
            for (JsonElement elemJson : elements) {
                JsonObject elem = elemJson.getAsJsonObject();
                if (!elem.has("from") || !elem.has("to")) continue;

                JsonArray from = elem.getAsJsonArray("from");
                JsonArray to = elem.getAsJsonArray("to");

                // Convert from 0-16 coordinate space to 0-1
                double x1 = Math.min(from.get(0).getAsDouble(), to.get(0).getAsDouble()) / 16.0;
                double y1 = Math.min(from.get(1).getAsDouble(), to.get(1).getAsDouble()) / 16.0;
                double z1 = Math.min(from.get(2).getAsDouble(), to.get(2).getAsDouble()) / 16.0;
                double x2 = Math.max(from.get(0).getAsDouble(), to.get(0).getAsDouble()) / 16.0;
                double y2 = Math.max(from.get(1).getAsDouble(), to.get(1).getAsDouble()) / 16.0;
                double z2 = Math.max(from.get(2).getAsDouble(), to.get(2).getAsDouble()) / 16.0;

                // Clamp to valid range
                x1 = Math.max(0, Math.min(1, x1));
                y1 = Math.max(0, Math.min(1, y1));
                z1 = Math.max(0, Math.min(1, z1));
                x2 = Math.max(0, Math.min(1, x2));
                y2 = Math.max(0, Math.min(1, y2));
                z2 = Math.max(0, Math.min(1, z2));

                if (x1 < x2 && y1 < y2 && z1 < z2) {
                    combined = Shapes.or(combined, Block.box(
                            x1 * 16, y1 * 16, z1 * 16,
                            x2 * 16, y2 * 16, z2 * 16));
                }
            }

            cachedShape = combined.isEmpty() ? Shapes.block() : combined;
        } catch (Exception e) {
            ShapeCraft.LOGGER.warn("[BlockEntity] Failed to compute shape: {}", e.getMessage());
            cachedShape = Shapes.block();
        }
    }

    @Nullable
    public VoxelShape getCachedShape() {
        return cachedShape;
    }

    public void setCachedShape(@Nullable VoxelShape shape) {
        this.cachedShape = shape;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("SlotIndex", slotIndex);
        tag.putString("DisplayName", displayName);
        tag.putString("ModelJson", modelJson);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        slotIndex = tag.getInt("SlotIndex");
        displayName = tag.getString("DisplayName");
        modelJson = tag.getString("ModelJson");
        computeShape();
    }
}
