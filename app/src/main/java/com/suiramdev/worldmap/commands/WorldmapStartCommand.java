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
 * Starts chunk processing. Use after setting API key or after a manual stop.
 */
public class WorldmapStartCommand extends AbstractPlayerCommand {

    public WorldmapStartCommand() {
        super("start", "Start Worldmap chunk processing.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Main main = Main.getInstance();
        if (main == null) {
            context.sendMessage(Message.raw("Worldmap plugin not loaded."));
            return;
        }
        main.getChunkManager().startProcessing();
        main.getChunkManager().fetchProcessedChunksList().thenRun(() -> {
            // Call processAllChunks from Main - we need to do this on the main thread / world executor
            world.execute(() -> {
                Main m = Main.getInstance();
                if (m != null) {
                    m.processAllChunksPublic();
                }
            });
        });
        context.sendMessage(Message.raw("Chunk processing started."));
    }
}
