package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DoorDebugSyncS2C(
        int textureClosedOffset,
        int textureOpenOffset,
        int hitboxClosedOffset,
        int hitboxOpenOffset
) implements CustomPacketPayload {

    public static final Type<DoorDebugSyncS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "door_debug_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DoorDebugSyncS2C> CODEC =
            StreamCodec.of(DoorDebugSyncS2C::write, DoorDebugSyncS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, DoorDebugSyncS2C payload) {
        buf.writeInt(payload.textureClosedOffset);
        buf.writeInt(payload.textureOpenOffset);
        buf.writeInt(payload.hitboxClosedOffset);
        buf.writeInt(payload.hitboxOpenOffset);
    }

    private static DoorDebugSyncS2C read(RegistryFriendlyByteBuf buf) {
        return new DoorDebugSyncS2C(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
