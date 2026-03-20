package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GenerationErrorS2C(
        String message
) implements CustomPacketPayload {

    public static final Type<GenerationErrorS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "generation_error"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GenerationErrorS2C> CODEC =
            StreamCodec.of(GenerationErrorS2C::write, GenerationErrorS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, GenerationErrorS2C payload) {
        buf.writeUtf(payload.message);
    }

    private static GenerationErrorS2C read(RegistryFriendlyByteBuf buf) {
        return new GenerationErrorS2C(buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
