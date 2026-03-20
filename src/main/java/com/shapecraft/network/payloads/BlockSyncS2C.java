package com.shapecraft.network.payloads;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record BlockSyncS2C(
        List<BlockSyncEntry> entries
) implements CustomPacketPayload {

    public static final Type<BlockSyncS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "block_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockSyncS2C> CODEC =
            StreamCodec.of(BlockSyncS2C::write, BlockSyncS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, BlockSyncS2C payload) {
        buf.writeInt(payload.entries.size());
        for (BlockSyncEntry entry : payload.entries) {
            buf.writeInt(entry.slotIndex());
            buf.writeUtf(entry.displayName());
            buf.writeUtf(entry.modelJson());
            buf.writeUtf(entry.textureTints() != null ? entry.textureTints() : "");
        }
    }

    private static BlockSyncS2C read(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        List<BlockSyncEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new BlockSyncEntry(
                    buf.readInt(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf()
            ));
        }
        return new BlockSyncS2C(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record BlockSyncEntry(int slotIndex, String displayName, String modelJson, String textureTints) {}
}
