package com.suiramdev.worldmap.services;

import com.suiramdev.worldmap.models.MissingAssetManifestItem;
import com.suiramdev.worldmap.models.ResolvedAsset;
import com.suiramdev.worldmap.util.WorldmapLog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Queue that resolves missing assets and uploads them in manifest order.
 */
public class AssetUploadQueue {

    private final AssetService assetService;
    private final AssetBinaryResolver resolver;
    private final boolean debugMode;
    private final BlockingQueue<UploadRequest> queue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AssetUploadQueue(AssetService assetService, AssetBinaryResolver resolver, boolean debugMode) {
        this.assetService = assetService;
        this.resolver = resolver;
        this.debugMode = debugMode;
        worker.submit(this::runLoop);
    }

    public CompletableFuture<Boolean> enqueue(List<MissingAssetManifestItem> manifestItems, String assetMapHash) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (manifestItems == null || manifestItems.isEmpty()) {
            future.complete(false);
            return future;
        }
        queue.offer(new UploadRequest(manifestItems, assetMapHash, future));
        return future;
    }

    public void shutdown() {
        running.set(false);
        worker.shutdownNow();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                UploadRequest request = queue.take();
                boolean result;
                try {
                    result = processRequest(request);
                } catch (Exception e) {
                    result = false;
                    WorldmapLog.severe("Asset upload request failed: %s", e.getMessage());
                    if (debugMode) {
                        WorldmapLog.severe("Asset upload request failed", e);
                    }
                }
                request.future.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                WorldmapLog.severe("Asset upload queue error: %s", e.getMessage());
                if (debugMode) {
                    WorldmapLog.severe("Asset upload queue error", e);
                }
            }
        }
    }

    private boolean processRequest(UploadRequest request) {
        if (request == null || request.items == null || request.items.isEmpty()) {
            return false;
        }

        if (request.assetMapHash == null || request.assetMapHash.isEmpty()) {
            WorldmapLog.severe("Asset-map hash is missing; cannot upload asset slices");
            return false;
        }

        List<ResolvedAsset> resolvedAssets = new ArrayList<>();
        for (MissingAssetManifestItem item : request.items) {
            if (item == null) {
                continue;
            }
            String path = item.getPath();
            if (path == null || path.isEmpty()) {
                WorldmapLog.warn("Missing asset manifest item has no path (assetId=%s); skipping", item.getAssetId());
                continue;
            }

            if (item.getHashAlgorithm() != null &&
                    !"SHA-256".equalsIgnoreCase(item.getHashAlgorithm()) &&
                    !"SHA256".equalsIgnoreCase(item.getHashAlgorithm())) {
                WorldmapLog.warn("Unsupported hash algorithm '%s' for %s; using SHA-256",
                        item.getHashAlgorithm(), path);
            }

            var bytesOpt = resolver.resolve(item);
            if (bytesOpt.isEmpty()) {
                WorldmapLog.warn("Asset not found in registry or fallbacks: %s", path);
                continue;
            }

            byte[] data = bytesOpt.get();
            String contentHash = computeSha256Hex(data);
            String contentType = resolveContentType(item, path);
            resolvedAssets.add(new ResolvedAsset(path, contentHash, data, contentType));
        }

        if (resolvedAssets.isEmpty()) {
            WorldmapLog.severe("No missing assets could be resolved; upload skipped");
            return false;
        }

        return assetService.uploadAssetSlices(resolvedAssets, request.assetMapHash).join();
    }

    private String resolveContentType(MissingAssetManifestItem item, String path) {
        if (item != null && item.getContentTypeHint() != null && !item.getContentTypeHint().isEmpty()) {
            return item.getContentTypeHint();
        }
        String lower = path != null ? path.toLowerCase() : "";
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".json") || lower.endsWith(".blockymodel")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    private String computeSha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(data);
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final class UploadRequest {
        private final List<MissingAssetManifestItem> items;
        private final String assetMapHash;
        private final CompletableFuture<Boolean> future;

        private UploadRequest(List<MissingAssetManifestItem> items, String assetMapHash, CompletableFuture<Boolean> future) {
            this.items = items;
            this.assetMapHash = assetMapHash;
            this.future = future;
        }
    }
}
