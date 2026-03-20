package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GenerateRequestC2S(
        String description
) implements CustomPacketPayload {

    public static final Type<GenerateRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "generate_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GenerateRequestC2S> CODEC =
            StreamCodec.of(GenerateRequestC2S::write, GenerateRequestC2S::read);

    private static void write(RegistryFriendlyByteBuf buf, GenerateRequestC2S payload) {
        buf.writeUtf(payload.description);
    }

    private static GenerateRequestC2S read(RegistryFriendlyByteBuf buf) {
        return new GenerateRequestC2S(buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
