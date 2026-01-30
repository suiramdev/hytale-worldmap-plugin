package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Main command for the Worldmap plugin: /worldmap key | process | status | ...
 */
public class WorldmapCommand extends AbstractCommandCollection {

    public WorldmapCommand() {
        super("worldmap", "Worldmap plugin: configure API key and chunk processing.");
        this.addSubCommand(new WorldmapKeyCommand());
        this.addSubCommand(new WorldmapProcessCommand());
        this.addSubCommand(new WorldmapStatusCommand());
        this.addSubCommand(new WorldmapLogsCommand());
    }
}
