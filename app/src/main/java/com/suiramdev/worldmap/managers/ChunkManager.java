package com.suiramdev.worldmap.managers;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.suiramdev.worldmap.models.ChunkPayload;
import com.suiramdev.worldmap.models.ChunkSendResult;
import com.suiramdev.worldmap.services.AssetService;
import com.suiramdev.worldmap.services.ChunkService;
import com.suiramdev.worldmap.util.WorldmapLog;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Processing is running (sending chunks). */
    public static final String STATE_RUNNING = "running";
    /** Processing halted by user (Stop button). */
    public static final String STATE_HALTED_USER = "halted_user";
    /** Processing halted due to missing or invalid API key. */
    public static final String STATE_HALTED_AUTH = "halted_auth";

    private final ChunkService chunkService;
    private final AssetService assetService;
    private final AssetManager assetManager;
    private final boolean debugMode;
    private final ExecutorService executorService;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    /** When true, processing is allowed; when false, all send/work is skipped until started again. */
    private final AtomicBoolean processingEnabled = new AtomicBoolean(true);
    /** Current state for UI: STATE_RUNNING, STATE_HALTED_USER, or STATE_HALTED_AUTH. */
    private final AtomicReference<String> processingState = new AtomicReference<>(STATE_RUNNING);
    /** Last error message for UI (e.g. auth error). */
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>(null);

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
        return processChunk(chunkX, chunkZ, chunk, world, false);
    }

    /**
     * Processes a single chunk asynchronously.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param chunk  The chunk object
     * @param world  The world object (for accessing neighboring chunks)
     * @param force  If true, process even when processing is halted (e.g. for manual re-send)
     * @return CompletableFuture that completes with true on success, false on
     *         failure
     */
    public CompletableFuture<Boolean> processChunk(int chunkX, int chunkZ, Object chunk, World world, boolean force) {
        if (!force && !processingEnabled.get()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            if (!force && !processingEnabled.get()) {
                return false;
            }
            try {
                // Extract chunk data with halo padding
                ChunkPayload payload = extractChunkPayload(chunk, chunkX, chunkZ, world);

                // Send to API (world derived from API key)
                ChunkSendResult result = chunkService.sendChunkData(payload).join();

                if (result.isAuthFailure()) {
                    processingEnabled.set(false);
                    processingState.set(STATE_HALTED_AUTH);
                    lastErrorMessage.set("Invalid or missing API key. Use /worldmap key <key> then /worldmap start.");
                    WorldmapLog.severe("Chunk processing halted due to auth failure.");
                    return false;
                }

                // Handle the result
                if (result.isSuccess()) {
                    int count = processedCount.incrementAndGet();
                    if (count % 100 == 0) {
                        WorldmapLog.info("Processed %d chunks (failed: %d)", count, failedCount.get());
                    }
                    return true;
                } else if (result.isAssetMapNeeded()) {
                    WorldmapLog.info("Asset-map required for chunk (%d,%d). Sending asset-map and retrying...", chunkX, chunkZ);

                    if (sendAssetMapAndRetry(payload)) {
                        int count = processedCount.incrementAndGet();
                        if (count % 100 == 0) {
                            WorldmapLog.info("Processed %d chunks (failed: %d)", count, failedCount.get());
                        }
                        return true;
                    } else {
                        failedCount.incrementAndGet();
                        return false;
                    }
                } else {
                    failedCount.incrementAndGet();
                    return false;
                }
            } catch (Exception e) {
                WorldmapLog.severe("Error processing chunk (%d,%d): %s", chunkX, chunkZ, e.getMessage());
                if (debugMode) {
                    WorldmapLog.severe("Error processing chunk", e);
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
                WorldmapLog.severe("No asset map data gathered");
                return false;
            }

            WorldmapLog.info("Sending asset-map (%d entries)", assetMap.size());

            // Send asset-map (world derived from API key)
            boolean assetMapSent = assetService.sendAssetMap(assetMap).join();
            if (!assetMapSent) {
                WorldmapLog.severe("Failed to send asset-map");
                return false;
            }

            WorldmapLog.info("Asset-map sent successfully, retrying chunk (%d,%d)", payload.chunkX, payload.chunkZ);

            // Retry sending the chunk
            ChunkSendResult retryResult = chunkService.sendChunkData(payload).join();
            if (retryResult.isAuthFailure()) {
                processingEnabled.set(false);
                processingState.set(STATE_HALTED_AUTH);
                lastErrorMessage.set("Invalid or missing API key. Use /worldmap key <key> then /worldmap start.");
                return false;
            }
            return retryResult.isSuccess();
        } catch (Exception e) {
            WorldmapLog.severe("Error sending asset-map and retrying chunk: %s", e.getMessage());
            if (debugMode) {
                WorldmapLog.severe("Error sending asset-map and retrying", e);
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
                            WorldmapLog.fine("Error getting tint at (%d,%d): %s", x, z, e.getMessage());
                        }
                        tintMap[x][z] = 0;
                    }
                }
            }

            // Build payload from blocks and tint map
            payload.buildFromBlocks(mainBlocks, haloBlocks, minY, maxY, tintMap);

        } catch (Exception e) {
            WorldmapLog.severe("Error extracting chunk payload for (%d,%d): %s", chunkX, chunkZ, e.getMessage());
            if (debugMode) {
                WorldmapLog.severe("Error extracting chunk payload", e);
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
                            WorldmapLog.fine("Error getting block at world (%d,%d,%d): %s", worldX, y, worldZ, e.getMessage());
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
        if (!processingEnabled.get()) {
            return;
        }
        long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
        world.getNonTickingChunkAsync(chunkIndex)
                .thenAcceptAsync(chunk -> {
                    if (chunk != null && processingEnabled.get()) {
                        processChunk(chunkX, chunkZ, chunk, world);
                    }
                }, executorService);
    }

    /**
     * Force re-process, render, and send a single chunk to the API.
     * Loads the chunk asynchronously and sends it regardless of processed set or
     * processing enabled state. Use for manual re-send of a given chunk (e.g.
     * /worldmap reprocess chunkX chunkZ).
     *
     * @param world  The world
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     */
    public void forceReprocessChunk(World world, int chunkX, int chunkZ) {
        long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
        world.getNonTickingChunkAsync(chunkIndex)
                .thenAcceptAsync(chunk -> {
                    if (chunk != null) {
                        processChunk(chunkX, chunkZ, chunk, world, true);
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
        if (!processingEnabled.get()) {
            return;
        }
        executorService.execute(() -> {
            if (processingEnabled.get() && !isChunkProcessed(chunkX, chunkZ)) {
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
                    WorldmapLog.info("Loaded %d processed chunks from API. Missing chunks will be sent.", chunks.size());
                })
                .exceptionally(throwable -> {
                    WorldmapLog.severe("Failed to fetch processed chunks list: %s", throwable.getMessage());
                    if (debugMode) {
                        WorldmapLog.severe("Failed to fetch processed chunks list", throwable);
                    }
                    WorldmapLog.info("Will process all chunks (API fetch failed)");
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
     * Stops chunk processing (user-requested halt). No further chunks are sent until {@link #startProcessing()} is called.
     */
    public void stopProcessing() {
        processingEnabled.set(false);
        processingState.set(STATE_HALTED_USER);
        lastErrorMessage.set("Stopped by user.");
        WorldmapLog.info("Chunk processing stopped by user.");
    }

    /**
     * Enables chunk processing again. Does not automatically re-queue chunks; the caller (e.g. Main) should call processAllChunks() if a full run is desired.
     */
    public void startProcessing() {
        processingEnabled.set(true);
        processingState.set(STATE_RUNNING);
        lastErrorMessage.set(null);
        WorldmapLog.info("Chunk processing started.");
    }

    /**
     * Halts processing due to auth/config error (e.g. missing API key at startup). Call instead of stopProcessing() when the cause is auth, not user request.
     */
    public void haltDueToAuth(String message) {
        processingEnabled.set(false);
        processingState.set(STATE_HALTED_AUTH);
        lastErrorMessage.set(message != null ? message : "Invalid or missing API key.");
        WorldmapLog.info("Chunk processing halted: %s", message != null ? message : "invalid or missing API key.");
    }

    /**
     * Returns whether chunk processing is currently enabled (not halted).
     */
    public boolean isProcessingEnabled() {
        return processingEnabled.get();
    }

    /**
     * Current processing state for UI: {@link #STATE_RUNNING}, {@link #STATE_HALTED_USER}, or {@link #STATE_HALTED_AUTH}.
     */
    public String getProcessingState() {
        return processingState.get();
    }

    /**
     * Last error message for UI (e.g. auth error), or null if none.
     */
    public String getLastErrorMessage() {
        return lastErrorMessage.get();
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
