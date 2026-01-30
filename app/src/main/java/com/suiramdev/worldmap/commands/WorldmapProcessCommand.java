package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Process subcommand for the Worldmap plugin: /worldmap process start | stop | force
 */
public class WorldmapProcessCommand extends AbstractCommandCollection {

    public WorldmapProcessCommand() {
        super("process", "Start, stop, or force-process chunks.");
        this.addSubCommand(new WorldmapProcessStartCommand());
        this.addSubCommand(new WorldmapProcessStopCommand());
        this.addSubCommand(new WorldmapProcessForceCommand());
    }
}
