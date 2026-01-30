package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.suiramdev.worldmap.Main;

import javax.annotation.Nonnull;

/**
 * Shows whether the API key is set (and its length). Does not display the key.
 * Usage: /worldmap key get
 */
public class WorldmapKeyGetCommand extends AbstractPlayerCommand {

    public WorldmapKeyGetCommand() {
        super("get", "Show whether the Worldmap API key is set (length only).");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Main main = Main.getInstance();
        if (main == null) {
            context.sendMessage(Message.raw("Worldmap plugin not loaded."));
            return;
        }
        String apiKey = main.getConfig().getApiKey();
        String status = (apiKey == null || apiKey.trim().isEmpty())
                ? "Not set. Use /worldmap key set <key> to set it."
                : "Set (" + apiKey.length() + " characters).";
        context.sendMessage(Message.raw("API key: " + status));
    }
}
