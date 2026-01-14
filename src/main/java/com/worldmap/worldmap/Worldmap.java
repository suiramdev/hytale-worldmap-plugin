package com.example.templateplugin;

/**
 * Main plugin class.
 * 
 * TODO: Implement your plugin logic here.
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class Worldmap {

    private static Worldmap instance;

    /**
     * Constructor - Called when plugin is loaded.
     */
    public Worldmap() {
        instance = this;
        System.out.println("[TemplatePlugin] Plugin loaded!");
    }

    /**
     * Called when plugin is enabled.
     */
    public void onEnable() {
        System.out.println("[Worldmap] Plugin enabled!");

        // TODO: Initialize your plugin here
        // - Load configuration
        // - Register event listeners
        // - Register commands
        // - Start services
    }

    /**
     * Called when plugin is disabled.
     */
    public void onDisable() {
        System.out.println("[Worldmap] Plugin disabled!");

        // TODO: Cleanup your plugin here
        // - Save data
        // - Stop services
        // - Close connections
    }

    /**
     * Get plugin instance.
     */
    public static Worldmap getInstance() {
        return instance;
    }
}
