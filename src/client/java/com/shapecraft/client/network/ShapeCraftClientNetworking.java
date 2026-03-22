package com.shapecraft.client.network;

import com.shapecraft.client.ShapeCraftClient;
import com.shapecraft.client.model.ModelCache;
import com.shapecraft.client.model.RuntimeModelBaker;
import com.shapecraft.client.screen.TrialExpiredScreen;
import com.shapecraft.network.payloads.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

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
            ShapeCraftClient.LOGGER.info("[Generation] Complete — slot={}, name='{}', tall={}, type='{}'",
                    payload.slotIndex(), payload.displayName(),
                    payload.upperModelJson() != null && !payload.upperModelJson().isEmpty(),
                    payload.blockType());

            // Populate model cache
            ModelCache.put(payload.slotIndex(), new ModelCache.ModelData(
                    payload.slotIndex(),
                    payload.displayName(),
                    payload.modelJson(),
                    payload.upperModelJson(),
                    payload.modelJsonOpen(),
                    payload.upperModelJsonOpen(),
                    payload.blockType(),
                    payload.textureTints()
            ));

            // Runtime-bake the model and refresh chunks — no loading screen
            Minecraft.getInstance().execute(() -> {
                RuntimeModelBaker.bakeAndCache(payload.slotIndex(), payload.modelJson(), payload.upperModelJson());

                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal("[ShapeCraft] Block generated: " + payload.displayName()), false);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(GenerationErrorS2C.TYPE, (payload, context) -> {
            ShapeCraftClient.LOGGER.warn("[Generation] Error: {} (code={})", payload.message(), payload.errorCode());

            Minecraft.getInstance().execute(() -> {
                // Show trial expired screen for trial/license errors
                if (GenerationErrorS2C.CODE_TRIAL_EXHAUSTED.equals(payload.errorCode())
                        || GenerationErrorS2C.CODE_LICENSE_EXPIRED.equals(payload.errorCode())) {
                    Minecraft.getInstance().setScreen(new TrialExpiredScreen());
                    return;
                }

                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal("[ShapeCraft] Error: " + payload.message()), false);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BlockSyncS2C.TYPE, (payload, context) -> {
            ShapeCraftClient.LOGGER.info("[Sync] Received {} block(s)", payload.entries().size());

            Map<Integer, ModelCache.ModelData> newEntries = new HashMap<>();
            for (var entry : payload.entries()) {
                if (!ModelCache.has(entry.slotIndex())) {
                    ModelCache.ModelData data = new ModelCache.ModelData(
                            entry.slotIndex(),
                            entry.displayName(),
                            entry.modelJson(),
                            entry.upperModelJson(),
                            entry.modelJsonOpen(),
                            entry.upperModelJsonOpen(),
                            entry.blockType(),
                            entry.textureTints()
                    );
                    ModelCache.put(entry.slotIndex(), data);
                    newEntries.put(entry.slotIndex(), data);
                }
            }

            if (!newEntries.isEmpty()) {
                Minecraft.getInstance().execute(() -> {
                    RuntimeModelBaker.bakeAndCacheBatch(newEntries);
                });
            }
        });
    }
}
