package com.suiramdev.worldmap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.suiramdev.worldmap.Main;

import javax.annotation.Nonnull;

/**
 * Force re-process, render, and send a single chunk to the API.
 * Usage: /worldmap reprocess &lt;chunkX&gt; &lt;chunkZ&gt;
 */
public class WorldmapReprocessCommand extends AbstractPlayerCommand {

    private final RequiredArg<Integer> chunkXArg = this.withRequiredArg("chunkX", "Chunk X coordinate", ArgTypes.INTEGER);
    private final RequiredArg<Integer> chunkZArg = this.withRequiredArg("chunkZ", "Chunk Z coordinate", ArgTypes.INTEGER);

    public WorldmapReprocessCommand() {
        super("reprocess", "Force re-process and send chunk (chunkX, chunkZ) to the API.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Main main = Main.getInstance();
        if (main == null) {
            context.sendMessage(Message.raw("Worldmap plugin not loaded."));
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            context.sendMessage(Message.raw("Universe not available."));
            return;
        }

        World defaultWorld = universe.getDefaultWorld();
        if (defaultWorld == null) {
            context.sendMessage(Message.raw("Default world not available."));
            return;
        }

        int chunkX = chunkXArg.get(context);
        int chunkZ = chunkZArg.get(context);

        main.getChunkManager().forceReprocessChunk(defaultWorld, chunkX, chunkZ);
        context.sendMessage(Message.raw("Chunk (" + chunkX + "," + chunkZ + ") queued for re-processing and sending."));
    }
}
