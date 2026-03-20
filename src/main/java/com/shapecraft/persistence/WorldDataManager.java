package com.shapecraft.persistence;

import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.block.BlockPoolManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persists BlockPoolManager state to world save data.
 */
public class WorldDataManager extends SavedData {

    private static final String DATA_KEY = ShapeCraftConstants.MOD_ID + "_pool";
    private final BlockPoolManager poolManager;

    public WorldDataManager(BlockPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public static WorldDataManager loadOrCreate(MinecraftServer server, BlockPoolManager poolManager) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(
                        () -> new WorldDataManager(poolManager),
                        (tag, registries) -> load(tag, poolManager),
                        null
                ),
                DATA_KEY
        );
    }

    private static WorldDataManager load(CompoundTag tag, BlockPoolManager poolManager) {
        WorldDataManager manager = new WorldDataManager(poolManager);

        if (tag.contains("Slots", Tag.TAG_LIST)) {
            ListTag slots = tag.getList("Slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < slots.size(); i++) {
                CompoundTag slotTag = slots.getCompound(i);
                int slotIndex = slotTag.getInt("SlotIndex");
                String displayName = slotTag.getString("DisplayName");
                String modelJson = slotTag.getString("ModelJson");

                poolManager.setSlot(slotIndex, new BlockPoolManager.BlockSlotData(
                        slotIndex, displayName, modelJson));
            }
            ShapeCraft.LOGGER.info("[Persistence] Loaded {} block slots from world data",
                    poolManager.getAssignedCount());
        }

        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag slots = new ListTag();
        for (var entry : poolManager.getAllSlots().entrySet()) {
            var data = entry.getValue();
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt("SlotIndex", data.slotIndex());
            slotTag.putString("DisplayName", data.displayName());
            slotTag.putString("ModelJson", data.modelJson());
            slots.add(slotTag);
        }
        tag.put("Slots", slots);
        return tag;
    }

    public void markDirty() {
        setDirty();
    }
}
