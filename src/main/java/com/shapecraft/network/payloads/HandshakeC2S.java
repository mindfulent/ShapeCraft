package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record HandshakeC2S(
        String modVersion,
        int protocolVersion
) implements CustomPacketPayload {

    public static final Type<HandshakeC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "handshake"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakeC2S> CODEC =
            StreamCodec.of(HandshakeC2S::write, HandshakeC2S::read);

    private static void write(RegistryFriendlyByteBuf buf, HandshakeC2S payload) {
        buf.writeUtf(payload.modVersion);
        buf.writeInt(payload.protocolVersion);
    }

    private static HandshakeC2S read(RegistryFriendlyByteBuf buf) {
        return new HandshakeC2S(buf.readUtf(), buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
