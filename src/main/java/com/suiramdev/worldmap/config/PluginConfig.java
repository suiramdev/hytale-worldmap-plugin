package com.suiramdev.worldmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Manages plugin configuration from config.json
 */
public class PluginConfig {
    private static final String CONFIG_FILE = "config.json";

    private String apiUrl = "http://localhost:3000/api/worker/process-chunk";
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
     * Load configuration from config.json file
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
     * Save current configuration to config.json
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

    public String getApiUrl() {
        return apiUrl;
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Internal class for JSON deserialization
     */
    private static class ConfigData {
        String apiUrl;
        int requestTimeout;
        int maxRetries;
        int batchSize;
        boolean debugMode;
    }
}
