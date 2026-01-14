package com.suiramdev.worldmap;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.suiramdev.worldmap.config.PluginConfig;
import com.suiramdev.worldmap.services.ChunkProcessingService;
import com.suiramdev.worldmap.services.HttpClientService;
import com.suiramdev.worldmap.storage.StorageService;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Main plugin class.
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class Main {

    private static Main instance;

    private PluginConfig config;
    private StorageService storage;
    private HttpClientService httpClient;
    private ChunkProcessingService chunkProcessor;
    private File dataFolder;

    /**
     * Constructor - Called when plugin is loaded.
     */
    public Main() {
        instance = this;
        System.out.println("[Worldmap] Plugin loaded!");
    }

    /**
     * Called when plugin is enabled.
     */
    public void onEnable() {
        System.out.println("[Worldmap] Plugin enabled!");

        try {
            // Get data folder
            dataFolder = getDataFolder();

            // Initialize configuration
            config = new PluginConfig(dataFolder);
            System.out.println("[Worldmap] Configuration loaded - API URL: " + config.getApiUrl());

            // Initialize storage service
            storage = new StorageService(dataFolder);

            // Initialize HTTP client service
            httpClient = new HttpClientService(
                    config.getApiUrl(),
                    config.getRequestTimeout(),
                    config.getMaxRetries(),
                    config.isDebugMode());

            // Initialize chunk processing service
            chunkProcessor = new ChunkProcessingService(httpClient, storage, config.isDebugMode());

            // Check if this is the first load
            if (storage.isFirstLoad()) {
                System.out.println("[Worldmap] First load detected - processing all chunks...");

                // Process chunks asynchronously to avoid blocking server startup
                CompletableFuture.runAsync(() -> {
                    try {
                        processAllChunks();
                        // Mark as loaded after queuing all chunks (individual chunks are tracked
                        // separately)
                        storage.markAsLoaded();
                        System.out.println("[Worldmap] Chunk processing queued!");
                        System.out.println("[Worldmap] Chunks will be processed in the background");
                    } catch (Exception e) {
                        System.err.println("[Worldmap] Error during chunk processing: " + e.getMessage());
                        if (config != null && config.isDebugMode()) {
                            e.printStackTrace();
                        }
                    }
                });
            } else {
                int processedCount = storage.getProcessedChunkCount();
                System.out.println("[Worldmap] Plugin loaded - " + processedCount + " chunks already processed");
            }
        } catch (Exception e) {
            System.err.println("[Worldmap] Failed to initialize plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called when plugin is disabled.
     */
    public void onDisable() {
        System.out.println("[Worldmap] Plugin disabled!");

        // Shutdown chunk processor (waits for ongoing tasks)
        if (chunkProcessor != null) {
            System.out.println("[Worldmap] Shutting down chunk processor...");
            chunkProcessor.shutdown();
        }

        // Save storage
        if (storage != null) {
            storage.saveStorage();
        }

        System.out.println("[Worldmap] Plugin disabled successfully!");
    }

    /**
     * Process all chunks from the world
     */
    private void processAllChunks() {
        System.out.println("[Worldmap] Starting chunk processing...");

        try {
            // Get the default world from Universe
            Universe universe = Universe.get();
            if (universe == null) {
                System.err.println("[Worldmap] Universe is not available");
                return;
            }

            World world = universe.getDefaultWorld();
            if (world == null) {
                System.err.println("[Worldmap] Default world is not available");
                return;
            }

            System.out.println("[Worldmap] Processing chunks from world: " + world.getName());

            // Get all chunk indexes from the chunk loader
            ChunkStore chunkStore = world.getChunkStore();
            IChunkLoader loader = chunkStore.getLoader();

            if (loader == null) {
                System.err.println("[Worldmap] Chunk loader is not available");
                return;
            }

            // Get all chunk indexes
            LongSet chunkIndexes;
            try {
                chunkIndexes = loader.getIndexes();
            } catch (IOException e) {
                System.err.println("[Worldmap] Error getting chunk indexes: " + e.getMessage());
                if (config != null && config.isDebugMode()) {
                    e.printStackTrace();
                }
                return;
            }

            int totalChunks = chunkIndexes.size();
            System.out.println("[Worldmap] Found " + totalChunks + " chunks to process");

            // Process each chunk
            int processed = 0;
            for (long chunkIndex : chunkIndexes) {
                int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
                int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);

                // Get chunk asynchronously (non-ticking to avoid affecting gameplay)
                world.getNonTickingChunkAsync(chunkIndex)
                        .thenAccept(chunk -> {
                            if (chunk != null) {
                                chunkProcessor.processChunk(chunkX, chunkZ, chunk);
                            } else {
                                if (config != null && config.isDebugMode()) {
                                    System.out.println(
                                            "[Worldmap] Chunk (" + chunkX + "," + chunkZ + ") is null, skipping");
                                }
                            }
                        })
                        .exceptionally(throwable -> {
                            System.err.println("[Worldmap] Error loading chunk (" + chunkX + "," + chunkZ + "): "
                                    + throwable.getMessage());
                            if (config != null && config.isDebugMode()) {
                                throwable.printStackTrace();
                            }
                            return null;
                        });

                processed++;

                // Log progress every 100 chunks
                if (processed % 100 == 0) {
                    System.out
                            .println("[Worldmap] Queued " + processed + " / " + totalChunks + " chunks for processing");
                }
            }

            System.out.println("[Worldmap] Queued all " + totalChunks + " chunks for processing");
        } catch (Exception e) {
            System.err.println("[Worldmap] Error getting world/chunks: " + e.getMessage());
            if (config != null && config.isDebugMode()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Get the plugin's data folder
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
     * Get plugin instance.
     */
    public static Main getInstance() {
        return instance;
    }

    /**
     * Get configuration
     */
    public PluginConfig getConfig() {
        return config;
    }

    /**
     * Get storage service
     */
    public StorageService getStorage() {
        return storage;
    }

    /**
     * Get chunk processor
     */
    public ChunkProcessingService getChunkProcessor() {
        return chunkProcessor;
    }
}
