package com.shapecraft.generation;

import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.block.BlockPoolManager;
import com.shapecraft.config.ContentFilter;
import com.shapecraft.network.payloads.GenerationCompleteS2C;
import com.shapecraft.network.payloads.GenerationErrorS2C;
import com.shapecraft.network.payloads.GenerationStatusS2C;
import com.shapecraft.validation.ModelValidator;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GenerationManager {

    private final BackendClient backendClient;
    private final ModelValidator validator;
    private final ExecutorService threadPool;
    private final Set<UUID> activeGenerations = ConcurrentHashMap.newKeySet();

    public GenerationManager(BackendClient backendClient) {
        this.backendClient = backendClient;
        this.validator = new ModelValidator();
        this.threadPool = Executors.newFixedThreadPool(ShapeCraftConstants.GENERATION_THREAD_POOL_SIZE);
    }

    public void submit(MinecraftServer server, ServerPlayer player, String description) {
        UUID playerUuid = player.getUUID();

        // Check license
        var licenseManager = ShapeCraft.getInstance().getLicenseManager();
        if (licenseManager != null && !licenseManager.canGenerate(playerUuid)) {
            ServerPlayNetworking.send(player, new GenerationErrorS2C(
                    licenseManager.getCannotGenerateReason(playerUuid)));
            return;
        }

        // Content filter
        String filterResult = ContentFilter.check(description);
        if (filterResult != null) {
            ServerPlayNetworking.send(player, new GenerationErrorS2C(filterResult));
            return;
        }

        // Prevent concurrent generations from same player
        if (!activeGenerations.add(playerUuid)) {
            ServerPlayNetworking.send(player, new GenerationErrorS2C(
                    "You already have a generation in progress. Please wait."));
            return;
        }

        // Check pool capacity
        BlockPoolManager pool = ShapeCraft.getInstance().getBlockPoolManager();
        int slot = pool.getNextAvailable();
        if (slot < 0) {
            activeGenerations.remove(playerUuid);
            ServerPlayNetworking.send(player, new GenerationErrorS2C(
                    "Block pool is full (" + ShapeCraftConstants.DEFAULT_POOL_SIZE + "/" + ShapeCraftConstants.DEFAULT_POOL_SIZE + ")."));
            return;
        }

        GenerationRequest request = GenerationRequest.create(playerUuid, player.getName().getString(), description);

        // Send status update
        ServerPlayNetworking.send(player, new GenerationStatusS2C(
                GenerationStatusS2C.STATUS_QUEUED, "Generating your block..."));

        threadPool.submit(() -> {
            try {
                ServerPlayNetworking.send(player, new GenerationStatusS2C(
                        GenerationStatusS2C.STATUS_GENERATING, "Calling AI backend..."));

                GenerationResult result = backendClient.generate(request).join();

                // Validate
                ServerPlayNetworking.send(player, new GenerationStatusS2C(
                        GenerationStatusS2C.STATUS_VALIDATING, "Validating model..."));

                var errors = validator.validate(result.modelJson());
                if (!errors.isEmpty()) {
                    String errorMsg = "Generated model failed validation: " + String.join(", ", errors);
                    ShapeCraft.LOGGER.warn("[Generate] Validation failed for '{}': {}", description, errorMsg);
                    server.execute(() -> {
                        ServerPlayNetworking.send(player, new GenerationErrorS2C(errorMsg));
                    });
                    return;
                }

                // Assign slot on server thread
                server.execute(() -> {
                    int assignedSlot = pool.assignSlot(result.displayName(), result.modelJson());
                    if (assignedSlot < 0) {
                        ServerPlayNetworking.send(player, new GenerationErrorS2C("Block pool became full during generation."));
                        return;
                    }

                    ShapeCraft.LOGGER.info("[Generate] Success — slot={}, name='{}', tokens={}/{}",
                            assignedSlot, result.displayName(), result.inputTokens(), result.outputTokens());

                    // Record generation for license tracking
                    if (licenseManager != null) {
                        licenseManager.recordGeneration(playerUuid);
                    }

                    // Mark world data as dirty for persistence
                    var worldData = ShapeCraft.getInstance().getWorldDataManager();
                    if (worldData != null) {
                        worldData.markDirty();
                    }

                    // Broadcast to all players
                    GenerationCompleteS2C completePayload = new GenerationCompleteS2C(
                            assignedSlot, result.displayName(), result.modelJson(), result.textureTints());

                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        ServerPlayNetworking.send(p, completePayload);
                    }

                    // Give block to requesting player
                    ItemStack stack = new ItemStack(ShapeCraft.POOL_ITEMS[assignedSlot]);
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                });

            } catch (Exception e) {
                ShapeCraft.LOGGER.error("[Generate] Failed for '{}': {}", description, e.getMessage());
                server.execute(() -> {
                    ServerPlayNetworking.send(player, new GenerationErrorS2C(
                            "Generation failed: " + e.getMessage()));
                });
            } finally {
                activeGenerations.remove(playerUuid);
            }
        });
    }

    public void shutdown() {
        threadPool.shutdown();
    }
}
