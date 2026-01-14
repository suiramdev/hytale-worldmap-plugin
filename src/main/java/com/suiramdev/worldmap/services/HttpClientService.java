package com.suiramdev.worldmap.services;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Handles HTTP requests to the external API
 */
public class HttpClientService {
    private final String apiUrl;
    private final int requestTimeout;
    private final int maxRetries;
    private final boolean debugMode;

    private final HttpClient httpClient;
    private final Gson gson;
    private final Semaphore rateLimiter; // Limit concurrent requests (max 5)

    public HttpClientService(String apiUrl, int requestTimeout, int maxRetries, boolean debugMode) {
        this.apiUrl = apiUrl;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.debugMode = debugMode;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.gson = new Gson();
        this.rateLimiter = new Semaphore(5); // Max 5 concurrent requests
    }

    /**
     * Send chunk data to the API
     * 
     * @param chunkX    Chunk X coordinate
     * @param chunkZ    Chunk Z coordinate
     * @param chunkData Chunk data object
     * @return CompletableFuture that completes with true on success, false on
     *         failure
     */
    public CompletableFuture<Boolean> sendChunkData(int chunkX, int chunkZ, Object chunkData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Acquire permit for rate limiting
                rateLimiter.acquire();

                try {
                    return sendChunkDataWithRetry(chunkX, chunkZ, chunkData);
                } finally {
                    rateLimiter.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (debugMode) {
                    System.err.println("[Worldmap] Request interrupted for chunk (" + chunkX + "," + chunkZ + ")");
                }
                return false;
            }
        });
    }

    /**
     * Send chunk data with retry logic
     */
    private boolean sendChunkDataWithRetry(int chunkX, int chunkZ, Object chunkData) {
        // Create request payload
        ChunkRequest request = new ChunkRequest();
        request.chunkX = chunkX;
        request.chunkZ = chunkZ;
        request.chunkData = chunkData;

        String jsonBody = gson.toJson(request);

        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofMillis(requestTimeout))
                        .build();

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    if (debugMode) {
                        System.out.println("[Worldmap] Successfully sent chunk (" + chunkX + "," + chunkZ
                                + ") - Status: " + statusCode);
                    }
                    return true;
                } else {
                    if (debugMode || attempt == maxRetries - 1) {
                        System.err.println("[Worldmap] API returned error status " + statusCode + " for chunk ("
                                + chunkX + "," + chunkZ + ")");
                    }
                }
            } catch (IOException e) {
                if (debugMode || attempt == maxRetries - 1) {
                    System.err.println(
                            "[Worldmap] IO error sending chunk (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (debugMode) {
                    System.err.println("[Worldmap] Request interrupted for chunk (" + chunkX + "," + chunkZ + ")");
                }
                return false;
            } catch (Exception e) {
                if (debugMode || attempt == maxRetries - 1) {
                    System.err.println("[Worldmap] Unexpected error sending chunk (" + chunkX + "," + chunkZ + "): "
                            + e.getMessage());
                }
            }

            attempt++;
            if (attempt < maxRetries) {
                // Exponential backoff: wait 1s, 2s, 4s, etc.
                long delayMs = (long) Math.pow(2, attempt - 1) * 1000;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Request payload structure
     */
    private static class ChunkRequest {
        int chunkX;
        int chunkZ;
        Object chunkData;
    }
}
