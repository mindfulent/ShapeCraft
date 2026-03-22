package com.shapecraft.block;

import net.minecraft.util.StringRepresentable;

public enum BlockHalf implements StringRepresentable {
    LOWER("lower"),
    UPPER("upper");

    private final String name;

    BlockHalf(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
