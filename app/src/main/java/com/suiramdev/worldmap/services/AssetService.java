package com.suiramdev.worldmap.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.suiramdev.worldmap.models.AssetMapPayload;
import com.suiramdev.worldmap.util.WorldmapLog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for handling HTTP requests to send asset data to the external API.
 *
 * <p>
 * This service manages communication with the asset map API endpoint,
 * sending block configuration data for world rendering.
 * </p>
 *
 * @author suiramdev
 * @version 1.0.0
 */
public class AssetService {

    private final String apiBaseUrl;
    private volatile String apiKey;
    private final int requestTimeout;
    private final int maxRetries;
    private final boolean debugMode;

    private final HttpClient httpClient;
    private final Gson gson;

    /**
     * Creates a new AssetService instance.
     *
     * @param apiBaseUrl     The base API URL
     * @param apiKey         The API key for authentication
     * @param requestTimeout Request timeout in milliseconds
     * @param maxRetries     Maximum number of retry attempts
     * @param debugMode      Whether debug logging is enabled
     */
    public AssetService(String apiBaseUrl, String apiKey, int requestTimeout, int maxRetries, boolean debugMode) {
        this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl.trim() : "";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.debugMode = debugMode;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Updates the API key (e.g. after /worldmap key). Takes effect on next request.
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
    }

    /**
     * Sends asset map data to the API asynchronously.
     * World is derived from the API key (key is linked to a world).
     *
     * <p>
     * Sends block configuration data to the POST {baseUrl}/asset-map endpoint.
     * </p>
     *
     * @param assetMap List of block configurations
     * @return CompletableFuture that completes with true on success, false on
     *         failure
     */
    public CompletableFuture<Boolean> sendAssetMap(List<AssetMapPayload> assetMap) {
        if (assetMap == null || assetMap.isEmpty()) {
            WorldmapLog.severe("No asset map data provided");
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                WorldmapLog.info("Sending %d block entries for asset map", assetMap.size());

                // Send to API endpoint
                return sendAssetMapToApi(assetMap);
            } catch (Exception e) {
                WorldmapLog.severe("Error sending asset map: %s", e.getMessage());
                if (debugMode) {
                    WorldmapLog.severe("Error sending asset map", e);
                }
                return false;
            }
        });
    }

    /**
     * Sends asset map to API endpoint POST {baseUrl}/asset-map.
     * World is derived from the API key.
     *
     * @param assetMap List of block configurations
     * @return true if successful, false otherwise
     */
    private boolean sendAssetMapToApi(List<AssetMapPayload> assetMap) {
        String apiUrl = buildAssetMapApiUrl();
        if (apiUrl == null) {
            return false;
        }

        // Convert asset map to JSON array
        JsonArray blocksArray = new JsonArray();
        for (AssetMapPayload entry : assetMap) {
            JsonObject blockObj = assetMapEntryToJson(entry);
            if (blockObj != null) {
                blocksArray.add(blockObj);
            }
        }

        String jsonPayload = gson.toJson(blocksArray);
        int payloadSize = jsonPayload.length();

        WorldmapLog.info("Sending %d asset map entries to %s (%d bytes)", assetMap.size(), apiUrl, payloadSize);

        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .timeout(Duration.ofMillis(requestTimeout));

                // Add Authorization header with Bearer token if API key is provided
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header("Authorization", "Bearer " + apiKey);
                }

                HttpRequest httpRequest = requestBuilder.build();

                if (debugMode || attempt == 0) {
                    WorldmapLog.info("Sending asset map to %s (attempt %d/%d)", apiUrl, attempt + 1, maxRetries);
                }

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                String responseBody = response.body();

                if (statusCode >= 200 && statusCode < 300) {
                    WorldmapLog.info("Successfully sent asset map - Status: %d", statusCode);
                    return true;
                } else {
                    WorldmapLog.severe("API returned error status %d for asset-map", statusCode);
                    if (responseBody != null && !responseBody.isEmpty()) {
                        String bodyPreview = responseBody.length() > 500
                                ? responseBody.substring(0, 500) + "... (truncated)"
                                : responseBody;
                        WorldmapLog.severe("Error response body: %s", bodyPreview);
                    }
                }
            } catch (IOException e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                }
                if (debugMode || attempt == maxRetries - 1) {
                    WorldmapLog.severe("IO error sending asset map: %s", errorMsg);
                    if (debugMode) {
                        WorldmapLog.severe("IO error", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                WorldmapLog.severe("Request interrupted for asset-map");
                return false;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                }
                if (debugMode || attempt == maxRetries - 1) {
                    WorldmapLog.severe("Unexpected error sending asset map: %s", errorMsg);
                    if (debugMode) {
                        WorldmapLog.severe("Unexpected error", e);
                    }
                }
            }

            attempt++;
            if (attempt < maxRetries) {
                // Exponential backoff
                long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                WorldmapLog.info("Retrying asset map send in %dms (attempt %d/%d)", delayMs, attempt + 1, maxRetries);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                WorldmapLog.severe("Failed to send asset map after %d attempts", maxRetries);
            }
        }

        return false;
    }

    /**
     * Builds the API URL for the asset map endpoint.
     * World is derived from the API key.
     *
     * @return The complete API URL, or null if base URL is not configured
     */
    private String buildAssetMapApiUrl() {
        if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
            WorldmapLog.severe("API base URL is not configured");
            return null;
        }

        // Remove trailing slash from base URL if present
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;

        return baseUrl + "/asset-map";
    }

    /**
     * Converts an asset map entry to a JSON object.
     *
     * @param entry The block configuration entry
     * @return The JSON object representation
     */
    private JsonObject assetMapEntryToJson(AssetMapPayload entry) {
        JsonObject blockObj = new JsonObject();

        blockObj.addProperty("blockId", entry.getBlockId());

        // Add textures object
        JsonObject textures = new JsonObject();
        textures.addProperty("north", entry.getTextureNorth());
        textures.addProperty("south", entry.getTextureSouth());
        textures.addProperty("east", entry.getTextureEast());
        textures.addProperty("west", entry.getTextureWest());
        textures.addProperty("top", entry.getTextureTop());
        textures.addProperty("bottom", entry.getTextureBottom());
        blockObj.add("textures", textures);

        blockObj.addProperty("drawType", entry.getDrawType().name());
        if (entry.getCustomModel() != null) {
            blockObj.addProperty("customModel", entry.getCustomModel());
        }
        blockObj.addProperty("opacity", entry.getOpacity().name());
        blockObj.addProperty("lightEmission", entry.getLightEmission());
        blockObj.addProperty("material", entry.getMaterial().name());

        // Add metadata if present
        Map<String, Object> metadata = entry.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            JsonObject metadataObj = new JsonObject();
            for (Map.Entry<String, Object> metadataEntry : metadata.entrySet()) {
                Object value = metadataEntry.getValue();
                if (value instanceof String) {
                    metadataObj.addProperty(metadataEntry.getKey(), (String) value);
                } else if (value instanceof Number) {
                    metadataObj.addProperty(metadataEntry.getKey(), ((Number) value).doubleValue());
                } else if (value instanceof Boolean) {
                    metadataObj.addProperty(metadataEntry.getKey(), (Boolean) value);
                }
            }
            blockObj.add("metadata", metadataObj);
        }

        return blockObj;
    }
}
