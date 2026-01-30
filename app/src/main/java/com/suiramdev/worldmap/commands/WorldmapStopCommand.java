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
 * Stops chunk processing. No further chunks are sent until /worldmap start is used.
 */
public class WorldmapStopCommand extends AbstractPlayerCommand {

    public WorldmapStopCommand() {
        super("stop", "Stop Worldmap chunk processing.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Main main = Main.getInstance();
        if (main == null) {
            context.sendMessage(Message.raw("Worldmap plugin not loaded."));
            return;
        }
        main.getChunkManager().stopProcessing();
        context.sendMessage(Message.raw("Chunk processing stopped."));
    }
}
