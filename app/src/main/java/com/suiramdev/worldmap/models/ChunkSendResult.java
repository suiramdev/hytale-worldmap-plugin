package com.suiramdev.worldmap.models;

/**
 * Result of a chunk send operation.
 * 
 * <p>
 * Indicates whether the chunk was sent successfully and if an asset-map is needed.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class ChunkSendResult {
    
    private final boolean success;
    private final boolean needsAssetMap;
    private final boolean authFailure;

    /**
     * Creates a new ChunkSendResult.
     *
     * @param success true if the chunk was sent successfully
     * @param needsAssetMap true if the API indicated that an asset-map is needed
     * @param authFailure true if the request failed due to missing or invalid API key (do not retry)
     */
    public ChunkSendResult(boolean success, boolean needsAssetMap, boolean authFailure) {
        this.success = success;
        this.needsAssetMap = needsAssetMap;
        this.authFailure = authFailure;
    }
    
    /**
     * Creates a successful result.
     * 
     * @return A successful result that doesn't need an asset-map
     */
    public static ChunkSendResult success() {
        return new ChunkSendResult(true, false, false);
    }

    /**
     * Creates a failure result.
     *
     * @return A failure result that doesn't need an asset-map
     */
    public static ChunkSendResult failure() {
        return new ChunkSendResult(false, false, false);
    }

    /**
     * Creates a result indicating that an asset-map is needed.
     *
     * @return A result indicating asset-map is needed (not successful yet)
     */
    public static ChunkSendResult needsAssetMap() {
        return new ChunkSendResult(false, true, false);
    }

    /**
     * Creates a result indicating authentication failure (missing or invalid API key).
     * Processing should be halted until the user updates the key and explicitly restarts.
     *
     * @return A result indicating auth failure; do not retry
     */
    public static ChunkSendResult authFailure() {
        return new ChunkSendResult(false, false, true);
    }
    
    /**
     * Gets whether the chunk send was successful.
     * 
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Gets whether an asset-map is needed.
     *
     * @return true if asset-map is needed, false otherwise
     */
    public boolean isAssetMapNeeded() {
        return needsAssetMap;
    }

    /**
     * Gets whether the failure was due to missing or invalid API key.
     * When true, chunk processing should be halted until the user updates the key.
     *
     * @return true if auth failure, false otherwise
     */
    public boolean isAuthFailure() {
        return authFailure;
    }
}
