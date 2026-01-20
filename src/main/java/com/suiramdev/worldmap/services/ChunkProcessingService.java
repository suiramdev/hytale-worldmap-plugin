package com.suiramdev.worldmap.services;

import com.hypixel.hytale.server.core.universe.world.World;
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
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param chunk  The chunk object
     * @param world  The world object (for accessing neighboring chunks)
     */
    public CompletableFuture<Boolean> processChunk(int chunkX, int chunkZ, Object chunk, World world) {
        // Check if already processed
        if (storage.isChunkProcessed(chunkX, chunkZ)) {
            if (debugMode) {
                System.out.println("[Worldmap] Chunk (" + chunkX + "," + chunkZ + ") already processed, skipping");
            }
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Extract chunk data with halo padding
                ChunkPayload payload = extractChunkPayload(chunk, chunkX, chunkZ, world);

                // Send to API
                return httpClient.sendChunkData(payload)
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
     * Extract chunk payload with halo padding from a chunk object
     * 
     * @param chunk  The WorldChunk object from Hytale
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param world  The world object for accessing neighboring chunks
     * @return ChunkPayload object containing compact, binary-friendly data
     */
    private ChunkPayload extractChunkPayload(Object chunk, int chunkX, int chunkZ, World world) {
        ChunkPayload payload = new ChunkPayload(chunkX, chunkZ, System.currentTimeMillis());

        try {
            // Cast to WorldChunk
            WorldChunk worldChunk = (WorldChunk) chunk;

            // Extract main chunk block data (32x320x32 - Hytale chunks are 32x32 blocks, 320 blocks tall)
            int[][][] mainBlocks = new int[ChunkPayload.CHUNK_SIZE_X][ChunkPayload.CHUNK_SIZE_Y][ChunkPayload.CHUNK_SIZE_Z];
            
            // Find actual Y bounds (minY and maxY with blocks)
            int minY = ChunkPayload.CHUNK_SIZE_Y - 1;
            int maxY = 0;
            
            // Extract blocks from main chunk
            for (int x = 0; x < ChunkPayload.CHUNK_SIZE_X; x++) {
                for (int z = 0; z < ChunkPayload.CHUNK_SIZE_Z; z++) {
                    for (int y = 0; y < ChunkPayload.CHUNK_SIZE_Y; y++) {
                        int blockId = worldChunk.getBlock(x, y, z);
                        mainBlocks[x][y][z] = blockId;
                        
                        // Track Y bounds (only count non-air blocks, assuming 0 is air)
                        if (blockId != 0) {
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                    }
                }
            }
            
            // Clamp bounds
            if (minY > maxY) {
                minY = 0;
                maxY = ChunkPayload.CHUNK_SIZE_Y - 1;
            }
            
            // Build halo with neighboring chunks (34x320x34 including 1-block padding on X/Z)
            int haloSizeX = ChunkPayload.CHUNK_SIZE_X + 2 * ChunkPayload.HALO_SIZE;
            int haloSizeZ = ChunkPayload.CHUNK_SIZE_Z + 2 * ChunkPayload.HALO_SIZE;
            int[][][] haloBlocks = new int[haloSizeX][ChunkPayload.CHUNK_SIZE_Y][haloSizeZ];
            
            // Copy main chunk into center of halo
            for (int x = 0; x < ChunkPayload.CHUNK_SIZE_X; x++) {
                for (int y = 0; y < ChunkPayload.CHUNK_SIZE_Y; y++) {
                    for (int z = 0; z < ChunkPayload.CHUNK_SIZE_Z; z++) {
                        haloBlocks[x + ChunkPayload.HALO_SIZE][y][z + ChunkPayload.HALO_SIZE] = mainBlocks[x][y][z];
                    }
                }
            }
            
            // Fill halo padding from neighboring chunks
            fillHaloPadding(haloBlocks, chunkX, chunkZ, world, minY, maxY);
            
            // Check for optional features
            BlockChunk blockChunk = worldChunk.getBlockChunk();
            if (blockChunk != null) {
                EnvironmentChunk envChunk = blockChunk.getEnvironmentChunk();
                if (envChunk != null) {
                    payload.featureFlags |= ChunkPayload.FLAG_ENVIRONMENT;
                }
            }
            
            // Build payload from blocks
            payload.buildFromBlocks(mainBlocks, haloBlocks, minY, maxY);
            
        } catch (Exception e) {
            System.err.println(
                    "[Worldmap] Error extracting chunk payload for (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
            // Return minimal payload on error
            int[][][] emptyMain = new int[ChunkPayload.CHUNK_SIZE_X][ChunkPayload.CHUNK_SIZE_Y][ChunkPayload.CHUNK_SIZE_Z];
            int haloSizeX = ChunkPayload.CHUNK_SIZE_X + 2 * ChunkPayload.HALO_SIZE;
            int haloSizeZ = ChunkPayload.CHUNK_SIZE_Z + 2 * ChunkPayload.HALO_SIZE;
            int[][][] emptyHalo = new int[haloSizeX][ChunkPayload.CHUNK_SIZE_Y][haloSizeZ];
            payload.buildFromBlocks(emptyMain, emptyHalo, 0, ChunkPayload.CHUNK_SIZE_Y - 1);
        }

        return payload;
    }
    
    /**
     * Fill halo padding from neighboring chunks using World.getBlock() with world coordinates.
     * 
     * This method uses World.getBlock() which automatically handles cross-chunk access,
     * simplifying coordinate conversion and neighbor chunk fetching.
     * 
     * Halo layout (34x34):
     * - Main chunk is at indices [1..32][y][1..32] (already filled)
     * - Negative X edge (x=0): world coordinates from (chunkX-1)*32 to (chunkX-1)*32+31
     * - Positive X edge (x=33): world coordinates from (chunkX+1)*32 to (chunkX+1)*32+31
     * - Negative Z edge (z=0): world coordinates from (chunkZ-1)*32 to (chunkZ-1)*32+31
     * - Positive Z edge (z=33): world coordinates from (chunkZ+1)*32 to (chunkZ+1)*32+31
     * 
     * @param haloBlocks The halo block array to fill (34x320x34)
     * @param chunkX Current chunk X coordinate
     * @param chunkZ Current chunk Z coordinate
     * @param world World object for accessing blocks at world coordinates
     * @param minY Minimum Y coordinate with blocks
     * @param maxY Maximum Y coordinate with blocks
     */
    private void fillHaloPadding(int[][][] haloBlocks, int chunkX, int chunkZ, World world, int minY, int maxY) {
        // Calculate world coordinate base for this chunk
        int worldBaseX = chunkX << 5; // chunkX * 32
        int worldBaseZ = chunkZ << 5; // chunkZ * 32
        
        // Fill entire halo using world coordinates
        // World.getBlock() automatically handles cross-chunk access
        int haloSizeX = ChunkPayload.CHUNK_SIZE_X + 2 * ChunkPayload.HALO_SIZE;
        int haloSizeZ = ChunkPayload.CHUNK_SIZE_Z + 2 * ChunkPayload.HALO_SIZE;
        
        for (int haloX = 0; haloX < haloSizeX; haloX++) {
            for (int haloZ = 0; haloZ < haloSizeZ; haloZ++) {
                // Skip main chunk area (already filled)
                if (haloX >= ChunkPayload.HALO_SIZE && 
                    haloX < ChunkPayload.HALO_SIZE + ChunkPayload.CHUNK_SIZE_X &&
                    haloZ >= ChunkPayload.HALO_SIZE && 
                    haloZ < ChunkPayload.HALO_SIZE + ChunkPayload.CHUNK_SIZE_Z) {
                    continue; // Main chunk already filled
                }
                
                // Convert halo coordinates to world coordinates
                // Halo x=0 corresponds to world x = (chunkX-1)*32 + 31 (last block of neighbor)
                // Halo x=1 corresponds to world x = chunkX*32 (first block of this chunk)
                // General formula: worldX = (chunkX << 5) + (haloX - HALO_SIZE)
                int worldX = worldBaseX + (haloX - ChunkPayload.HALO_SIZE);
                int worldZ = worldBaseZ + (haloZ - ChunkPayload.HALO_SIZE);
                
                // Fill all Y levels for this halo position
                for (int y = minY; y <= maxY; y++) {
                    try {
                        // World.getBlock() automatically handles cross-chunk access
                        int blockId = world.getBlock(worldX, y, worldZ);
                        haloBlocks[haloX][y][haloZ] = blockId;
                    } catch (Exception e) {
                        // On error, fill with air (0)
                        if (debugMode && y == minY) { // Log once per position
                            System.err.println("[Worldmap] Error getting block at world (" + worldX + "," + y + "," + worldZ + "): " + e.getMessage());
                        }
                        haloBlocks[haloX][y][haloZ] = 0;
                    }
                }
            }
        }
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
}
