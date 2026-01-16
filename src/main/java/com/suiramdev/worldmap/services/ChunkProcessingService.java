package com.suiramdev.worldmap.services;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockTypeTextures;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.suiramdev.worldmap.storage.StorageService;
import java.util.HashMap;
import java.util.Map;
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
     */
    public CompletableFuture<Boolean> processChunk(int chunkX, int chunkZ, Object chunk) {
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
                ChunkData chunkData = extractChunkData(chunk, chunkX, chunkZ);

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
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return ChunkData object containing extracted data
     */
    private ChunkData extractChunkData(Object chunk, int chunkX, int chunkZ) {
        ChunkData data = new ChunkData();
        data.chunkX = chunkX;
        data.chunkZ = chunkZ;
        data.timestamp = System.currentTimeMillis();

        try {
            // Cast to WorldChunk
            WorldChunk worldChunk = (WorldChunk) chunk;

            // Extract block data (32x320x32 - Hytale chunks are 32x32 blocks, 320 blocks
            // tall)
            int[][][] blocks = new int[32][320][32];
            short[][] heightMap = new short[32][32];
            int[][] tintMap = new int[32][32];

            // Map to store block textures (blockId -> texture info) to avoid duplication
            Map<Integer, BlockTextureInfo> blockTextures = new HashMap<>();

            // Get BlockType asset map for texture lookup
            BlockTypeAssetMap<String, BlockType> blockTypeAssetMap = BlockType.getAssetMap();

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
                        int blockId = worldChunk.getBlock(x, y, z);
                        blocks[x][y][z] = blockId;

                        // Extract texture info for this block ID if not already stored
                        if (!blockTextures.containsKey(blockId)) {
                            BlockTextureInfo textureInfo = extractBlockTextureInfo(blockId, blockTypeAssetMap);
                            if (textureInfo != null) {
                                blockTextures.put(blockId, textureInfo);
                            }
                        }
                    }
                }
            }

            data.blocks = blocks;
            data.heightMap = heightMap;
            data.tintMap = tintMap;
            data.blockTextures = blockTextures;

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
            System.err.println(
                    "[Worldmap] Error extracting chunk data for (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
            // Return minimal data on error
            data.blocks = new int[32][320][32];
            data.heightMap = new short[32][32];
            data.tintMap = new int[32][32];
            data.blockTextures = new HashMap<>();
        }

        return data;
    }

    /**
     * Extract texture information for a block ID
     * 
     * @param blockId           The block ID
     * @param blockTypeAssetMap The BlockType asset map
     * @return BlockTextureInfo containing texture paths, or null if block type not
     *         found
     */
    private BlockTextureInfo extractBlockTextureInfo(int blockId,
            BlockTypeAssetMap<String, BlockType> blockTypeAssetMap) {
        try {
            BlockType blockType = blockTypeAssetMap.getAsset(blockId);
            if (blockType == null) {
                return null;
            }

            BlockTypeTextures[] textures = blockType.getTextures();
            if (textures == null || textures.length == 0) {
                // Use default unknown texture
                return new BlockTextureInfo(
                        "BlockTextures/Unknown.png",
                        "BlockTextures/Unknown.png",
                        "BlockTextures/Unknown.png",
                        "BlockTextures/Unknown.png",
                        "BlockTextures/Unknown.png",
                        "BlockTextures/Unknown.png",
                        false);
            }

            // Use the first texture variant (most common case)
            BlockTypeTextures firstTexture = textures[0];

            // Check if this block should be tinted using BiomeTint properties from
            // BlockType
            // A block should be tinted if any of its biomeTint values are non-zero
            boolean shouldTint = blockType.getBiomeTintUp() != 0 ||
                    blockType.getBiomeTintDown() != 0 ||
                    blockType.getBiomeTintNorth() != 0 ||
                    blockType.getBiomeTintSouth() != 0 ||
                    blockType.getBiomeTintWest() != 0 ||
                    blockType.getBiomeTintEast() != 0;

            return new BlockTextureInfo(
                    firstTexture.getUp(),
                    firstTexture.getDown(),
                    firstTexture.getNorth(),
                    firstTexture.getSouth(),
                    firstTexture.getEast(),
                    firstTexture.getWest(),
                    shouldTint);
        } catch (Exception e) {
            if (debugMode) {
                System.err
                        .println("[Worldmap] Error extracting texture for block ID " + blockId + ": " + e.getMessage());
            }
            return null;
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

    /**
     * Chunk data structure for API
     */
    public static class ChunkData {
        public int chunkX;
        public int chunkZ;
        public long timestamp;
        public int[][][] blocks; // 32x320x32 array of block IDs
        public short[][] heightMap; // 32x32 array of height values
        public int[][] tintMap; // 32x32 array of tint values
        public Map<Integer, BlockTextureInfo> blockTextures; // Map of blockId -> texture paths
        public boolean hasEnvironmentData = false;
    }

    /**
     * Block texture information structure
     */
    public static class BlockTextureInfo {
        public String up;
        public String down;
        public String north;
        public String south;
        public String east;
        public String west;
        public boolean shouldTint; // Whether this block should receive biome tinting

        public BlockTextureInfo() {
            this.shouldTint = false;
        }

        public BlockTextureInfo(String up, String down, String north, String south, String east, String west,
                boolean shouldTint) {
            this.up = up;
            this.down = down;
            this.north = north;
            this.south = south;
            this.east = east;
            this.west = west;
            this.shouldTint = shouldTint;
        }
    }
}
