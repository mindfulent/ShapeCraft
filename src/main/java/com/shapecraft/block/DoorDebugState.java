package com.shapecraft.block;

import net.minecraft.core.Direction;

/**
 * Mutable debug tunables for door hitbox mapping.
 * Texture rotation is now computed automatically from model geometry.
 */
public final class DoorDebugState {
    public static int hitboxClosedOffset = 0;
    public static int hitboxOpenOffset = 0;

    /**
     * Rotate a horizontal facing by an offset (0, 90, 180, or 270 degrees clockwise).
     */
    public static Direction rotateFacing(Direction facing, int offset) {
        int steps = ((offset / 90) % 4 + 4) % 4;
        for (int i = 0; i < steps; i++) {
            facing = facing.getClockWise();
        }
        return facing;
    }

    private DoorDebugState() {}
}
