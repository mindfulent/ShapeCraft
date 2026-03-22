package com.shapecraft;

import com.shapecraft.block.BlockPoolManager;
import com.shapecraft.block.PoolBlock;
import com.shapecraft.block.PoolBlockEntity;
import com.shapecraft.command.ShapeCraftCommand;
import com.shapecraft.config.LicenseStore;
import com.shapecraft.config.ShapeCraftConfig;
import com.shapecraft.generation.BackendClient;
import com.shapecraft.generation.GenerationManager;
import com.shapecraft.license.LicenseManager;
import com.shapecraft.license.LicenseValidator;
import com.shapecraft.network.ShapeCraftNetworking;
import com.shapecraft.persistence.WorldDataManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShapeCraft implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(ShapeCraftConstants.MOD_ID);

    private static ShapeCraft instance;

    public static final Block[] POOL_BLOCKS = new Block[ShapeCraftConstants.DEFAULT_POOL_SIZE];
    public static final BlockItem[] POOL_ITEMS = new BlockItem[ShapeCraftConstants.DEFAULT_POOL_SIZE];
    public static BlockEntityType<PoolBlockEntity> POOL_BLOCK_ENTITY_TYPE;

    private BlockPoolManager blockPoolManager;
    private GenerationManager generationManager;
    private BackendClient backendClient;
    private WorldDataManager worldDataManager;
    private LicenseManager licenseManager;
    private ShapeCraftConfig config;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("Initializing ShapeCraft v{}...", ShapeCraftConstants.MOD_VERSION);

        // Register pool blocks and items
        for (int i = 0; i < ShapeCraftConstants.DEFAULT_POOL_SIZE; i++) {
            ResourceLocation blockId = ResourceLocation.fromNamespaceAndPath(
                    ShapeCraftConstants.MOD_ID, ShapeCraftConstants.POOL_BLOCK_PREFIX + i);

            PoolBlock block = new PoolBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5f, 6.0f)
                    .dynamicShape()
                    .noOcclusion(), i);

            Block registeredBlock = Registry.register(BuiltInRegistries.BLOCK, blockId, block);
            POOL_BLOCKS[i] = registeredBlock;

            BlockItem item = new BlockItem(registeredBlock, new Item.Properties());
            POOL_ITEMS[i] = Registry.register(BuiltInRegistries.ITEM, blockId, item);
        }

        // Register block entity type for all pool blocks
        POOL_BLOCK_ENTITY_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "pool_block_entity"),
                FabricBlockEntityTypeBuilder.create(PoolBlockEntity::new, POOL_BLOCKS)
                        .build());

        // Register creative tab
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(ShapeCraftConstants.MOD_ID, "shapecraft_tab"),
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.shapecraft"))
                        .icon(() -> new ItemStack(POOL_ITEMS[0]))
                        .displayItems((params, output) -> {
                            for (int i = 0; i < ShapeCraftConstants.DEFAULT_POOL_SIZE; i++) {
                                if (blockPoolManager != null && blockPoolManager.isSlotAssigned(i)) {
                                    output.accept(POOL_ITEMS[i]);
                                }
                            }
                        })
                        .build());

        blockPoolManager = new BlockPoolManager();

        // Load config
        config = ShapeCraftConfig.load();

        // Initialize license system
        LicenseStore licenseStore = new LicenseStore();
        LicenseValidator licenseValidator = new LicenseValidator(config.getBackendUrl());
        licenseManager = new LicenseManager(licenseStore, licenseValidator);

        // Initialize backend client and generation manager
        backendClient = new BackendClient(config.getBackendUrl());
        generationManager = new GenerationManager(backendClient);

        // Register networking
        ShapeCraftNetworking.registerPayloads();
        ShapeCraftNetworking.registerServerReceivers();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ShapeCraftCommand.register(dispatcher);
        });

        // Load persisted data and initialize license when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            worldDataManager = WorldDataManager.loadOrCreate(server, blockPoolManager);
            LOGGER.info("ShapeCraft world data loaded — {} blocks assigned",
                    blockPoolManager.getAssignedCount());

            String ip = server.getLocalIp() != null ? server.getLocalIp() : "localhost";
            int port = server.getPort();
            licenseManager.initialize(server, ip, port);
            LOGGER.info("ShapeCraft license state: {}", licenseManager.getState());

            // Wire license key to backend client for authenticated API calls
            if (licenseManager.getLicenseKey() != null) {
                backendClient.setAuthToken(licenseManager.getLicenseKey());
            }
        });

        // Periodic license validation
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % ShapeCraftConstants.VALIDATION_POLL_TICKS == 0) {
                int players = server.getPlayerList().getPlayerCount();
                licenseManager.periodicValidation(players);
            }
        });

        // Shutdown generation thread pool
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            generationManager.shutdown();
        });

        LOGGER.info("ShapeCraft ready — {} pool blocks registered", ShapeCraftConstants.DEFAULT_POOL_SIZE);
    }

    public static ShapeCraft getInstance() {
        return instance;
    }

    public BlockPoolManager getBlockPoolManager() {
        return blockPoolManager;
    }

    public GenerationManager getGenerationManager() {
        return generationManager;
    }

    public BackendClient getBackendClient() {
        return backendClient;
    }

    public WorldDataManager getWorldDataManager() {
        return worldDataManager;
    }

    public LicenseManager getLicenseManager() {
        return licenseManager;
    }

    public ShapeCraftConfig getConfig() {
        return config;
    }
}
