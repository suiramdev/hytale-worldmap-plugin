package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Main command for the Worldmap plugin: /worldmap key | start | stop
 */
public class WorldmapCommand extends AbstractCommandCollection {

    public WorldmapCommand() {
        super("worldmap", "Worldmap plugin: configure API key and chunk processing.");
        this.addSubCommand(new WorldmapSetKeyCommand());
        this.addSubCommand(new WorldmapStartCommand());
        this.addSubCommand(new WorldmapStopCommand());
        this.addSubCommand(new WorldmapReprocessCommand());
        this.addSubCommand(new WorldmapLogsCommand());
    }
}
