package com.suiramdev.worldmap.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages storage of processed chunks and first-load status
 */
public class StorageService {
    private static final String STORAGE_FILE = "worldmap_data.json";

    private final File dataFolder;
    private final Gson gson;
    private StorageData data;

    public StorageService(File dataFolder) {
        this.dataFolder = dataFolder;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.data = new StorageData();
        loadStorage();
    }

    /**
     * Load storage data from file
     */
    private void loadStorage() {
        File storageFile = new File(dataFolder, STORAGE_FILE);

        if (!storageFile.exists()) {
            // First load - no storage file exists
            data.firstLoad = true;
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
            data.firstLoad = true;
            data.processedChunks = new HashSet<>();
        }
    }

    /**
     * Save storage data to file
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
     * Check if this is the first load
     */
    public boolean isFirstLoad() {
        return data.firstLoad;
    }

    /**
     * Mark that chunks have been loaded (no longer first load)
     */
    public void markAsLoaded() {
        data.firstLoad = false;
        saveStorage();
    }

    /**
     * Check if a chunk has been processed
     */
    public boolean isChunkProcessed(int chunkX, int chunkZ) {
        String key = chunkX + "," + chunkZ;
        return data.processedChunks.contains(key);
    }

    /**
     * Mark a chunk as processed
     */
    public void markChunkProcessed(int chunkX, int chunkZ) {
        String key = chunkX + "," + chunkZ;
        data.processedChunks.add(key);
        // Save periodically (could be optimized with batching)
        saveStorage();
    }

    /**
     * Get count of processed chunks
     */
    public int getProcessedChunkCount() {
        return data.processedChunks.size();
    }

    /**
     * Internal class for JSON storage
     */
    private static class StorageData {
        boolean firstLoad = true;
        Set<String> processedChunks = new HashSet<>();
    }
}
