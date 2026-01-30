package com.suiramdev.worldmap;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.suiramdev.worldmap.config.PluginConfig;
import com.suiramdev.worldmap.managers.AssetManager;
import com.suiramdev.worldmap.managers.ChunkManager;
import com.suiramdev.worldmap.util.WorldmapLog;
import com.suiramdev.worldmap.services.AssetService;
import com.suiramdev.worldmap.services.ChunkService;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Main plugin class for the Worldmap plugin.
 * 
 * <p>
 * This plugin processes world chunks and sends them to an external API
 * for real-time map visualization. It follows Minecraft-style plugin
 * architecture
 * patterns while using Hytale's plugin system.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class Main extends JavaPlugin {

    private static Main instance;

    // Configuration and services
    private Config<PluginConfig> config;
    private ChunkService chunkService;
    private AssetService assetService;

    // Managers
    private ChunkManager chunkManager;
    private AssetManager assetManager;

    /**
     * Constructor - Called when plugin is loaded.
     *
     * @param init The plugin initialization data
     */
    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        config = withConfig(PluginConfig.CODEC);
    }

    @Override
    protected void setup() {
        instance = this;
        getCommandRegistry().registerCommand(new com.suiramdev.worldmap.commands.WorldmapCommand());
        WorldmapLog.info("Plugin setup complete!");
    }

    @Override
    protected void start() {
        WorldmapLog.info("Enabling plugin...");

        try {
            WorldmapLog.info("Configuration loaded - API Base URL: %s", config.get().getApiBaseUrl());

            // Initialize services
            initializeServices();

            // Initialize managers
            initializeManagers();

            // If API key is missing, do not start processing; set it via /worldmap key
            String apiKey = config.get().getApiKey();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                chunkManager.haltDueToAuth("API key not set. Use /worldmap key set <key> to set the API key, then /worldmap process start.");
                WorldmapLog.info("API key is not set. Chunk processing halted. Use /worldmap key set <key>, then /worldmap process start.");
            } else {
                // Fetch processed chunks list from API first (wait for it; world derived from API key)
                WorldmapLog.info("Fetching processed chunks list from API...");
                chunkManager.fetchProcessedChunksList().join();

                // Send asset map on startup
                sendAssetMapOnStartup();

                // Process chunks asynchronously (will use API list)
                processAllChunks();
            }

            // Register for block place/break/damage to re-send modified chunks
            registerBlockEventSystems();

            // Register for new chunk loads (discovered by players) to send new chunks
            registerChunkLoadEvent();

        } catch (Exception e) {
            WorldmapLog.severe("Failed to initialize plugin: " + e.getMessage(), e);
        }
    }

    /**
     * Registers ECS event systems for PlaceBlockEvent, BreakBlockEvent, and
     * DamageBlockEvent
     * so that modified chunks are re-sent to the API.
     */
    private void registerBlockEventSystems() {
        getEntityStoreRegistry().registerSystem(new WorldmapPlaceBlockEventSystem());
        getEntityStoreRegistry().registerSystem(new WorldmapBreakBlockEventSystem());
        getEntityStoreRegistry().registerSystem(new WorldmapDamageBlockEventSystem());
        WorldmapLog.info("Registered block event systems (place/break/damage)");
    }

    /**
     * Registers for ChunkPreLoadProcessEvent so that newly loaded chunks are sent
     * to the API.
     */
    private void registerChunkLoadEvent() {
        getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class, this::onChunkPreLoadProcess);
        WorldmapLog.info("Registered chunk load event (new chunks will be sent)");
    }

    private void onChunkPreLoadProcess(ChunkPreLoadProcessEvent event) {
        WorldChunk chunk = event.getChunk();
        World world = chunk.getWorld();
        // Only process chunks from the default world (same as processAllChunks)
        Universe universe = Universe.get();
        if (universe == null || world != universe.getDefaultWorld()) {
            return;
        }
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        chunkManager.processChunkAsyncIfNeeded(chunkX, chunkZ, chunk, world);
    }

    @Override
    protected void shutdown() {
        WorldmapLog.info("Disabling plugin...");

        // Shutdown chunk manager (waits for ongoing tasks)
        if (chunkManager != null) {
            WorldmapLog.info("Shutting down chunk manager...");
            chunkManager.shutdown();
        }

        WorldmapLog.info("Plugin disabled successfully!");
    }

    /**
     * Initializes all service instances.
     */
    private void initializeServices() {
        // Initialize chunk service
        chunkService = new ChunkService(
                config.get().getApiBaseUrl(),
                config.get().getApiKey(),
                config.get().getRequestTimeout(),
                config.get().getMaxRetries(),
                config.get().isDebugMode());

        // Initialize asset service (handles asset-map)
        assetService = new AssetService(
                config.get().getApiBaseUrl(),
                config.get().getApiKey(),
                config.get().getRequestTimeout(),
                config.get().getMaxRetries(),
                config.get().isDebugMode());
    }

    /**
     * Initializes all manager instances.
     */
    private void initializeManagers() {
        // Initialize asset manager
        assetManager = new AssetManager(config.get().isDebugMode());

        // Initialize chunk manager (requires asset service and manager for
        // coordination)
        chunkManager = new ChunkManager(chunkService, assetService, assetManager, config.get().isDebugMode());
    }

    /**
     * Sends asset map to API on plugin startup.
     * 
     * <p>
     * Retries gathering the asset map with delays, as the BlockType registry
     * may not be fully initialized immediately at plugin startup.
     * </p>
     */
    private void sendAssetMapOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                WorldmapLog.info("Sending asset-map to API");

                // Gather asset map with retries (BlockType registry may not be ready
                // immediately)
                var assetMap = gatherAssetMapWithRetry();

                if (assetMap == null || assetMap.isEmpty()) {
                    WorldmapLog.warn("No asset map data gathered after retries. " +
                            "BlockType registry may not be available yet. Asset-map will be sent when needed.");
                    return;
                }

                WorldmapLog.info("Gathered %d block entries for asset map", assetMap.size());

                // Send to API (world derived from API key)
                assetService.sendAssetMap(assetMap)
                        .thenAccept(success -> {
                            if (success) {
                                WorldmapLog.info("Asset-map sent successfully");
                            } else {
                                WorldmapLog.warn("Failed to send asset-map");
                            }
                        })
                        .exceptionally(throwable -> {
                            WorldmapLog.severe("Error sending asset-map: " + throwable.getMessage(), throwable);
                            return null;
                        });
            } catch (Exception e) {
                WorldmapLog.severe("Error getting world for asset-map: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Gathers asset map with retry logic, as the BlockType registry may not be
     * initialized immediately.
     * 
     * @return List of asset map entries, or empty list if unavailable after retries
     */
    private java.util.List<com.suiramdev.worldmap.models.AssetMapPayload> gatherAssetMapWithRetry() {
        int maxRetries = 5;
        long delayMs = 1000; // Start with 1 second delay

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            var assetMap = assetManager.gatherAssetMap();

            if (assetMap != null && !assetMap.isEmpty()) {
                if (attempt > 0) {
                    WorldmapLog.info("Successfully gathered asset map on attempt %d", attempt + 1);
                }
                return assetMap;
            }

            if (attempt < maxRetries - 1) {
                WorldmapLog.info("Asset map not available yet, retrying in %dms (attempt %d/%d)",
                        delayMs, attempt + 2, maxRetries);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    WorldmapLog.severe("Interrupted while waiting for asset map");
                    return new java.util.ArrayList<>();
                }
                delayMs *= 2; // Exponential backoff
            }
        }

        WorldmapLog.severe("Failed to gather asset map after %d attempts", maxRetries);
        return new java.util.ArrayList<>();
    }

    /**
     * Processes all chunks asynchronously to avoid blocking server startup.
     * Only unprocessed chunks will be sent to the API.
     */
    private void processAllChunksAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                processAllChunks();
                WorldmapLog.info("Chunk processing queued!");
                WorldmapLog.info("Chunks will be processed in the background");
            } catch (Exception e) {
                WorldmapLog.severe("Error during chunk processing: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Public entry point for (re)starting chunk processing (e.g. from /worldmap process start).
     */
    public void processAllChunksPublic() {
        processAllChunks();
    }

    /**
     * Processes all chunks from the world.
     */
    private void processAllChunks() {
        WorldmapLog.info("Starting chunk processing...");

        try {
            // Get the default world from Universe
            Universe universe = Universe.get();
            if (universe == null) {
                WorldmapLog.severe("Universe is not available");
                return;
            }

            World world = universe.getDefaultWorld();
            if (world == null) {
                WorldmapLog.severe("Default world is not available");
                return;
            }

            // Get all chunk indexes from the chunk loader
            ChunkStore chunkStore = world.getChunkStore();

            // Wait for the loader to be initialized (it may not be ready immediately)
            IChunkLoader loader = null;
            int retries = 10;
            for (int i = 0; i < retries; i++) {
                loader = chunkStore.getLoader();
                if (loader != null) {
                    break;
                }
                try {
                    Thread.sleep(500); // Wait 500ms before retrying
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    WorldmapLog.severe("Interrupted while waiting for chunk loader");
                    return;
                }
            }

            LongSet chunkIndexes;
            if (loader != null) {
                // Get all chunk indexes from disk via the loader
                try {
                    chunkIndexes = loader.getIndexes();
                    WorldmapLog.info("Loaded chunk indexes from disk storage");
                } catch (IOException e) {
                    WorldmapLog.severe("Error getting chunk indexes from loader: " + e.getMessage(), e);
                    // Fallback to currently loaded chunks
                    chunkIndexes = chunkStore.getChunkIndexes();
                    WorldmapLog.info("Falling back to currently loaded chunks: %d", chunkIndexes.size());
                }
            } else {
                // Fallback to currently loaded chunks if loader is not available
                WorldmapLog.info("Chunk loader not available, using currently loaded chunks");
                chunkIndexes = chunkStore.getChunkIndexes();
                if (chunkIndexes.isEmpty()) {
                    WorldmapLog.severe("No chunks are currently loaded. The loader may not be initialized yet.");
                    return;
                }
            }

            int totalChunks = chunkIndexes.size();
            WorldmapLog.info("Found %d chunks to check", totalChunks);

            // Process each chunk - only process chunks that haven't been sent to the API
            int queued = 0;
            int skipped = 0;
            for (long chunkIndex : chunkIndexes) {
                int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
                int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);

                // Check if chunk has already been processed (using API list)
                if (chunkManager.isChunkProcessed(chunkX, chunkZ)) {
                    skipped++;
                    continue;
                }

                // Get chunk asynchronously (non-ticking to avoid affecting gameplay)
                world.getNonTickingChunkAsync(chunkIndex)
                        .thenAccept(chunk -> {
                            if (chunk != null) {
                                chunkManager.processChunk(chunkX, chunkZ, chunk, world);
                            } else {
                                if (config.get().isDebugMode()) {
                                    WorldmapLog.fine("Chunk (%d,%d) is null, skipping", chunkX, chunkZ);
                                }
                            }
                        })
                        .exceptionally(throwable -> {
                            WorldmapLog.severe("Error loading chunk (" + chunkX + "," + chunkZ + "): "
                                    + throwable.getMessage(), throwable);
                            return null;
                        });

                queued++;

                // Log progress every 100 chunks
                if ((queued + skipped) % 100 == 0) {
                    WorldmapLog.info("Queued %d / %d chunks for processing (%d already processed)",
                            queued, totalChunks, skipped);
                }
            }

            WorldmapLog.info("Queued %d unprocessed chunks for processing (%d chunks were already processed)",
                    queued, skipped);
        } catch (Exception e) {
            WorldmapLog.severe("Error getting world/chunks: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the plugin instance.
     * 
     * @return The plugin instance, or null if not initialized
     */
    public static Main getInstance() {
        return instance;
    }

    /**
     * Gets the plugin configuration.
     * 
     * @return The plugin configuration
     */
    public PluginConfig getConfig() {
        return config.get();
    }

    /**
     * Gets the chunk manager.
     * 
     * @return The chunk manager
     */
    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    /**
     * Gets the asset map service.
     *
     * @return The asset map service
     */
    public AssetService getAssetService() {
        return assetService;
    }

    /**
     * Gets the plugin config wrapper (for saving). The inner config is mutable via getConfig() setters.
     */
    public Config<PluginConfig> getConfigHolder() {
        return config;
    }

    /**
     * Updates the API key in config, saves to disk, and updates services. Optionally restarts chunk processing.
     *
     * @param newApiKey The new API key
     * @param restartProcessing If true, start chunk processing after updating (e.g. after /worldmap key set <key> restart)
     */
    public void updateApiKeyAndSave(String newApiKey, boolean restartProcessing) {
        config.get().setApiKey(newApiKey != null ? newApiKey.trim() : "");
        config.save().join();
        chunkService.setApiKey(config.get().getApiKey());
        assetService.setApiKey(config.get().getApiKey());
        if (restartProcessing) {
            chunkManager.startProcessing();
            chunkManager.fetchProcessedChunksList().thenRun(() -> {
                World world = Universe.get() != null ? Universe.get().getDefaultWorld() : null;
                if (world != null) {
                    world.execute(this::processAllChunks);
                } else {
                    processAllChunks();
                }
            });
        }
    }

    /**
     * Saves the current plugin config to disk.
     */
    public void saveConfig() {
        config.save().join();
    }

    /**
     * Builds status text (state, API key, counts, last error, commands).
     */
    public String getAdminStatusText() {
        if (chunkManager == null) {
            return "Plugin not fully loaded.";
        }
        String state = chunkManager.getProcessingState();
        int processed = chunkManager.getProcessedCount();
        int failed = chunkManager.getFailedCount();
        String lastError = chunkManager.getLastErrorMessage();
        String apiKeyStatus = config.get().getApiKey() == null || config.get().getApiKey().trim().isEmpty()
                ? "Not set"
                : "Set (" + config.get().getApiKey().length() + " chars)";

        StringBuilder sb = new StringBuilder();
        sb.append("State: ").append(state).append("\n");
        sb.append("API key: ").append(apiKeyStatus).append("\n");
        sb.append("Processed: ").append(processed).append(" | Failed: ").append(failed).append("\n");
        if (lastError != null && !lastError.isEmpty()) {
            sb.append("Last error: ").append(lastError).append("\n");
        }
        sb.append("\nCommands: /worldmap key get | key set <key> [restart] | process start | process stop | process force <chunkX> <chunkZ> | logs");
        return sb.toString();
    }

    // --- Block event systems (ECS) - re-send chunk to API when blocks change ---

    private static final class WorldmapPlaceBlockEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        WorldmapPlaceBlockEventSystem() {
            super(PlaceBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull PlaceBlockEvent event) {
            scheduleChunkResendForBlockEvent(commandBuffer, event.getTargetBlock());
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    private static final class WorldmapBreakBlockEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        WorldmapBreakBlockEventSystem() {
            super(BreakBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull BreakBlockEvent event) {
            scheduleChunkResendForBlockEvent(commandBuffer, event.getTargetBlock());
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    private static final class WorldmapDamageBlockEventSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {
        WorldmapDamageBlockEventSystem() {
            super(DamageBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event) {
            scheduleChunkResendForBlockEvent(commandBuffer, event.getTargetBlock());
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    private static void scheduleChunkResendForBlockEvent(CommandBuffer<EntityStore> commandBuffer, Vector3i blockPos) {
        World world = commandBuffer.getExternalData().getWorld();
        int chunkX = ChunkUtil.chunkCoordinate(blockPos.x);
        int chunkZ = ChunkUtil.chunkCoordinate(blockPos.z);
        Main main = Main.getInstance();
        if (main != null) {
            ChunkManager cm = main.getChunkManager();
            if (cm != null) {
                cm.scheduleChunkResend(world, chunkX, chunkZ);
            }
        }
    }
}
