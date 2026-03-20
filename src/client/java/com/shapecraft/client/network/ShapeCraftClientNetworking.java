package com.shapecraft.client.network;

import com.shapecraft.client.ShapeCraftClient;
import com.shapecraft.client.model.ModelCache;
import com.shapecraft.network.payloads.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ShapeCraftClientNetworking {

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(HandshakeResponseS2C.TYPE, (payload, context) -> {
            ShapeCraftClient.LOGGER.info("[Handshake] Response — success={}, protocol={}, poolSize={}, message='{}'",
                    payload.success(), payload.protocolVersion(), payload.poolSize(), payload.message());
        });

        ClientPlayNetworking.registerGlobalReceiver(GenerationStatusS2C.TYPE, (payload, context) -> {
            String statusName = switch (payload.status()) {
                case GenerationStatusS2C.STATUS_QUEUED -> "Queued";
                case GenerationStatusS2C.STATUS_GENERATING -> "Generating";
                case GenerationStatusS2C.STATUS_VALIDATING -> "Validating";
                default -> "Unknown";
            };
            ShapeCraftClient.LOGGER.info("[Generation] {} — {}", statusName, payload.message());

            // Show status in action bar
            Minecraft.getInstance().execute(() -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal("[ShapeCraft] " + payload.message()), true);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(GenerationCompleteS2C.TYPE, (payload, context) -> {
            ShapeCraftClient.LOGGER.info("[Generation] Complete — slot={}, name='{}'",
                    payload.slotIndex(), payload.displayName());

            // Populate model cache
            ModelCache.put(payload.slotIndex(), new ModelCache.ModelData(
                    payload.slotIndex(),
                    payload.displayName(),
                    payload.modelJson(),
                    payload.textureTints()
            ));

            // Trigger resource reload to rebake models
            Minecraft.getInstance().execute(() -> {
                ShapeCraftClient.LOGGER.info("[Model] Triggering resource reload for new block...");
                Minecraft.getInstance().reloadResourcePacks();

                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal("[ShapeCraft] Block generated: " + payload.displayName()), false);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(GenerationErrorS2C.TYPE, (payload, context) -> {
            ShapeCraftClient.LOGGER.warn("[Generation] Error: {}", payload.message());

            Minecraft.getInstance().execute(() -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal("[ShapeCraft] Error: " + payload.message()), false);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BlockSyncS2C.TYPE, (payload, context) -> {
            ShapeCraftClient.LOGGER.info("[Sync] Received {} block(s)", payload.entries().size());

            boolean hadNewData = false;
            for (var entry : payload.entries()) {
                if (!ModelCache.has(entry.slotIndex())) {
                    ModelCache.put(entry.slotIndex(), new ModelCache.ModelData(
                            entry.slotIndex(),
                            entry.displayName(),
                            entry.modelJson(),
                            entry.textureTints()
                    ));
                    hadNewData = true;
                }
            }

            if (hadNewData) {
                Minecraft.getInstance().execute(() -> {
                    ShapeCraftClient.LOGGER.info("[Model] Triggering resource reload for synced blocks...");
                    Minecraft.getInstance().reloadResourcePacks();
                });
            }
        });
    }
}
