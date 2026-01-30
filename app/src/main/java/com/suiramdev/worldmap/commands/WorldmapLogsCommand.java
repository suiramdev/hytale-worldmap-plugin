package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.suiramdev.worldmap.util.WorldmapLog;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Shows recent Worldmap plugin log entries in-game with severity colors. Usage: /worldmap logs
 */
public class WorldmapLogsCommand extends AbstractPlayerCommand {

    private static final int DEFAULT_LINES = 50;

    public WorldmapLogsCommand() {
        super("logs", "Show recent Worldmap plugin logs (last 50 lines).");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        List<WorldmapLog.LogEntry> entries = WorldmapLog.getRecentEntries(DEFAULT_LINES);
        if (entries.isEmpty()) {
            context.sendMessage(Message.raw("No log entries yet."));
            return;
        }

        Message msg = Message.raw("--- Worldmap logs (last " + entries.size() + ") ---\n");
        for (WorldmapLog.LogEntry entry : entries) {
            msg = msg.insert(Message.raw("[" + entry.time + "] "))
                    .insert(Message.raw("[" + entry.level + "] ").color(WorldmapLog.getLevelColorHex(entry.level)))
                    .insert(Message.raw(entry.message + "\n"));
        }
        context.sendMessage(msg);
    }
}
