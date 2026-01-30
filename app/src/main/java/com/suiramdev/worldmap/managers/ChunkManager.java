package com.suiramdev.worldmap.managers;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.suiramdev.worldmap.models.ChunkPayload;
import com.suiramdev.worldmap.models.ChunkSendResult;
import com.suiramdev.worldmap.services.AssetService;
import com.suiramdev.worldmap.services.ChunkService;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manager for processing chunks and managing concurrent requests.
 * 
 * <p>
 * This manager handles chunk extraction, payload creation, and coordination
 * with the chunk service for sending data to the API.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class ChunkManager {

    private final ChunkService chunkService;
    private final AssetService assetService;
    private final AssetManager assetManager;
    private final boolean debugMode;
    private final ExecutorService executorService;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    // Processed chunks from API (set on initialization)
    private Set<String> processedChunksFromApi = new HashSet<>();

    /**
     * Creates a new ChunkManager instance.
     * 
     * @param chunkService The chunk service for API communication
     * @param assetService The asset service for sending asset maps and packs
     * @param assetManager The asset manager for gathering asset maps
     * @param debugMode    Whether debug logging is enabled
     */
    public ChunkManager(ChunkService chunkService, AssetService assetService,
            AssetManager assetManager, boolean debugMode) {
        this.chunkService = chunkService;
        this.assetService = assetService;
        this.assetManager = assetManager;
        this.debugMode = debugMode;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * Processes a single chunk asynchronously.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param chunk  The chunk object
     * @param world  The world object (for accessing neighboring chunks)
     * @return CompletableFuture that completes with true on success, false on
     *         failure
     */
    public CompletableFuture<Boolean> processChunk(int chunkX, int chunkZ, Object chunk, World world) {
        // Note: Chunk processing status is now tracked via API, not local storage
        // The check for already-processed chunks is done in Main.java using the API
        // list

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Extract chunk data with halo padding
                ChunkPayload payload = extractChunkPayload(chunk, chunkX, chunkZ, world);

                // Send to API (world derived from API key)
                ChunkSendResult result = chunkService.sendChunkData(payload).join();

                // Handle the result
                if (result.isSuccess()) {
                    // Don't mark as processed in local storage - API tracks this
                    int count = processedCount.incrementAndGet();

                    // Log progress every 100 chunks
                    if (count % 100 == 0) {
                        System.out.println("[Worldmap] Processed " + count + " chunks (failed: "
                                + failedCount.get() + ")");
                    }

                    return true;
                } else if (result.isAssetMapNeeded()) {
                    // Asset-map is needed - send it and retry
                    System.out.println("[Worldmap] Asset-map required for chunk (" + chunkX + "," + chunkZ
                            + "). Sending asset-map and retrying...");

                    if (sendAssetMapAndRetry(payload)) {
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
                } else {
                    // Regular failure
                    failedCount.incrementAndGet();
                    return false;
                }
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
     * Sends the asset-map and retries sending the chunk.
     * 
     * @param payload The chunk payload to retry after sending asset-map
     * @return true if the retry was successful, false otherwise
     */
    private boolean sendAssetMapAndRetry(ChunkPayload payload) {
        try {
            // Gather asset map
            var assetMap = assetManager.gatherAssetMap();
            if (assetMap == null || assetMap.isEmpty()) {
                System.err.println("[Worldmap] No asset map data gathered");
                return false;
            }

            System.out.println("[Worldmap] Sending asset-map (" + assetMap.size() + " entries)");

            // Send asset-map (world derived from API key)
            boolean assetMapSent = assetService.sendAssetMap(assetMap).join();
            if (!assetMapSent) {
                System.err.println("[Worldmap] Failed to send asset-map");
                return false;
            }

            System.out.println("[Worldmap] Asset-map sent successfully, retrying chunk (" + payload.chunkX + ","
                    + payload.chunkZ + ")");

            // Retry sending the chunk
            ChunkSendResult retryResult = chunkService.sendChunkData(payload).join();
            return retryResult.isSuccess();
        } catch (Exception e) {
            System.err.println("[Worldmap] Error sending asset-map and retrying chunk: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Extracts chunk payload with halo padding from a chunk object.
     * 
     * @param chunk  The WorldChunk object
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

            // Extract main chunk block data (32x320x32 - chunks are 32x32 blocks, 320
            // blocks tall)
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
                            if (y < minY)
                                minY = y;
                            if (y > maxY)
                                maxY = y;
                        }
                    }
                }
            }

            // Clamp bounds
            if (minY > maxY) {
                minY = 0;
                maxY = ChunkPayload.CHUNK_SIZE_Y - 1;
            }

            // Build halo with neighboring chunks (34x320x34 including 1-block padding on
            // X/Z)
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

            // Extract tint map (32x32 array, one tint value per X/Z position)
            int[][] tintMap = new int[ChunkPayload.CHUNK_SIZE_X][ChunkPayload.CHUNK_SIZE_Z];
            for (int x = 0; x < ChunkPayload.CHUNK_SIZE_X; x++) {
                for (int z = 0; z < ChunkPayload.CHUNK_SIZE_Z; z++) {
                    try {
                        tintMap[x][z] = worldChunk.getTint(x, z);
                    } catch (Exception e) {
                        // On error, use default tint (0)
                        if (debugMode) {
                            System.err.println(
                                    "[Worldmap] Error getting tint at (" + x + "," + z + "): " + e.getMessage());
                        }
                        tintMap[x][z] = 0;
                    }
                }
            }

            // Build payload from blocks and tint map
            payload.buildFromBlocks(mainBlocks, haloBlocks, minY, maxY, tintMap);

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
            int[][] emptyTintMap = new int[ChunkPayload.CHUNK_SIZE_X][ChunkPayload.CHUNK_SIZE_Z];
            payload.buildFromBlocks(emptyMain, emptyHalo, 0, ChunkPayload.CHUNK_SIZE_Y - 1, emptyTintMap);
        }

        return payload;
    }

    /**
     * Fills halo padding from neighboring chunks using World.getBlock() with world
     * coordinates.
     * 
     * <p>
     * This method uses World.getBlock() which automatically handles cross-chunk
     * access,
     * simplifying coordinate conversion and neighbor chunk fetching.
     * </p>
     * 
     * <p>
     * Halo layout (34x34):
     * <ul>
     * <li>Main chunk is at indices [1..32][y][1..32] (already filled)</li>
     * <li>Negative X edge (x=0): world coordinates from (chunkX-1)*32 to
     * (chunkX-1)*32+31</li>
     * <li>Positive X edge (x=33): world coordinates from (chunkX+1)*32 to
     * (chunkX+1)*32+31</li>
     * <li>Negative Z edge (z=0): world coordinates from (chunkZ-1)*32 to
     * (chunkZ-1)*32+31</li>
     * <li>Positive Z edge (z=33): world coordinates from (chunkZ+1)*32 to
     * (chunkZ+1)*32+31</li>
     * </ul>
     * </p>
     * 
     * @param haloBlocks The halo block array to fill (34x320x34)
     * @param chunkX     Current chunk X coordinate
     * @param chunkZ     Current chunk Z coordinate
     * @param world      World object for accessing blocks at world coordinates
     * @param minY       Minimum Y coordinate with blocks
     * @param maxY       Maximum Y coordinate with blocks
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
                            System.err.println("[Worldmap] Error getting block at world (" + worldX + "," + y + ","
                                    + worldZ + "): " + e.getMessage());
                        }
                        haloBlocks[haloX][y][haloZ] = 0;
                    }
                }
            }
        }
    }

    /**
     * Gets the number of successfully processed chunks.
     * 
     * @return The processed count
     */
    public int getProcessedCount() {
        return processedCount.get();
    }

    /**
     * Schedules a chunk to be re-sent to the API (e.g. after a block place/break).
     * Loads the chunk asynchronously and sends it without checking the processed
     * set.
     *
     * @param world  The world
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     */
    public void scheduleChunkResend(World world, int chunkX, int chunkZ) {
        long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
        world.getNonTickingChunkAsync(chunkIndex)
                .thenAcceptAsync(chunk -> {
                    if (chunk != null) {
                        processChunk(chunkX, chunkZ, chunk, world);
                    }
                }, executorService);
    }

    /**
     * Processes a chunk asynchronously only if it has not already been sent to the
     * API.
     * Used when a new chunk is loaded (e.g. ChunkPreLoadProcessEvent).
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param chunk  The chunk (e.g. from the event)
     * @param world  The world
     */
    public void processChunkAsyncIfNeeded(int chunkX, int chunkZ, WorldChunk chunk, World world) {
        executorService.execute(() -> {
            if (!isChunkProcessed(chunkX, chunkZ)) {
                processChunk(chunkX, chunkZ, chunk, world);
            }
        });
    }

    /**
     * Gets the number of failed chunk processing attempts.
     * 
     * @return The failed count
     */
    public int getFailedCount() {
        return failedCount.get();
    }

    /**
     * Fetches the list of processed chunks from the API.
     * World is derived from the API key.
     *
     * <p>
     * This should be called before processing chunks to avoid sending chunks
     * that have already been processed.
     * </p>
     *
     * @return CompletableFuture that completes when the fetch is done
     */
    public CompletableFuture<Void> fetchProcessedChunksList() {
        return chunkService.fetchProcessedChunksList()
                .thenAccept(chunks -> {
                    synchronized (this) {
                        processedChunksFromApi = chunks;
                    }
                    System.out.println("[Worldmap] Loaded " + chunks.size()
                            + " processed chunks from API. Missing chunks will be sent.");
                })
                .exceptionally(throwable -> {
                    System.err.println("[Worldmap] Failed to fetch processed chunks list: " + throwable.getMessage());
                    if (debugMode) {
                        throwable.printStackTrace();
                    }
                    System.out.println("[Worldmap] Will process all chunks (API fetch failed)");
                    return null;
                });
    }

    /**
     * Checks if a chunk has already been processed.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk has been processed, false otherwise
     */
    public boolean isChunkProcessed(int chunkX, int chunkZ) {
        String chunkKey = chunkX + "," + chunkZ;
        synchronized (this) {
            return processedChunksFromApi.contains(chunkKey);
        }
    }

    /**
     * Shuts down the executor service gracefully.
     * 
     * <p>
     * Waits for ongoing tasks to complete, with a timeout of 60 seconds.
     * </p>
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
