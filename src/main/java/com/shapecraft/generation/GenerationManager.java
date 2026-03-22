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

        // Check license (local checks: expired/uninitialized state + daily cap only)
        // Trial and monthly limits are enforced by the backend (source of truth)
        var licenseManager = ShapeCraft.getInstance().getLicenseManager();
        if (licenseManager != null && !licenseManager.canGenerate(playerUuid)) {
            String reason = licenseManager.getCannotGenerateReason(playerUuid);
            String errorCode = licenseManager.isLicenseExpired() ? GenerationErrorS2C.CODE_LICENSE_EXPIRED : "";
            ServerPlayNetworking.send(player, new GenerationErrorS2C(reason, errorCode));
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

                // Validate upper model if present
                if (result.upperModelJson() != null && !result.upperModelJson().isEmpty()) {
                    var upperErrors = validator.validate(result.upperModelJson());
                    if (!upperErrors.isEmpty()) {
                        String errorMsg = "Generated upper model failed validation: " + String.join(", ", upperErrors);
                        ShapeCraft.LOGGER.warn("[Generate] Upper model validation failed for '{}': {}", description, errorMsg);
                        server.execute(() -> {
                            ServerPlayNetworking.send(player, new GenerationErrorS2C(errorMsg));
                        });
                        return;
                    }
                }

                // Assign slot on server thread
                server.execute(() -> {
                    String upperModel = result.upperModelJson() != null ? result.upperModelJson() : "";
                    String modelJsonOpen = result.modelJsonOpen() != null ? result.modelJsonOpen() : "";
                    String upperModelJsonOpen = result.upperModelJsonOpen() != null ? result.upperModelJsonOpen() : "";
                    String blockType = result.blockType() != null ? result.blockType() : "";

                    int assignedSlot = pool.assignSlot(result.displayName(), result.modelJson(), upperModel,
                            modelJsonOpen, upperModelJsonOpen, blockType);
                    if (assignedSlot < 0) {
                        ServerPlayNetworking.send(player, new GenerationErrorS2C("Block pool became full during generation."));
                        return;
                    }

                    ShapeCraft.LOGGER.info("[Generate] Success — slot={}, name='{}', type='{}', tokens={}/{}",
                            assignedSlot, result.displayName(), blockType, result.inputTokens(), result.outputTokens());

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
                            assignedSlot, result.displayName(), result.modelJson(), upperModel,
                            modelJsonOpen, upperModelJsonOpen, blockType, result.textureTints());

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
                    // Detect 402 Payment Required (trial exhausted / monthly limit)
                    String errorCode = "";
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof BackendHttpException httpEx && httpEx.getStatusCode() == 402) {
                        errorCode = GenerationErrorS2C.CODE_TRIAL_EXHAUSTED;
                    } else if (cause instanceof BackendHttpException httpEx2 && httpEx2.getStatusCode() == 403) {
                        errorCode = GenerationErrorS2C.CODE_LICENSE_EXPIRED;
                    }
                    ServerPlayNetworking.send(player, new GenerationErrorS2C(
                            "Generation failed: " + cause.getMessage(), errorCode));
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
