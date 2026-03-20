package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GenerationStatusS2C(
        int status,
        String message
) implements CustomPacketPayload {

    // Status constants
    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_GENERATING = 1;
    public static final int STATUS_VALIDATING = 2;

    public static final Type<GenerationStatusS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "generation_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GenerationStatusS2C> CODEC =
            StreamCodec.of(GenerationStatusS2C::write, GenerationStatusS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, GenerationStatusS2C payload) {
        buf.writeInt(payload.status);
        buf.writeUtf(payload.message);
    }

    private static GenerationStatusS2C read(RegistryFriendlyByteBuf buf) {
        return new GenerationStatusS2C(buf.readInt(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
