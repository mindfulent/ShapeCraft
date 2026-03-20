package com.shapecraft.block;

import com.shapecraft.ShapeCraftConstants;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockPoolManager {

    private final Map<Integer, BlockSlotData> slots = new ConcurrentHashMap<>();

    public boolean isSlotAssigned(int slotIndex) {
        return slots.containsKey(slotIndex);
    }

    @Nullable
    public BlockSlotData getSlot(int slotIndex) {
        return slots.get(slotIndex);
    }

    public synchronized int assignSlot(String displayName, String modelJson) {
        int slot = getNextAvailable();
        if (slot < 0) return -1;

        slots.put(slot, new BlockSlotData(slot, displayName, modelJson));
        return slot;
    }

    public void setSlot(int slotIndex, BlockSlotData data) {
        slots.put(slotIndex, data);
    }

    public int getNextAvailable() {
        for (int i = 0; i < ShapeCraftConstants.DEFAULT_POOL_SIZE; i++) {
            if (!slots.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    public int getAssignedCount() {
        return slots.size();
    }

    public Map<Integer, BlockSlotData> getAllSlots() {
        return Map.copyOf(slots);
    }

    public record BlockSlotData(int slotIndex, String displayName, String modelJson) {}
}
