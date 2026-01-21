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
    
    /**
     * Creates a new ChunkSendResult.
     * 
     * @param success true if the chunk was sent successfully
     * @param needsAssetMap true if the API indicated that an asset-map is needed
     */
    public ChunkSendResult(boolean success, boolean needsAssetMap) {
        this.success = success;
        this.needsAssetMap = needsAssetMap;
    }
    
    /**
     * Creates a successful result.
     * 
     * @return A successful result that doesn't need an asset-map
     */
    public static ChunkSendResult success() {
        return new ChunkSendResult(true, false);
    }
    
    /**
     * Creates a failure result.
     * 
     * @return A failure result that doesn't need an asset-map
     */
    public static ChunkSendResult failure() {
        return new ChunkSendResult(false, false);
    }
    
    /**
     * Creates a result indicating that an asset-map is needed.
     * 
     * @return A result indicating asset-map is needed (not successful yet)
     */
    public static ChunkSendResult needsAssetMap() {
        return new ChunkSendResult(false, true);
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
}
