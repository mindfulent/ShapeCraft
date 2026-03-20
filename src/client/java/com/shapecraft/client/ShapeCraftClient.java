package com.shapecraft.client;

import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.client.model.ShapeCraftModelPlugin;
import com.shapecraft.client.network.ShapeCraftClientNetworking;
import com.shapecraft.network.payloads.HandshakeC2S;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShapeCraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ShapeCraft Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("ShapeCraft Client initializing...");

        // Register model loading plugin — intercepts model loading for pool blocks
        ModelLoadingPlugin.register(new ShapeCraftModelPlugin());

        ShapeCraftClientNetworking.registerReceivers();

        // Send handshake on join
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                if (ClientPlayNetworking.canSend(HandshakeC2S.TYPE)) {
                    LOGGER.info("[Handshake] Sending handshake to server...");
                    ClientPlayNetworking.send(new HandshakeC2S(
                            ShapeCraftConstants.MOD_VERSION,
                            ShapeCraftConstants.PROTOCOL_VERSION
                    ));
                } else {
                    LOGGER.info("[Handshake] Server does not have ShapeCraft — skipping");
                }
            } catch (Exception e) {
                LOGGER.warn("[Handshake] Failed to send: {}", e.getMessage());
            }
        });

        LOGGER.info("ShapeCraft Client initialized");
    }
}
