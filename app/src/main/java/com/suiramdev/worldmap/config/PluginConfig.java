package com.suiramdev.worldmap.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Plugin configuration using Hytale's Codec system.
 * Loaded via {@link com.hypixel.hytale.server.core.util.Config} and
 * {@link com.hypixel.hytale.server.core.plugin.JavaPlugin#withConfig}.
 */
public class PluginConfig {
    private String apiUrl = "http://localhost:3000/api";
    private String apiKey = "";
    private String assetsZipPath = "Assets.zip";
    private int requestTimeout = 30000;
    private int maxRetries = 3;
    private int batchSize = 10;
    private boolean debugMode = false;

    public static final BuilderCodec<PluginConfig> CODEC = BuilderCodec.builder(PluginConfig.class, PluginConfig::new)
            .append(new KeyedCodec<>("ApiUrl", Codec.STRING),
                    (config, val) -> config.apiUrl = val,
                    config -> config.apiUrl)
            .add()
            .append(new KeyedCodec<>("ApiKey", Codec.STRING),
                    (config, val) -> config.apiKey = val,
                    config -> config.apiKey)
            .add()
            .append(new KeyedCodec<>("AssetsZipPath", Codec.STRING),
                    (config, val) -> config.assetsZipPath = val,
                    config -> config.assetsZipPath)
            .add()
            .append(new KeyedCodec<>("RequestTimeout", Codec.INTEGER),
                    (config, val) -> config.requestTimeout = val,
                    config -> config.requestTimeout)
            .add()
            .append(new KeyedCodec<>("MaxRetries", Codec.INTEGER),
                    (config, val) -> config.maxRetries = val,
                    config -> config.maxRetries)
            .add()
            .append(new KeyedCodec<>("BatchSize", Codec.INTEGER),
                    (config, val) -> config.batchSize = val,
                    config -> config.batchSize)
            .add()
            .append(new KeyedCodec<>("DebugMode", Codec.BOOLEAN),
                    (config, val) -> config.debugMode = val,
                    config -> config.debugMode)
            .add()
            .build();

    public String getApiBaseUrl() {
        return apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAssetsZipPath() {
        return assetsZipPath;
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

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl != null ? apiUrl : "";
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
    }

    public void setAssetsZipPath(String assetsZipPath) {
        this.assetsZipPath = assetsZipPath != null ? assetsZipPath : "";
    }
}
