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
import com.suiramdev.worldmap.managers.ChunkManager;

import javax.annotation.Nonnull;

/**
 * Shows whether Worldmap is currently processing chunks or is halted.
 */
public class WorldmapStatusCommand extends AbstractPlayerCommand {

    public WorldmapStatusCommand() {
        super("status", "Show whether Worldmap is processing chunks or halted.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Main main = Main.getInstance();
        if (main == null) {
            context.sendMessage(Message.raw("Worldmap plugin not loaded."));
            return;
        }

        ChunkManager chunkManager = main.getChunkManager();
        String state = chunkManager.getProcessingState();
        int processed = chunkManager.getProcessedCount();
        int failed = chunkManager.getFailedCount();

        String statusLine;
        if (ChunkManager.STATE_RUNNING.equals(state)) {
            statusLine = "Processing chunks (running).";
        } else {
            statusLine = "Halted.";
            String reason = chunkManager.getLastErrorMessage();
            if (reason != null && !reason.isEmpty()) {
                statusLine += " " + reason;
            } else if (ChunkManager.STATE_HALTED_USER.equals(state)) {
                statusLine += " Stopped by user. Use /worldmap process start to resume.";
            } else if (ChunkManager.STATE_HALTED_AUTH.equals(state)) {
                statusLine += " Invalid or missing API key. Use /worldmap key set <key> then /worldmap process start.";
            }
        }

        Message msg = Message.raw("Worldmap: " + statusLine);
        if (processed > 0 || failed > 0) {
            msg = msg.insert(Message.raw(" Processed: " + processed + ", failed: " + failed + "."));
        }
        context.sendMessage(msg);
    }
}
