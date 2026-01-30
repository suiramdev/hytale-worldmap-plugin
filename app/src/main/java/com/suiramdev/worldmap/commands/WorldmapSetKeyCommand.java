package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.suiramdev.worldmap.Main;

import javax.annotation.Nonnull;

/**
 * Sets the API key and optionally restarts chunk processing.
 * Usage: /worldmap key set <key> [restart]
 */
public class WorldmapSetKeyCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> keyArg = this.withRequiredArg("key", "API key", ArgTypes.STRING);
    private final OptionalArg<String> restartArg = this.withOptionalArg("restart", "Type 'restart' to start processing after save", ArgTypes.STRING);

    public WorldmapSetKeyCommand() {
        super("set", "Set the Worldmap API key. Add 'restart' to start processing after save.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Main main = Main.getInstance();
        if (main == null) {
            context.sendMessage(Message.raw("Worldmap plugin not loaded."));
            return;
        }
        String key = keyArg.get(context);
        if (key == null || key.isEmpty()) {
            context.sendMessage(Message.raw("API key cannot be empty."));
            return;
        }
        boolean restart = restartArg.provided(context) && "restart".equalsIgnoreCase(String.valueOf(restartArg.get(context)).trim());
        main.updateApiKeyAndSave(key, restart);
        context.sendMessage(Message.raw("API key saved."));
        if (restart) {
            context.sendMessage(Message.raw("Chunk processing started."));
        }
    }
}
