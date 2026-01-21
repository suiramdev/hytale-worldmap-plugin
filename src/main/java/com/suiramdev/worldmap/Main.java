package com.suiramdev.worldmap;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.suiramdev.worldmap.config.PluginConfig;
import com.suiramdev.worldmap.managers.AssetMapManager;
import com.suiramdev.worldmap.managers.ChunkManager;
import com.suiramdev.worldmap.services.AssetMapService;
import com.suiramdev.worldmap.services.ChunkService;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nonnull;
import java.io.File;
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
    private PluginConfig config;
    private ChunkService chunkService;
    private AssetMapService assetMapService;

    // Managers
    private ChunkManager chunkManager;
    private AssetMapManager assetMapManager;

    // Plugin data
    private File dataFolder;

    /**
     * Constructor - Called when plugin is loaded.
     * 
     * @param init The plugin initialization data
     */
    public Main(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        instance = this;
        System.out.println("[Worldmap] Plugin setup complete!");
    }

    @Override
    protected void start() {
        System.out.println("[Worldmap] Enabling plugin...");

        try {
            // Initialize data folder
            dataFolder = getDataFolder();

            // Load configuration
            config = new PluginConfig(dataFolder);
            System.out.println("[Worldmap] Configuration loaded - API Base URL: " + config.getApiBaseUrl());

            // Initialize services
            initializeServices();

            // Initialize managers
            initializeManagers();

            // Fetch processed chunks list from API first (wait for it)
            String worldId = getWorldId();
            if (worldId != null && !worldId.isEmpty()) {
                System.out.println("[Worldmap] Fetching processed chunks list from API...");
                chunkManager.fetchProcessedChunksList(worldId).join();
            } else {
                System.err.println(
                        "[Worldmap] WARNING: worldId not configured, cannot fetch processed chunks list");
            }

            // Send asset map on startup
            sendAssetMapOnStartup();

            // Process chunks asynchronously (will use API list)
            processAllChunks();

        } catch (Exception e) {
            System.err.println("[Worldmap] ERROR: Failed to initialize plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void shutdown() {
        System.out.println("[Worldmap] Disabling plugin...");

        // Shutdown chunk manager (waits for ongoing tasks)
        if (chunkManager != null) {
            System.out.println("[Worldmap] Shutting down chunk manager...");
            chunkManager.shutdown();
        }

        System.out.println("[Worldmap] Plugin disabled successfully!");
    }

    /**
     * Initializes all service instances.
     */
    private void initializeServices() {
        // Initialize chunk service
        chunkService = new ChunkService(
                config.getApiBaseUrl(),
                config.getApiKey(),
                config.getRequestTimeout(),
                config.getMaxRetries(),
                config.isDebugMode());

        // Initialize asset map service
        assetMapService = new AssetMapService(
                config.getApiBaseUrl(),
                config.getApiKey(),
                config.getRequestTimeout(),
                config.getMaxRetries(),
                config.isDebugMode());
    }

    /**
     * Initializes all manager instances.
     */
    private void initializeManagers() {
        // Initialize asset map manager
        assetMapManager = new AssetMapManager(config.isDebugMode());

        // Initialize chunk manager (requires asset map service and manager for
        // coordination)
        chunkManager = new ChunkManager(chunkService, assetMapService, assetMapManager, config.isDebugMode());
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
                // Get worldId from config, with fallback to world name if not configured
                String worldId = getWorldId();
                if (worldId == null || worldId.isEmpty()) {
                    System.err.println(
                            "[Worldmap] WARNING: worldId not configured in config.json, skipping asset-map send");
                    return;
                }

                System.out.println("[Worldmap] Sending asset-map to API for world: " + worldId);

                // Gather asset map with retries (BlockType registry may not be ready
                // immediately)
                var assetMap = gatherAssetMapWithRetry();

                if (assetMap == null || assetMap.isEmpty()) {
                    System.err.println("[Worldmap] WARNING: No asset map data gathered after retries. " +
                            "BlockType registry may not be available yet. Asset-map will be sent when needed.");
                    return;
                }

                System.out.println("[Worldmap] Gathered " + assetMap.size() + " block entries for asset map");

                // Send to API
                assetMapService.sendAssetMap(worldId, assetMap)
                        .thenAccept(success -> {
                            if (success) {
                                System.out.println("[Worldmap] Asset-map sent successfully for world: " + worldId);
                            } else {
                                System.err
                                        .println("[Worldmap] WARNING: Failed to send asset-map for world: " + worldId);
                            }
                        })
                        .exceptionally(throwable -> {
                            System.err.println("[Worldmap] ERROR: Error sending asset-map: " + throwable.getMessage());
                            throwable.printStackTrace();
                            return null;
                        });
            } catch (Exception e) {
                System.err.println("[Worldmap] ERROR: Error getting world for asset-map: " + e.getMessage());
                e.printStackTrace();
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
            var assetMap = assetMapManager.gatherAssetMap();

            if (assetMap != null && !assetMap.isEmpty()) {
                if (attempt > 0) {
                    System.out.println("[Worldmap] Successfully gathered asset map on attempt " + (attempt + 1));
                }
                return assetMap;
            }

            if (attempt < maxRetries - 1) {
                System.out.println("[Worldmap] Asset map not available yet, retrying in " + delayMs + "ms (attempt "
                        + (attempt + 2) + "/" + maxRetries + ")");
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[Worldmap] Interrupted while waiting for asset map");
                    return new java.util.ArrayList<>();
                }
                delayMs *= 2; // Exponential backoff
            }
        }

        System.err.println("[Worldmap] Failed to gather asset map after " + maxRetries + " attempts");
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
                System.out.println("[Worldmap] Chunk processing queued!");
                System.out.println("[Worldmap] Chunks will be processed in the background");
            } catch (Exception e) {
                System.err.println("[Worldmap] ERROR: Error during chunk processing: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Processes all chunks from the world.
     */
    private void processAllChunks() {
        System.out.println("[Worldmap] Starting chunk processing...");

        try {
            // Get the default world from Universe
            Universe universe = Universe.get();
            if (universe == null) {
                System.err.println("[Worldmap] ERROR: Universe is not available");
                return;
            }

            World world = universe.getDefaultWorld();
            if (world == null) {
                System.err.println("[Worldmap] ERROR: Default world is not available");
                return;
            }

            // Get worldId from config for logging
            String worldId = getWorldId();
            if (worldId == null || worldId.isEmpty()) {
                System.err.println(
                        "[Worldmap] WARNING: worldId not configured in config.json, chunk processing may fail");
            } else {
                System.out.println("[Worldmap] Processing chunks from world: " + worldId);
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
                    System.err.println("[Worldmap] ERROR: Interrupted while waiting for chunk loader");
                    return;
                }
            }

            LongSet chunkIndexes;
            if (loader != null) {
                // Get all chunk indexes from disk via the loader
                try {
                    chunkIndexes = loader.getIndexes();
                    System.out.println("[Worldmap] Loaded chunk indexes from disk storage");
                } catch (IOException e) {
                    System.err.println("[Worldmap] ERROR: Error getting chunk indexes from loader: " + e.getMessage());
                    e.printStackTrace();
                    // Fallback to currently loaded chunks
                    chunkIndexes = chunkStore.getChunkIndexes();
                    System.out.println("[Worldmap] Falling back to currently loaded chunks: " + chunkIndexes.size());
                }
            } else {
                // Fallback to currently loaded chunks if loader is not available
                System.out.println("[Worldmap] Chunk loader not available, using currently loaded chunks");
                chunkIndexes = chunkStore.getChunkIndexes();
                if (chunkIndexes.isEmpty()) {
                    System.err.println(
                            "[Worldmap] ERROR: No chunks are currently loaded. The loader may not be initialized yet.");
                    return;
                }
            }

            int totalChunks = chunkIndexes.size();
            System.out.println("[Worldmap] Found " + totalChunks + " chunks to check");

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
                                if (config.isDebugMode()) {
                                    System.out.println("[Worldmap] [DEBUG] Chunk (" + chunkX + "," + chunkZ
                                            + ") is null, skipping");
                                }
                            }
                        })
                        .exceptionally(throwable -> {
                            System.err.println("[Worldmap] ERROR: Error loading chunk (" + chunkX + "," + chunkZ + "): "
                                    + throwable.getMessage());
                            throwable.printStackTrace();
                            return null;
                        });

                queued++;

                // Log progress every 100 chunks
                if ((queued + skipped) % 100 == 0) {
                    System.out.println("[Worldmap] Queued " + queued + " / " + totalChunks + " chunks for processing ("
                            + skipped + " already processed)");
                }
            }

            System.out.println("[Worldmap] Queued " + queued + " unprocessed chunks for processing ("
                    + skipped + " chunks were already processed)");
        } catch (Exception e) {
            System.err.println("[Worldmap] ERROR: Error getting world/chunks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Gets the plugin's data folder.
     * 
     * @return The data folder file
     */
    private File getDataFolder() {
        // Try to get from system property first (for testing)
        String dataFolderPath = System.getProperty("worldmap.data.folder");
        if (dataFolderPath != null) {
            return new File(dataFolderPath);
        }

        // Default: use current directory + /plugins/Worldmap/
        // This will be replaced with actual Plugin.getDataFolder() call
        File defaultFolder = new File("plugins/Worldmap");
        if (!defaultFolder.exists()) {
            defaultFolder.mkdirs();
        }
        return defaultFolder;
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
        return config;
    }

    /**
     * Gets the world identifier from configuration, with fallback to world name if
     * not configured.
     * 
     * @return The world identifier, or null if not available
     */
    public String getWorldId() {
        String worldId = config.getWorldId();
        if (worldId != null && !worldId.isEmpty()) {
            return worldId;
        }

        // Fallback to world name if worldId is not configured
        try {
            Universe universe = Universe.get();
            if (universe != null) {
                World world = universe.getDefaultWorld();
                if (world != null) {
                    String worldName = world.getName();
                    if (worldName != null && !worldName.isEmpty()) {
                        System.err.println(
                                "[Worldmap] WARNING: worldId not configured, falling back to world name: " + worldName);
                        return worldName;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Worldmap] ERROR: Error getting world name as fallback: " + e.getMessage());
        }

        return null;
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
    public AssetMapService getAssetMapService() {
        return assetMapService;
    }
}
