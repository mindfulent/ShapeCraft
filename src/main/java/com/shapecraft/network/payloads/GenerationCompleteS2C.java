package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GenerationCompleteS2C(
        int slotIndex,
        String displayName,
        String modelJson,
        String upperModelJson,
        String modelJsonOpen,
        String upperModelJsonOpen,
        String blockType,
        String textureTints
) implements CustomPacketPayload {

    public static final Type<GenerationCompleteS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "generation_complete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GenerationCompleteS2C> CODEC =
            StreamCodec.of(GenerationCompleteS2C::write, GenerationCompleteS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, GenerationCompleteS2C payload) {
        buf.writeInt(payload.slotIndex);
        buf.writeUtf(payload.displayName);
        buf.writeUtf(payload.modelJson);
        buf.writeUtf(payload.upperModelJson != null ? payload.upperModelJson : "");
        buf.writeUtf(payload.modelJsonOpen != null ? payload.modelJsonOpen : "");
        buf.writeUtf(payload.upperModelJsonOpen != null ? payload.upperModelJsonOpen : "");
        buf.writeUtf(payload.blockType != null ? payload.blockType : "");
        buf.writeUtf(payload.textureTints != null ? payload.textureTints : "");
    }

    private static GenerationCompleteS2C read(RegistryFriendlyByteBuf buf) {
        return new GenerationCompleteS2C(
                buf.readInt(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
