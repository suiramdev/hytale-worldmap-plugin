package com.suiramdev.worldmap.services;

import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.suiramdev.worldmap.storage.StorageService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes chunks and manages concurrent requests
 */
public class ChunkProcessingService {
    private final HttpClientService httpClient;
    private final StorageService storage;
    private final boolean debugMode;
    private final ExecutorService executorService;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    public ChunkProcessingService(HttpClientService httpClient, StorageService storage, boolean debugMode) {
        this.httpClient = httpClient;
        this.storage = storage;
        this.debugMode = debugMode;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * Process a single chunk
     * 
     * @param worldId World identifier
     * @param chunkX  Chunk X coordinate
     * @param chunkZ  Chunk Z coordinate
     * @param chunk   The chunk object (placeholder - will be replaced with actual
     *                Hytale Chunk type)
     */
    public CompletableFuture<Boolean> processChunk(String worldId, int chunkX, int chunkZ, Object chunk) {
        // Check if already processed
        if (storage.isChunkProcessed(chunkX, chunkZ)) {
            if (debugMode) {
                System.out.println("[Worldmap] Chunk (" + chunkX + "," + chunkZ + ") already processed, skipping");
            }
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Extract chunk data
                ChunkData chunkData = extractChunkData(chunk, worldId, chunkX, chunkZ);

                // Send to API
                return httpClient.sendChunkData(chunkData)
                        .thenApply(success -> {
                            if (success) {
                                storage.markChunkProcessed(chunkX, chunkZ);
                                int count = processedCount.incrementAndGet();

                                // Log progress every 100 chunks
                                if (count % 100 == 0) {
                                    System.out.println("[Worldmap] Processed " + count + " chunks (failed: "
                                            + failedCount.get() + ")");
                                }

                                return true;
                            } else {
                                failedCount.incrementAndGet();
                                return false;
                            }
                        })
                        .join();
            } catch (Exception e) {
                System.err.println(
                        "[Worldmap] Error processing chunk (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                if (debugMode) {
                    e.printStackTrace();
                }
                failedCount.incrementAndGet();
                return false;
            }
        }, executorService);
    }

    /**
     * Extract chunk data from a chunk object
     * 
     * @param chunk  The WorldChunk object from Hytale
     * @param worldId World identifier
     * @param chunkX  Chunk X coordinate
     * @param chunkZ  Chunk Z coordinate
     * @return ChunkData object containing extracted data
     */
    private ChunkData extractChunkData(Object chunk, String worldId, int chunkX, int chunkZ) {
        ChunkData data = new ChunkData();
        data.worldId = worldId;
        data.chunkX = chunkX;
        data.chunkZ = chunkZ;
        data.timestamp = System.currentTimeMillis();

        try {
            // Cast to WorldChunk
            WorldChunk worldChunk = (WorldChunk) chunk;

            // Extract block data (32x320x32 - Hytale chunks are 32x32 blocks, 320 blocks tall)
            int[][][] blocks = new int[32][320][32];
            short[][] heightMap = new short[32][32];
            int[][] tintMap = new int[32][32];

            // Extract block data
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    // Get height map
                    heightMap[x][z] = worldChunk.getHeight(x, z);
                    // Get tint map
                    tintMap[x][z] = worldChunk.getTint(x, z);
                    
                    // Extract blocks for this column (only up to height to save space)
                    int maxY = Math.min(320, heightMap[x][z] + 10); // Include a bit above height
                    for (int y = 0; y < maxY; y++) {
                        blocks[x][y][z] = worldChunk.getBlock(x, y, z);
                    }
                }
            }

            data.blocks = blocks;
            data.heightMap = heightMap;
            data.tintMap = tintMap;

            // Extract environment/biome data if available
            BlockChunk blockChunk = worldChunk.getBlockChunk();
            if (blockChunk != null) {
                EnvironmentChunk envChunk = blockChunk.getEnvironmentChunk();
                if (envChunk != null) {
                    // Environment data is available but may need special handling
                    // For now, we'll include a flag that it exists
                    data.hasEnvironmentData = true;
                }
            }

        } catch (Exception e) {
            System.err.println("[Worldmap] Error extracting chunk data for (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
            // Return minimal data on error
            data.blocks = new int[32][320][32];
            data.heightMap = new short[32][32];
            data.tintMap = new int[32][32];
        }

        return data;
    }

    /**
     * Get statistics
     */
    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Chunk data structure for API
     */
    public static class ChunkData {
        public String worldId;
        public int chunkX;
        public int chunkZ;
        public long timestamp;
        public int[][][] blocks; // 32x320x32 array of block IDs
        public short[][] heightMap; // 32x32 array of height values
        public int[][] tintMap; // 32x32 array of tint values
        public boolean hasEnvironmentData = false;
    }
}
