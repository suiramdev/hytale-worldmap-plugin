package com.suiramdev.worldmap.models;

/**
 * Result of a chunk send operation.
 * 
 * <p>
 * Indicates whether the chunk was accepted and whether assets are missing.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class ChunkSendResult {
    
    private final boolean success;
    private final boolean authFailure;
    private final boolean missingAssets;
    private final java.util.List<MissingAssetManifestItem> missingAssetsList;
    private final java.util.List<Integer> missingBlockIds;
    private final String assetMapHash;
    private final String jobId;

    /**
     * Creates a new ChunkSendResult.
     *
     * @param success       true if the chunk was accepted
     * @param authFailure   true if the request failed due to missing or invalid API key (do not retry)
     * @param missingAssets true if the API reported missing assets
     * @param missingAssets list of missing assets (if any)
     * @param missingBlockIds list of missing block IDs (if any)
     * @param assetMapHash  asset map hash returned by server (if any)
     * @param jobId         job ID for accepted chunks (if any)
     */
    public ChunkSendResult(boolean success, boolean authFailure, boolean missingAssets,
                           java.util.List<MissingAssetManifestItem> missingAssetsItems,
                           java.util.List<Integer> missingBlockIds,
                           String assetMapHash,
                           String jobId) {
        this.success = success;
        this.authFailure = authFailure;
        this.missingAssets = missingAssets;
        this.missingAssetsList = missingAssetsItems != null ? java.util.List.copyOf(missingAssetsItems) : java.util.List.of();
        this.missingBlockIds = missingBlockIds != null ? java.util.List.copyOf(missingBlockIds) : java.util.List.of();
        this.assetMapHash = assetMapHash;
        this.jobId = jobId;
    }
    
    /**
     * Creates a successful result.
     * 
     * @return A successful result
     */
    public static ChunkSendResult success() {
        return new ChunkSendResult(true, false, false, java.util.List.of(), java.util.List.of(), null, null);
    }

    /**
     * Creates a successful result with a job ID.
     *
     * @param jobId The job ID returned by the API
     * @return A successful result with job ID
     */
    public static ChunkSendResult success(String jobId) {
        return new ChunkSendResult(true, false, false, java.util.List.of(), java.util.List.of(), null, jobId);
    }

    /**
     * Creates a failure result.
     *
     * @return A failure result
     */
    public static ChunkSendResult failure() {
        return new ChunkSendResult(false, false, false, java.util.List.of(), java.util.List.of(), null, null);
    }

    /**
     * Creates a result indicating authentication failure (missing or invalid API key).
     * Processing should be halted until the user updates the key and explicitly restarts.
     *
     * @return A result indicating auth failure; do not retry
     */
    public static ChunkSendResult authFailure() {
        return new ChunkSendResult(false, true, false, java.util.List.of(), java.util.List.of(), null, null);
    }

    /**
     * Creates a result indicating missing assets.
     *
     * @param missingAssets  Missing asset manifest items
     * @param missingBlockIds Missing block IDs
     * @param assetMapHash   Asset map hash returned by server
     * @return A result indicating missing assets
     */
    public static ChunkSendResult missingAssets(java.util.List<MissingAssetManifestItem> missingAssets,
                                                java.util.List<Integer> missingBlockIds,
                                                String assetMapHash) {
        return new ChunkSendResult(false, false, true, missingAssets, missingBlockIds, assetMapHash, null);
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
     * Gets whether the failure was due to missing or invalid API key.
     * When true, chunk processing should be halted until the user updates the key.
     *
     * @return true if auth failure, false otherwise
     */
    public boolean isAuthFailure() {
        return authFailure;
    }

    /**
     * Gets whether assets are missing for this chunk.
     *
     * @return true if assets are missing, false otherwise
     */
    public boolean isMissingAssets() {
        return missingAssets;
    }

    /**
     * Gets the missing asset manifest items.
     *
     * @return Missing assets (possibly empty)
     */
    public java.util.List<MissingAssetManifestItem> getMissingAssets() {
        return missingAssetsList;
    }

    /**
     * Gets the missing block IDs.
     *
     * @return Missing block IDs (possibly empty)
     */
    public java.util.List<Integer> getMissingBlockIds() {
        return missingBlockIds;
    }

    /**
     * Gets the asset map hash returned by the server.
     *
     * @return Asset map hash or null
     */
    public String getAssetMapHash() {
        return assetMapHash;
    }

    /**
     * Gets the job ID for accepted chunks.
     *
     * @return Job ID or null
     */
    public String getJobId() {
        return jobId;
    }
}
