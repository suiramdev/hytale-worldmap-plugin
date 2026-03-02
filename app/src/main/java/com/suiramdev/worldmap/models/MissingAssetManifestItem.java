package com.suiramdev.worldmap.models;

/**
 * Represents a missing asset entry reported by the API.
 *
 * <p>
 * Fields mirror the server's missing-asset manifest contract.
 * </p>
 */
public class MissingAssetManifestItem {
    private final String assetId;
    private final String path;
    private final String assetMapHash;
    private final String hashAlgorithm;
    private final String contentTypeHint;

    public MissingAssetManifestItem(String assetId, String path, String assetMapHash,
                                    String hashAlgorithm, String contentTypeHint) {
        this.assetId = assetId;
        this.path = path;
        this.assetMapHash = assetMapHash;
        this.hashAlgorithm = hashAlgorithm;
        this.contentTypeHint = contentTypeHint;
    }

    public String getAssetId() {
        return assetId;
    }

    public String getPath() {
        return path;
    }

    public String getAssetMapHash() {
        return assetMapHash;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public String getContentTypeHint() {
        return contentTypeHint;
    }
}
