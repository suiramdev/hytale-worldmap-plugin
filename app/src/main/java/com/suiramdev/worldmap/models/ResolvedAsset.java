package com.suiramdev.worldmap.models;

/**
 * Represents a resolved asset ready for upload.
 */
public class ResolvedAsset {
    private final String path;
    private final String contentHash;
    private final byte[] data;
    private final String contentType;

    public ResolvedAsset(String path, String contentHash, byte[] data, String contentType) {
        this.path = path;
        this.contentHash = contentHash;
        this.data = data;
        this.contentType = contentType;
    }

    public String getPath() {
        return path;
    }

    public String getContentHash() {
        return contentHash;
    }

    public byte[] getData() {
        return data;
    }

    public String getContentType() {
        return contentType;
    }
}
