package com.suiramdev.worldmap;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.suiramdev.worldmap.config.PluginConfig;
import com.suiramdev.worldmap.services.ChunkProcessingService;
import com.suiramdev.worldmap.services.HttpClientService;
import com.suiramdev.worldmap.storage.StorageService;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Main plugin class.
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class Main extends JavaPlugin {

    private static Main instance;

    private PluginConfig config;
    private StorageService storage;
    private HttpClientService httpClient;
    private ChunkProcessingService chunkProcessor;
    private File dataFolder;

    /**
     * Constructor - Called when plugin is loaded.
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
                    config.getApiKey(),
                    config.getRequestTimeout(),
                    config.getMaxRetries(),
                    config.isDebugMode());

            // Initialize chunk processing service
            chunkProcessor = new ChunkProcessingService(httpClient, storage, config.isDebugMode());

            // Get processed chunk count
            int processedCount = storage.getProcessedChunkCount();
            System.out.println("[Worldmap] Plugin loaded - " + processedCount + " chunks already processed");

            // Process chunks asynchronously to avoid blocking server startup
            // Only unprocessed chunks will be sent to the API
            CompletableFuture.runAsync(() -> {
                try {
                    processAllChunks();
                    System.out.println("[Worldmap] Chunk processing queued!");
                    System.out.println("[Worldmap] Chunks will be processed in the background");
                } catch (Exception e) {
                    System.err.println("[Worldmap] Error during chunk processing: " + e.getMessage());
                    if (config != null && config.isDebugMode()) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (Exception e) {
            System.err.println("[Worldmap] Failed to initialize plugin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void shutdown() {
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
                    System.err.println("[Worldmap] Interrupted while waiting for chunk loader");
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
                    System.err.println("[Worldmap] Error getting chunk indexes from loader: " + e.getMessage());
                    if (config != null && config.isDebugMode()) {
                        e.printStackTrace();
                    }
                    // Fallback to currently loaded chunks
                    chunkIndexes = chunkStore.getChunkIndexes();
                    System.out.println("[Worldmap] Falling back to currently loaded chunks: " + chunkIndexes.size());
                }
            } else {
                // Fallback to currently loaded chunks if loader is not available
                System.out.println("[Worldmap] Chunk loader not available, using currently loaded chunks");
                chunkIndexes = chunkStore.getChunkIndexes();
                if (chunkIndexes.isEmpty()) {
                    System.err.println("[Worldmap] No chunks are currently loaded. The loader may not be initialized yet.");
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

                // Check if chunk has already been processed
                if (storage.isChunkProcessed(chunkX, chunkZ)) {
                    skipped++;
                    continue;
                }

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
