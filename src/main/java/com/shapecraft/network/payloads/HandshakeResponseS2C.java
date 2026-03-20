package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record HandshakeResponseS2C(
        boolean success,
        int protocolVersion,
        String message,
        int poolSize
) implements CustomPacketPayload {

    public static final Type<HandshakeResponseS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "handshake_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakeResponseS2C> CODEC =
            StreamCodec.of(HandshakeResponseS2C::write, HandshakeResponseS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, HandshakeResponseS2C payload) {
        buf.writeBoolean(payload.success);
        buf.writeInt(payload.protocolVersion);
        buf.writeUtf(payload.message != null ? payload.message : "");
        buf.writeInt(payload.poolSize);
    }

    private static HandshakeResponseS2C read(RegistryFriendlyByteBuf buf) {
        return new HandshakeResponseS2C(
                buf.readBoolean(),
                buf.readInt(),
                buf.readUtf(),
                buf.readInt()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
