package com.suiramdev.worldmap.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.suiramdev.worldmap.models.AssetMapPayload;

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
    private final String apiKey;
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
     * Sends asset map data to the API asynchronously.
     * World is derived from the API key (key is linked to a world).
     *
     * <p>
     * Sends block configuration data to the POST /api/asset-map endpoint.
     * </p>
     *
     * @param assetMap List of block configurations
     * @return CompletableFuture that completes with true on success, false on
     *         failure
     */
    public CompletableFuture<Boolean> sendAssetMap(List<AssetMapPayload> assetMap) {
        if (assetMap == null || assetMap.isEmpty()) {
            System.err.println("[Worldmap] No asset map data provided");
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println(
                        "[Worldmap] Sending " + assetMap.size() + " block entries for asset map");

                // Send to API endpoint
                return sendAssetMapToApi(assetMap);
            } catch (Exception e) {
                System.err.println("[Worldmap] Error sending asset map: " + e.getMessage());
                if (debugMode) {
                    e.printStackTrace();
                }
                return false;
            }
        });
    }

    /**
     * Sends asset map to API endpoint POST /api/asset-map.
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

        System.out.println("[Worldmap] Sending " + assetMap.size() + " asset map entries to " + apiUrl + " ("
                + payloadSize + " bytes)");

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
                    System.out.println("[Worldmap] Sending asset map to " + apiUrl + " (attempt " + (attempt + 1) + "/"
                            + maxRetries + ")");
                }

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                String responseBody = response.body();

                if (statusCode >= 200 && statusCode < 300) {
                    System.out.println("[Worldmap] Successfully sent asset map - Status: " + statusCode);
                    return true;
                } else {
                    System.err.println(
                            "[Worldmap] API returned error status " + statusCode + " for asset-map");
                    if (responseBody != null && !responseBody.isEmpty()) {
                        String bodyPreview = responseBody.length() > 500
                                ? responseBody.substring(0, 500) + "... (truncated)"
                                : responseBody;
                        System.err.println("[Worldmap] Error response body: " + bodyPreview);
                    }
                }
            } catch (IOException e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                }
                if (debugMode || attempt == maxRetries - 1) {
                    System.err
                            .println("[Worldmap] IO error sending asset map: " + errorMsg);
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[Worldmap] Request interrupted for asset-map");
                return false;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                }
                if (debugMode || attempt == maxRetries - 1) {
                    System.err.println(
                            "[Worldmap] Unexpected error sending asset map: " + errorMsg);
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            }

            attempt++;
            if (attempt < maxRetries) {
                // Exponential backoff
                long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                System.out.println("[Worldmap] Retrying asset map send in " + delayMs
                        + "ms (attempt " + (attempt + 1) + "/" + maxRetries + ")");
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                System.err.println("[Worldmap] Failed to send asset map after " + maxRetries + " attempts");
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
            System.err.println("[Worldmap] API base URL is not configured");
            return null;
        }

        // Remove trailing slash from base URL if present
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;

        return baseUrl + "/api/asset-map";
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
