package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Key subcommand for the Worldmap plugin: /worldmap key get | set
 */
public class WorldmapKeyCommand extends AbstractCommandCollection {

    public WorldmapKeyCommand() {
        super("key", "Get or set the Worldmap API key.");
        this.addSubCommand(new WorldmapKeyGetCommand());
        this.addSubCommand(new WorldmapSetKeyCommand());
    }
}
