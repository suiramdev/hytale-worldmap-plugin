package com.suiramdev.worldmap.services;

import com.suiramdev.worldmap.models.ChunkPayload;
import com.suiramdev.worldmap.models.ChunkSendResult;
import com.suiramdev.worldmap.util.WorldmapLog;
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
    private volatile String apiKey;
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
     * Updates the API key (e.g. after /worldmap key). Takes effect on next request.
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
    }

    /**
     * Sends chunk data to the API asynchronously.
     * World is derived from the API key (key is linked to a world).
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
                    WorldmapLog.fine("Request interrupted for chunk (%d,%d)", payload.chunkX, payload.chunkZ);
                }
                return ChunkSendResult.failure();
            }
        });
    }

    /**
     * Sends chunk data to API endpoint with retry logic.
     *
     * @param payload The chunk payload to send
     * @return ChunkSendResult indicating success, failure, or if asset-map is
     *         needed
     */
    private ChunkSendResult sendChunkDataToApi(ChunkPayload payload) {
        int chunkX = payload.chunkX;
        int chunkZ = payload.chunkZ;

        // Missing or empty API key: do not retry; processing must be halted until user sets key
        if (apiKey == null || apiKey.isEmpty()) {
            WorldmapLog.severe("API key is missing. Chunk processing halted. Use /worldmap key <key> then /worldmap start.");
            return ChunkSendResult.authFailure();
        }

        // Build the chunk API URL (world derived from API key)
        String apiUrl = buildChunkApiUrl();
        if (apiUrl == null) {
            WorldmapLog.severe("API URL is not configured for chunk (%d,%d)", chunkX, chunkZ);
            return ChunkSendResult.failure();
        }

        // Serialize to binary format
        byte[] serializedData;
        try {
            serializedData = payload.serialize();
        } catch (Exception e) {
            WorldmapLog.severe("Failed to serialize chunk payload for (%d,%d): %s",
                    chunkX, chunkZ, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
            if (debugMode) {
                WorldmapLog.severe("Serialization error", e);
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
                    WorldmapLog.severe("Invalid API URL: %s for chunk (%d,%d)", apiUrl, chunkX, chunkZ);
                    return ChunkSendResult.failure();
                }

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Content-Type", "application/octet-stream")
                        .header("X-Chunk-Format-Version", String.valueOf(ChunkPayload.FORMAT_VERSION))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(serializedData))
                        .timeout(Duration.ofMillis(requestTimeout));

                // Add Authorization header with Bearer token if API key is provided
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header("Authorization", "Bearer " + apiKey);
                }

                HttpRequest httpRequest = requestBuilder.build();

                // Log request details
                int rawSize = serializedData.length;
                WorldmapLog.info("Sending chunk (%d,%d) to %s (attempt %d/%d, size: %d bytes)",
                        chunkX, chunkZ, apiUrl, attempt + 1, maxRetries, rawSize);

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                String responseBody = response.body();

                // Log response details
                WorldmapLog.info("API response for chunk (%d,%d): Status %d", chunkX, chunkZ, statusCode);

                if (responseBody != null && !responseBody.isEmpty()) {
                    // Truncate very long responses for readability
                    String bodyPreview = responseBody.length() > 500
                            ? responseBody.substring(0, 500) + "... (truncated)"
                            : responseBody;
                    WorldmapLog.info("API response body: %s", bodyPreview);
                } else {
                    WorldmapLog.info("API response body: (empty)");
                }

                if (debugMode) {
                    WorldmapLog.fine("Response headers: %s", response.headers().map());
                }

                if (statusCode >= 200 && statusCode < 300) {
                    WorldmapLog.info("Successfully sent chunk (%d,%d) - Status: %d", chunkX, chunkZ, statusCode);
                    return ChunkSendResult.success();
                } else if (statusCode == 401 || statusCode == 403) {
                    WorldmapLog.severe("API returned %d (unauthorized). Invalid or missing API key. Chunk processing halted. Use /worldmap key <key> then /worldmap start.", statusCode);
                    return ChunkSendResult.authFailure();
                } else if (statusCode == 428) {
                    // Check if this is ASSET_MAP_MISSING error
                    if (isAssetMapMissingError(responseBody)) {
                        WorldmapLog.info("Received 428 ASSET_MAP_MISSING for chunk (%d,%d). Asset-map is required.", chunkX, chunkZ);
                        return ChunkSendResult.needsAssetMap();
                    } else {
                        WorldmapLog.severe("API returned 428 status (not ASSET_MAP_MISSING) for chunk (%d,%d)", chunkX, chunkZ);
                        if (responseBody != null && !responseBody.isEmpty()) {
                            WorldmapLog.severe("Error response body: %s", responseBody);
                        }
                    }
                } else {
                    WorldmapLog.severe("API returned error status %d for chunk (%d,%d)", statusCode, chunkX, chunkZ);
                    if (responseBody != null && !responseBody.isEmpty()) {
                        WorldmapLog.severe("Error response body: %s", responseBody);
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
                    WorldmapLog.severe("IO error sending chunk (%d,%d) to %s: %s", chunkX, chunkZ, apiUrl, errorMsg);
                    if (!connectionWarningShown && (errorMsg.contains("refused") ||
                            errorMsg.contains("Unreachable") ||
                            errorMsg.contains("no message"))) {
                        WorldmapLog.warn("Cannot connect to API server. Ensure the web application is running at %s", apiUrl);
                        connectionWarningShown = true;
                    }
                    if (debugMode) {
                        WorldmapLog.severe("IO error", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (debugMode) {
                    WorldmapLog.fine("Request interrupted for chunk (%d,%d)", chunkX, chunkZ);
                }
                return ChunkSendResult.failure();
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + " (no message)";
                }
                if (debugMode || attempt == maxRetries - 1) {
                    WorldmapLog.severe("Unexpected error sending chunk (%d,%d): %s", chunkX, chunkZ, errorMsg);
                    if (debugMode) {
                        WorldmapLog.severe("Unexpected error", e);
                    }
                }
            }

            attempt++;
            if (attempt < maxRetries) {
                // Exponential backoff: wait 1s, 2s, 4s, etc.
                long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                WorldmapLog.info("Retrying chunk (%d,%d) in %dms (attempt %d/%d)", chunkX, chunkZ, delayMs, attempt + 1, maxRetries);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ChunkSendResult.failure();
                }
            } else {
                WorldmapLog.severe("Failed to send chunk (%d,%d) after %d attempts", chunkX, chunkZ, maxRetries);
            }
        }

        return ChunkSendResult.failure();
    }

    /**
     * Fetches the list of processed chunks from the API.
     * World is derived from the API key (key is linked to a world).
     *
     * <p>
     * Calls GET {baseUrl}/chunks/list to retrieve all chunks that have been processed.
     * </p>
     *
     * @return CompletableFuture that completes with a Set of processed chunk keys
     *         (format: "x,z"), or empty set on failure
     */
    public CompletableFuture<Set<String>> fetchProcessedChunksList() {
        return CompletableFuture.supplyAsync(() -> {
            if (apiKey == null || apiKey.isEmpty()) {
                WorldmapLog.severe("API key is missing. Cannot fetch processed chunks list.");
                return new HashSet<>();
            }

            String apiUrl = buildChunksListApiUrl();
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

                    // Add Authorization header with Bearer token if API key is provided
                    if (apiKey != null && !apiKey.isEmpty()) {
                        requestBuilder.header("Authorization", "Bearer " + apiKey);
                    }

                    HttpRequest httpRequest = requestBuilder.build();

                    if (debugMode || attempt == 0) {
                        WorldmapLog.info("Fetching processed chunks list from %s (attempt %d/%d)", apiUrl, attempt + 1, maxRetries);
                    }

                    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                    int statusCode = response.statusCode();
                    String responseBody = response.body();

                    if (statusCode >= 200 && statusCode < 300) {
                        Set<String> processedChunks = parseProcessedChunksResponse(responseBody);
                        if (processedChunks.isEmpty() && (responseBody == null || responseBody.trim().isEmpty())) {
                            WorldmapLog.info("API returned empty response - no chunks processed yet");
                        } else {
                            WorldmapLog.info("Successfully fetched %d processed chunks from API", processedChunks.size());
                        }
                        return processedChunks;
                    } else if (statusCode == 401 || statusCode == 403) {
                        WorldmapLog.severe("API returned %d when fetching processed chunks list. Invalid or missing API key. Halting.", statusCode);
                        return new HashSet<>();
                    } else {
                        WorldmapLog.severe("API returned error status %d when fetching processed chunks list from %s", statusCode, apiUrl);
                        if (responseBody != null && !responseBody.isEmpty()) {
                            String bodyPreview = responseBody.length() > 500
                                    ? responseBody.substring(0, 500) + "... (truncated)"
                                    : responseBody;
                            WorldmapLog.severe("Error response body: %s", bodyPreview);
                        } else {
                            WorldmapLog.severe("Error response body: (empty)");
                        }
                    }
                } catch (IOException e) {
                    String errorMsg = e.getMessage();
                    if (errorMsg == null || errorMsg.isEmpty()) {
                        errorMsg = e.getClass().getSimpleName() + " (no message)";
                    }
                    if (debugMode || attempt == maxRetries - 1) {
                        WorldmapLog.severe("IO error fetching processed chunks list: %s", errorMsg);
                        if (debugMode) {
                            WorldmapLog.severe("IO error", e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    WorldmapLog.severe("Request interrupted while fetching processed chunks list");
                    return new HashSet<>();
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    if (errorMsg == null || errorMsg.isEmpty()) {
                        errorMsg = e.getClass().getSimpleName() + " (no message)";
                    }
                    if (debugMode || attempt == maxRetries - 1) {
                        WorldmapLog.severe("Unexpected error fetching processed chunks list: %s", errorMsg);
                        if (debugMode) {
                            WorldmapLog.severe("Unexpected error", e);
                        }
                    }
                }

                attempt++;
                if (attempt < maxRetries) {
                    // Exponential backoff
                    long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                    WorldmapLog.info("Retrying fetch processed chunks list in %dms (attempt %d/%d)", delayMs, attempt + 1, maxRetries);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new HashSet<>();
                    }
                } else {
                    WorldmapLog.severe("Failed to fetch processed chunks list after %d attempts", maxRetries);
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
                if (debugMode) {
                    WorldmapLog.fine("Response body parsed to null");
                }
                return processedChunks;
            }

            JsonArray chunksArray = null;

            // Check if it's an object with a "chunks" property
            if (jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                if (jsonObject.has("chunks") && jsonObject.get("chunks").isJsonArray()) {
                    chunksArray = jsonObject.getAsJsonArray("chunks");
                } else if (jsonObject.has("data") && jsonObject.get("data").isJsonArray()) {
                    // Alternative format: {"data": [...]}
                    chunksArray = jsonObject.getAsJsonArray("data");
                } else if (jsonObject.size() == 0) {
                    // Empty object - no chunks processed yet
                    if (debugMode) {
                        WorldmapLog.info("Received empty response object - no chunks processed yet");
                    }
                    return processedChunks;
                }
            }
            // Check if it's directly an array
            else if (jsonElement.isJsonArray()) {
                chunksArray = jsonElement.getAsJsonArray();
            }

            if (chunksArray == null) {
                WorldmapLog.severe("Unexpected response format for processed chunks list");
                WorldmapLog.severe("Response type: %s", jsonElement != null ? jsonElement.getClass().getSimpleName() : "null");
                if (jsonElement != null && jsonElement.isJsonObject()) {
                    JsonObject jsonObject = jsonElement.getAsJsonObject();
                    WorldmapLog.severe("Response keys: %s", jsonObject.keySet());
                }
                if (responseBody != null && responseBody.length() < 1000) {
                    WorldmapLog.severe("Response body: %s", responseBody);
                } else if (responseBody != null) {
                    WorldmapLog.severe("Response body (first 500 chars): %s", responseBody.substring(0, Math.min(500, responseBody.length())));
                }
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
            WorldmapLog.severe("Error parsing processed chunks response: %s", e.getMessage());
            if (debugMode) {
                WorldmapLog.severe("Parse error", e);
            }
        }

        return processedChunks;
    }

    /**
     * Builds the API URL for the chunks list endpoint.
     * World is derived from the API key.
     *
     * <p>
     * Constructs: {baseUrl}/chunks/list (baseUrl already includes /api if configured)
     * </p>
     *
     * @return The complete chunks list API URL, or null if base URL is not
     *         configured
     */
    private String buildChunksListApiUrl() {
        if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
            return null;
        }

        // Remove trailing slash from base URL if present
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;

        return baseUrl + "/chunks/list";
    }

    /**
     * Builds the API URL for the chunk processing endpoint.
     * World is derived from the API key.
     *
     * <p>
     * Constructs: {baseUrl}/chunks/process (baseUrl already includes /api if configured)
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

        return baseUrl + "/chunks/process";
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
                WorldmapLog.fine("Error parsing response body for ASSET_MAP_MISSING check: %s", e.getMessage());
            }
        }

        // Fallback: check if response body contains the string "ASSET_MAP_MISSING"
        return responseBody.contains("ASSET_MAP_MISSING");
    }
}
