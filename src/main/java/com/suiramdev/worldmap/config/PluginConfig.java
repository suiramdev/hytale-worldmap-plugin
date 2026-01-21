package com.suiramdev.worldmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Manages plugin configuration from config.json.
 * 
 * <p>
 * This class handles loading and saving of plugin configuration,
 * providing access to API settings, timeouts, retry counts, and debug mode.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class PluginConfig {
    private static final String CONFIG_FILE = "config.json";

    private String apiUrl = "http://localhost:3000/api";
    private String apiKey = "";
    private String worldId = "";
    private int requestTimeout = 30000;
    private int maxRetries = 3;
    private int batchSize = 10;
    private boolean debugMode = false;

    private final File dataFolder;
    private final Gson gson;

    public PluginConfig(File dataFolder) {
        this.dataFolder = dataFolder;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadConfig();
    }

    /**
     * Loads configuration from config.json file.
     * 
     * <p>
     * If the config file doesn't exist, a default configuration is created.
     * </p>
     */
    private void loadConfig() {
        File configFile = new File(dataFolder, CONFIG_FILE);

        if (!configFile.exists()) {
            // Create default config file
            saveConfig();
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            ConfigData data = gson.fromJson(reader, ConfigData.class);
            if (data != null) {
                if (data.apiUrl != null)
                    this.apiUrl = data.apiUrl;
                if (data.apiKey != null)
                    this.apiKey = data.apiKey;
                if (data.worldId != null)
                    this.worldId = data.worldId;
                if (data.requestTimeout > 0)
                    this.requestTimeout = data.requestTimeout;
                if (data.maxRetries > 0)
                    this.maxRetries = data.maxRetries;
                if (data.batchSize > 0)
                    this.batchSize = data.batchSize;
                this.debugMode = data.debugMode;
            }
        } catch (IOException e) {
            System.err.println("[Worldmap] Failed to load config: " + e.getMessage());
            System.err.println("[Worldmap] Using default configuration");
        }
    }

    /**
     * Saves current configuration to config.json file.
     */
    private void saveConfig() {
        File configFile = new File(dataFolder, CONFIG_FILE);

        try {
            // Ensure data folder exists
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            ConfigData data = new ConfigData();
            data.apiUrl = this.apiUrl;
            data.apiKey = this.apiKey;
            data.worldId = this.worldId;
            data.requestTimeout = this.requestTimeout;
            data.maxRetries = this.maxRetries;
            data.batchSize = this.batchSize;
            data.debugMode = this.debugMode;

            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            System.err.println("[Worldmap] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Gets the base API URL.
     * 
     * @return The base API URL
     */
    public String getApiBaseUrl() {
        return apiUrl;
    }

    /**
     * Gets the API key for authentication.
     * 
     * @return The API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Gets the world identifier.
     * 
     * @return The world identifier, or empty string if not configured
     */
    public String getWorldId() {
        return worldId != null ? worldId.trim() : "";
    }

    /**
     * Gets the request timeout in milliseconds.
     * 
     * @return The request timeout
     */
    public int getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Gets the maximum number of retry attempts.
     * 
     * @return The maximum retry count
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Gets the batch size for processing.
     * 
     * @return The batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Checks if debug mode is enabled.
     * 
     * @return true if debug mode is enabled, false otherwise
     */
    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Internal class for JSON deserialization
     */
    private static class ConfigData {
        String apiUrl;
        String apiKey;
        String worldId;
        int requestTimeout;
        int maxRetries;
        int batchSize;
        boolean debugMode;
    }
}
