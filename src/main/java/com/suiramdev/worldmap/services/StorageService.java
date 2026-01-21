package com.suiramdev.worldmap.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Service for managing storage of processed chunks and first-load status.
 * 
 * <p>
 * This service handles persistence of chunk processing state to disk,
 * allowing the plugin to track which chunks have already been processed
 * and avoid duplicate work.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class StorageService {

    private static final String STORAGE_FILE = "worldmap_data.json";

    private final File dataFolder;
    private final Gson gson;
    private StorageData data;

    /**
     * Creates a new StorageService instance.
     * 
     * @param dataFolder The plugin's data folder
     */
    public StorageService(File dataFolder) {
        this.dataFolder = dataFolder;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.data = new StorageData();
        loadStorage();
    }

    /**
     * Loads storage data from file.
     */
    private void loadStorage() {
        File storageFile = new File(dataFolder, STORAGE_FILE);

        if (!storageFile.exists()) {
            // First load - no storage file exists
            data.processedChunks = new HashSet<>();
            return;
        }

        try (FileReader reader = new FileReader(storageFile)) {
            StorageData loaded = gson.fromJson(reader, StorageData.class);
            if (loaded != null) {
                this.data = loaded;
                // Ensure processedChunks is initialized
                if (data.processedChunks == null) {
                    data.processedChunks = new HashSet<>();
                }
            }
        } catch (IOException e) {
            System.err.println("[Worldmap] Failed to load storage: " + e.getMessage());
            System.err.println("[Worldmap] Treating as first load");
            data.processedChunks = new HashSet<>();
        }
    }

    /**
     * Saves storage data to file.
     */
    public void saveStorage() {
        File storageFile = new File(dataFolder, STORAGE_FILE);

        try {
            // Ensure data folder exists
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            try (FileWriter writer = new FileWriter(storageFile)) {
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            System.err.println("[Worldmap] Failed to save storage: " + e.getMessage());
        }
    }

    /**
     * Checks if a chunk has been processed.
     * 
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return true if the chunk has been processed, false otherwise
     */
    public boolean isChunkProcessed(int chunkX, int chunkZ) {
        String key = chunkX + "," + chunkZ;
        return data.processedChunks.contains(key);
    }

    /**
     * Marks a chunk as processed.
     * 
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     */
    public void markChunkProcessed(int chunkX, int chunkZ) {
        String key = chunkX + "," + chunkZ;
        data.processedChunks.add(key);
        // Save periodically (could be optimized with batching)
        saveStorage();
    }

    /**
     * Gets the count of processed chunks.
     * 
     * @return The number of processed chunks
     */
    public int getProcessedChunkCount() {
        return data.processedChunks.size();
    }

    /**
     * Checks if block configurations have been sent for a world.
     * 
     * @param worldId The world identifier
     * @return true if block configs have been sent, false otherwise
     */
    public boolean areBlockConfigsSent(String worldId) {
        if (worldId == null || worldId.trim().isEmpty()) {
            return false;
        }
        return data.sentBlockConfigs != null && data.sentBlockConfigs.contains(worldId.trim());
    }

    /**
     * Marks block configurations as sent for a world.
     * 
     * @param worldId The world identifier
     */
    public void markBlockConfigsSent(String worldId) {
        if (worldId == null || worldId.trim().isEmpty()) {
            return;
        }
        if (data.sentBlockConfigs == null) {
            data.sentBlockConfigs = new HashSet<>();
        }
        data.sentBlockConfigs.add(worldId.trim());
        saveStorage();
    }

    /**
     * Internal class for JSON storage.
     */
    private static class StorageData {
        Set<String> processedChunks = new HashSet<>();
        Set<String> sentBlockConfigs = new HashSet<>();
    }
}
