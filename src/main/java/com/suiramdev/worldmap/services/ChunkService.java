package com.suiramdev.worldmap.services;

import com.suiramdev.worldmap.models.ChunkPayload;
import com.github.luben.zstd.Zstd;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    }

    /**
     * Sends chunk data to the API asynchronously.
     * 
     * @param payload ChunkPayload containing compact, binary-friendly chunk data
     * @return CompletableFuture that completes with true on success, false on
     *         failure
     */
    public CompletableFuture<Boolean> sendChunkData(ChunkPayload payload) {
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
                return false;
            }
        });
    }

    /**
     * Sends chunk data to API endpoint with retry logic.
     * 
     * @param payload The chunk payload to send
     * @return true if successful, false otherwise
     */
    private boolean sendChunkDataToApi(ChunkPayload payload) {
        int chunkX = payload.chunkX;
        int chunkZ = payload.chunkZ;

        // Build the chunk API URL
        String apiUrl = buildChunkApiUrl();
        if (apiUrl == null) {
            System.err.println("[Worldmap] API URL is not configured for chunk (" + chunkX + "," + chunkZ + ")");
            return false;
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
            return false;
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
            return false;
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
                    return false;
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
                    return true;
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
                return false;
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
                    return false;
                }
            } else {
                System.err.println("[Worldmap] Failed to send chunk (" + chunkX + "," + chunkZ + ") after " + maxRetries
                        + " attempts");
            }
        }

        return false;
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
}
