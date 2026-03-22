package com.shapecraft.network;

import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.block.BlockPoolManager;
import com.shapecraft.network.payloads.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.ArrayList;
import java.util.List;

public class ShapeCraftNetworking {

    public static void registerPayloads() {
        // C2S
        PayloadTypeRegistry.playC2S().register(HandshakeC2S.TYPE, HandshakeC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(GenerateRequestC2S.TYPE, GenerateRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(BlockSyncRequestC2S.TYPE, BlockSyncRequestC2S.CODEC);

        // S2C
        PayloadTypeRegistry.playS2C().register(HandshakeResponseS2C.TYPE, HandshakeResponseS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GenerationStatusS2C.TYPE, GenerationStatusS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GenerationCompleteS2C.TYPE, GenerationCompleteS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GenerationErrorS2C.TYPE, GenerationErrorS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BlockSyncS2C.TYPE, BlockSyncS2C.CODEC);
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(HandshakeC2S.TYPE, (payload, context) -> {
            var player = context.player();
            ShapeCraft.LOGGER.info("[Handshake] Received from {} — client v{}, protocol {}",
                    player.getName().getString(), payload.modVersion(), payload.protocolVersion());

            if (payload.protocolVersion() != ShapeCraftConstants.PROTOCOL_VERSION) {
                String msg = "ShapeCraft version mismatch! Server has v" + ShapeCraftConstants.MOD_VERSION
                        + " (protocol " + ShapeCraftConstants.PROTOCOL_VERSION
                        + "), your client has v" + payload.modVersion()
                        + " (protocol " + payload.protocolVersion()
                        + "). Please update your ShapeCraft mod to match the server.";
                ShapeCraft.LOGGER.warn("[Handshake] Protocol mismatch from {} — server={}, client={}",
                        player.getName().getString(), ShapeCraftConstants.PROTOCOL_VERSION, payload.protocolVersion());
                ServerPlayNetworking.send(player, new HandshakeResponseS2C(
                        false, ShapeCraftConstants.PROTOCOL_VERSION, msg, 0));
                return;
            }

            ServerPlayNetworking.send(player, new HandshakeResponseS2C(
                    true,
                    ShapeCraftConstants.PROTOCOL_VERSION,
                    "Welcome to ShapeCraft!",
                    ShapeCraftConstants.DEFAULT_POOL_SIZE));

            // Auto-sync all assigned blocks to joining player
            sendBlockSync(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(GenerateRequestC2S.TYPE, (payload, context) -> {
            var player = context.player();
            String description = payload.description();
            ShapeCraft.LOGGER.info("[Generate] C2S request from {}: '{}'",
                    player.getName().getString(), description);

            ShapeCraft.getInstance().getGenerationManager()
                    .submit(context.player().server, player, description);
        });

        ServerPlayNetworking.registerGlobalReceiver(BlockSyncRequestC2S.TYPE, (payload, context) -> {
            sendBlockSync(context.player());
        });
    }

    private static void sendBlockSync(net.minecraft.server.level.ServerPlayer player) {
        var poolManager = ShapeCraft.getInstance().getBlockPoolManager();
        var allSlots = poolManager.getAllSlots();

        if (allSlots.isEmpty()) return;

        List<BlockSyncS2C.BlockSyncEntry> entries = new ArrayList<>();
        for (var entry : allSlots.entrySet()) {
            var data = entry.getValue();
            entries.add(new BlockSyncS2C.BlockSyncEntry(
                    data.slotIndex(), data.displayName(), data.modelJson(),
                    data.upperModelJson() != null ? data.upperModelJson() : "",
                    data.modelJsonOpen() != null ? data.modelJsonOpen() : "",
                    data.upperModelJsonOpen() != null ? data.upperModelJsonOpen() : "",
                    data.blockType() != null ? data.blockType() : "",
                    ""));
        }

        ShapeCraft.LOGGER.info("[Sync] Sending {} blocks to {}",
                entries.size(), player.getName().getString());
        ServerPlayNetworking.send(player, new BlockSyncS2C(entries));
    }
}
