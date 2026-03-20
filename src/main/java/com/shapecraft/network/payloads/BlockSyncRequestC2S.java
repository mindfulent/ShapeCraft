package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BlockSyncRequestC2S() implements CustomPacketPayload {

    public static final Type<BlockSyncRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "block_sync_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockSyncRequestC2S> CODEC =
            StreamCodec.of(BlockSyncRequestC2S::write, BlockSyncRequestC2S::read);

    private static void write(RegistryFriendlyByteBuf buf, BlockSyncRequestC2S payload) {
        // Empty payload
    }

    private static BlockSyncRequestC2S read(RegistryFriendlyByteBuf buf) {
        return new BlockSyncRequestC2S();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
