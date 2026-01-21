package com.suiramdev.worldmap.services;

import com.suiramdev.worldmap.models.ChunkPayload;
import com.suiramdev.worldmap.models.ChunkSendResult;
import com.github.luben.zstd.Zstd;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Service for handling HTTP requests to send chunk data to the external API.
 * 
 * <p>
 * This service manages the communication with the chunk processing API
 * endpoint,
 * including serialization, compression, rate limiting, and retry logic.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class ChunkService {

    private final String apiBaseUrl;
    private final String apiKey;
    private final int requestTimeout;
    private final int maxRetries;
    private final boolean debugMode;

    private final HttpClient httpClient;
    private final Semaphore rateLimiter; // Limit concurrent requests (max 5)
    private static boolean connectionWarningShown = false; // Track if we've shown the connection warning
    private final Gson gson;

    /**
     * Creates a new ChunkService instance.
     * 
     * @param apiBaseUrl     The base API URL
     * @param apiKey         The API key for authentication
     * @param requestTimeout Request timeout in milliseconds
     * @param maxRetries     Maximum number of retry attempts
     * @param debugMode      Whether debug logging is enabled
     */
    public ChunkService(String apiBaseUrl, String apiKey, int requestTimeout, int maxRetries, boolean debugMode) {
        this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl.trim() : "";
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.debugMode = debugMode;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.rateLimiter = new Semaphore(5); // Max 5 concurrent requests
        this.gson = new GsonBuilder().create();
    }

    /**
     * Sends chunk data to the API asynchronously.
     * 
     * @param payload ChunkPayload containing compact, binary-friendly chunk data
     * @return CompletableFuture that completes with ChunkSendResult indicating
     *         success, failure, or if asset-map is needed
     */
    public CompletableFuture<ChunkSendResult> sendChunkData(ChunkPayload payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Acquire permit for rate limiting
                rateLimiter.acquire();

                try {
                    return sendChunkDataToApi(payload);
                } finally {
                    rateLimiter.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (debugMode && payload != null) {
                    System.err.println(
                            "[Worldmap] Request interrupted for chunk (" + payload.chunkX + "," + payload.chunkZ + ")");
                }
                return ChunkSendResult.failure();
            }
        });
    }

    /**
     * Sends chunk data to API endpoint with retry logic.
     * 
     * @param payload The chunk payload to send
     * @return ChunkSendResult indicating success, failure, or if asset-map is needed
     */
    private ChunkSendResult sendChunkDataToApi(ChunkPayload payload) {
        int chunkX = payload.chunkX;
        int chunkZ = payload.chunkZ;

        // Build the chunk API URL
        String apiUrl = buildChunkApiUrl();
        if (apiUrl == null) {
            System.err.println("[Worldmap] API URL is not configured for chunk (" + chunkX + "," + chunkZ + ")");
            return ChunkSendResult.failure();
        }

        // Serialize to binary format
        byte[] serializedData;
        try {
            serializedData = payload.serialize();
        } catch (Exception e) {
            System.err.println("[Worldmap] Failed to serialize chunk payload for (" + chunkX + "," + chunkZ + "): "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            if (debugMode) {
                e.printStackTrace();
            }
            return ChunkSendResult.failure();
        }

        // Compress with Zstd
        byte[] compressedData;
        try {
            // Use compression level 3 (balanced between speed and compression ratio)
            compressedData = Zstd.compress(serializedData, 3);
        } catch (Exception e) {
            System.err.println("[Worldmap] Failed to compress chunk payload for (" + chunkX + "," + chunkZ + "): "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            if (debugMode) {
                e.printStackTrace();
            }
            return ChunkSendResult.failure();
        }

        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                // Validate and create URI
                URI uri;
                try {
                    uri = URI.create(apiUrl);
                } catch (IllegalArgumentException e) {
                    System.err.println(
                            "[Worldmap] Invalid API URL: " + apiUrl + " for chunk (" + chunkX + "," + chunkZ + ")");
                    return ChunkSendResult.failure();
                }

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Content-Type", "application/octet-stream")
                        .header("X-Chunk-Format-Version", String.valueOf(ChunkPayload.FORMAT_VERSION))
                        .header("Content-Encoding", "zstd")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(compressedData))
                        .timeout(Duration.ofMillis(requestTimeout));

                // Add Authorization header with API key if provided
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header("Authorization", apiKey);
                }

                HttpRequest httpRequest = requestBuilder.build();

                // Log request details
                int rawSize = serializedData.length;
                int compressedSize = compressedData.length;
                double compressionRatio = (1.0 - (double) compressedSize / rawSize) * 100.0;
                System.out.println("[Worldmap] Sending chunk (" + chunkX + "," + chunkZ + ") to " + apiUrl
                        + " (attempt " + (attempt + 1) + "/" + maxRetries
                        + ", raw: " + rawSize + " bytes, compressed: " + compressedSize + " bytes"
                        + ", ratio: " + String.format("%.1f", compressionRatio) + "%)");

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                String responseBody = response.body();

                // Log response details
                System.out.println(
                        "[Worldmap] API response for chunk (" + chunkX + "," + chunkZ + "): Status " + statusCode);

                if (responseBody != null && !responseBody.isEmpty()) {
                    // Truncate very long responses for readability
                    String bodyPreview = responseBody.length() > 500
                            ? responseBody.substring(0, 500) + "... (truncated)"
                            : responseBody;
                    System.out.println("[Worldmap] API response body: " + bodyPreview);
                } else {
                    System.out.println("[Worldmap] API response body: (empty)");
                }

                if (debugMode) {
                    // Log response headers in debug mode
                    System.out.println("[Worldmap] Response headers: " + response.headers().map());
                }

                if (statusCode >= 200 && statusCode < 300) {
                    System.out.println("[Worldmap] Successfully sent chunk (" + chunkX + "," + chunkZ
                            + ") - Status: " + statusCode);
                    return ChunkSendResult.success();
                } else if (statusCode == 428) {
                    // Check if this is ASSET_MAP_MISSING error
                    if (isAssetMapMissingError(responseBody)) {
                        System.out.println("[Worldmap] Received 428 ASSET_MAP_MISSING for chunk (" + chunkX + ","
                                + chunkZ + "). Asset-map is required.");
                        // Return result indicating asset-map is needed
                        // The manager will handle sending the asset-map and retrying
                        return ChunkSendResult.needsAssetMap();
                    } else {
                        System.err.println("[Worldmap] API returned 428 status (not ASSET_MAP_MISSING) for chunk ("
                                + chunkX + "," + chunkZ + ")");
                        if (responseBody != null && !responseBody.isEmpty()) {
                            System.err.println("[Worldmap] Error response body: " + responseBody);
                        }
                    }
                } else {
                    System.err.println("[Worldmap] API returned error status " + statusCode + " for chunk ("
                            + chunkX + "," + chunkZ + ")");
                    if (responseBody != null && !responseBody.isEmpty()) {
                        System.err.println("[Worldmap] Error response body: " + responseBody);
                    }
                }
            } catch (IOException e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                    // Common causes for null message: connection refused, unreachable host
                    if (e.getClass().getSimpleName().contains("Connect") ||
                            e.getClass().getSimpleName().contains("Unreachable")) {
                        errorMsg += " - Check if API server is running at " + apiUrl;
                    }
                }
                if (debugMode || attempt == maxRetries - 1) {
                    System.err.println(
                            "[Worldmap] IO error sending chunk (" + chunkX + "," + chunkZ + ") to " + apiUrl + ": "
                                    + errorMsg);
                    // Show connection warning once
                    if (!connectionWarningShown && (errorMsg.contains("refused") ||
                            errorMsg.contains("Unreachable") ||
                            errorMsg.contains("no message"))) {
                        System.err.println(
                                "[Worldmap] WARNING: Cannot connect to API server. Ensure the web application is running at "
                                        + apiUrl);
                        connectionWarningShown = true;
                    }
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (debugMode) {
                    System.err.println("[Worldmap] Request interrupted for chunk (" + chunkX + "," + chunkZ + ")");
                }
                return ChunkSendResult.failure();
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                }
                if (debugMode || attempt == maxRetries - 1) {
                    System.err.println("[Worldmap] Unexpected error sending chunk (" + chunkX + "," + chunkZ + "): "
                            + errorMsg);
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            }

            attempt++;
            if (attempt < maxRetries) {
                // Exponential backoff: wait 1s, 2s, 4s, etc.
                long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                System.out.println("[Worldmap] Retrying chunk (" + chunkX + "," + chunkZ + ") in " + delayMs
                        + "ms (attempt " + (attempt + 1) + "/" + maxRetries + ")");
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ChunkSendResult.failure();
                }
            } else {
                System.err.println("[Worldmap] Failed to send chunk (" + chunkX + "," + chunkZ + ") after " + maxRetries
                        + " attempts");
            }
        }

        return ChunkSendResult.failure();
    }

    /**
     * Fetches the list of processed chunks from the API.
     * 
     * <p>
     * Calls GET /api/worlds/:worldId/chunks/list to retrieve all chunks that
     * have been processed.
     * </p>
     * 
     * @param worldId The world identifier
     * @return CompletableFuture that completes with a Set of processed chunk keys
     *         (format: "x,z"), or empty set on failure
     */
    public CompletableFuture<Set<String>> fetchProcessedChunksList(String worldId) {
        return CompletableFuture.supplyAsync(() -> {
            if (worldId == null || worldId.trim().isEmpty()) {
                System.err.println("[Worldmap] Invalid worldId provided to fetchProcessedChunksList");
                return new HashSet<>();
            }

            String apiUrl = buildChunksListApiUrl(worldId);
            if (apiUrl == null) {
                return new HashSet<>();
            }

            int attempt = 0;
            while (attempt < maxRetries) {
                try {
                    URI uri = URI.create(apiUrl);

                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(uri)
                            .header("Accept", "application/json")
                            .GET()
                            .timeout(Duration.ofMillis(requestTimeout));

                    // Add Authorization header with API key if provided
                    if (apiKey != null && !apiKey.isEmpty()) {
                        requestBuilder.header("Authorization", apiKey);
                    }

                    HttpRequest httpRequest = requestBuilder.build();

                    if (debugMode || attempt == 0) {
                        System.out.println("[Worldmap] Fetching processed chunks list from " + apiUrl
                                + " (attempt " + (attempt + 1) + "/" + maxRetries + ")");
                    }

                    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                    int statusCode = response.statusCode();
                    String responseBody = response.body();

                    if (statusCode >= 200 && statusCode < 300) {
                        Set<String> processedChunks = parseProcessedChunksResponse(responseBody);
                        System.out.println("[Worldmap] Successfully fetched " + processedChunks.size()
                                + " processed chunks from API");
                        return processedChunks;
                    } else {
                        System.err.println("[Worldmap] API returned error status " + statusCode
                                + " when fetching processed chunks list");
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
                        System.err.println("[Worldmap] IO error fetching processed chunks list: " + errorMsg);
                        if (debugMode) {
                            e.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[Worldmap] Request interrupted while fetching processed chunks list");
                    return new HashSet<>();
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    if (errorMsg == null || errorMsg.isEmpty()) {
                        errorMsg = e.getClass().getSimpleName() + " (no message)";
                    }
                    if (debugMode || attempt == maxRetries - 1) {
                        System.err.println("[Worldmap] Unexpected error fetching processed chunks list: " + errorMsg);
                        if (debugMode) {
                            e.printStackTrace();
                        }
                    }
                }

                attempt++;
                if (attempt < maxRetries) {
                    // Exponential backoff
                    long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                    System.out.println("[Worldmap] Retrying fetch processed chunks list in " + delayMs
                            + "ms (attempt " + (attempt + 1) + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new HashSet<>();
                    }
                } else {
                    System.err.println("[Worldmap] Failed to fetch processed chunks list after " + maxRetries
                            + " attempts");
                }
            }

            return new HashSet<>();
        });
    }

    /**
     * Parses the API response containing processed chunks list.
     * 
     * <p>
     * Supports multiple response formats:
     * <ul>
     * <li>Array of objects: [{"x": 1, "z": 2}, ...]</li>
     * <li>Array of strings: ["1,2", "3,4", ...]</li>
     * <li>Object with chunks array: {"chunks": [{"x": 1, "z": 2}, ...]}</li>
     * </ul>
     * </p>
     * 
     * @param responseBody The JSON response body
     * @return Set of chunk keys in format "x,z"
     */
    private Set<String> parseProcessedChunksResponse(String responseBody) {
        Set<String> processedChunks = new HashSet<>();

        if (responseBody == null || responseBody.trim().isEmpty()) {
            return processedChunks;
        }

        try {
            JsonElement jsonElement = gson.fromJson(responseBody, JsonElement.class);

            if (jsonElement == null) {
                return processedChunks;
            }

            JsonArray chunksArray = null;

            // Check if it's an object with a "chunks" property
            if (jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                if (jsonObject.has("chunks") && jsonObject.get("chunks").isJsonArray()) {
                    chunksArray = jsonObject.getAsJsonArray("chunks");
                }
            }
            // Check if it's directly an array
            else if (jsonElement.isJsonArray()) {
                chunksArray = jsonElement.getAsJsonArray();
            }

            if (chunksArray == null) {
                System.err.println("[Worldmap] Unexpected response format for processed chunks list");
                return processedChunks;
            }

            // Parse each chunk entry
            for (JsonElement element : chunksArray) {
                if (element.isJsonObject()) {
                    // Format: {"x": 1, "z": 2}
                    JsonObject chunkObj = element.getAsJsonObject();
                    if (chunkObj.has("x") && chunkObj.has("z")) {
                        int x = chunkObj.get("x").getAsInt();
                        int z = chunkObj.get("z").getAsInt();
                        processedChunks.add(x + "," + z);
                    }
                } else if (element.isJsonPrimitive()) {
                    // Format: "1,2"
                    String chunkKey = element.getAsString();
                    if (chunkKey != null && !chunkKey.trim().isEmpty()) {
                        processedChunks.add(chunkKey.trim());
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[Worldmap] Error parsing processed chunks response: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
        }

        return processedChunks;
    }

    /**
     * Builds the API URL for the chunks list endpoint.
     * 
     * <p>
     * Constructs: {baseUrl}/api/worlds/:worldId/chunks/list
     * </p>
     * 
     * @param worldId The world identifier
     * @return The complete chunks list API URL, or null if base URL is not
     *         configured
     */
    private String buildChunksListApiUrl(String worldId) {
        if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
            return null;
        }

        // Remove trailing slash from base URL if present
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;

        // Build full URL: {baseUrl}/api/worlds/:worldId/chunks/list
        return baseUrl + "/worlds/" + worldId + "/chunks/list";
    }

    /**
     * Builds the API URL for the chunk processing endpoint.
     * 
     * <p>
     * Constructs: {baseUrl}/worker/process-chunk
     * </p>
     * 
     * @return The complete chunk processing API URL, or null if base URL is not
     *         configured
     */
    private String buildChunkApiUrl() {
        if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
            return null;
        }

        // Remove trailing slash from base URL if present
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;

        // Build full URL: {baseUrl}/worker/process-chunk
        return baseUrl + "/worker/process-chunk";
    }

    /**
     * Checks if the response body indicates an ASSET_MAP_MISSING error.
     * 
     * @param responseBody The response body from the API
     * @return true if the error code is ASSET_MAP_MISSING, false otherwise
     */
    private boolean isAssetMapMissingError(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return false;
        }

        try {
            JsonElement jsonElement = gson.fromJson(responseBody, JsonElement.class);
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                // Check for "code" field with value "ASSET_MAP_MISSING"
                if (jsonObject.has("code")) {
                    String code = jsonObject.get("code").getAsString();
                    return "ASSET_MAP_MISSING".equals(code);
                }
                // Also check for "error" field that might contain the code
                if (jsonObject.has("error")) {
                    String error = jsonObject.get("error").getAsString();
                    return "ASSET_MAP_MISSING".equals(error);
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error parsing response body for ASSET_MAP_MISSING check: "
                        + e.getMessage());
            }
        }

        // Fallback: check if response body contains the string "ASSET_MAP_MISSING"
        return responseBody.contains("ASSET_MAP_MISSING");
    }
}
