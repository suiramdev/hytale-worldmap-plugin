package com.suiramdev.worldmap.util;

import com.hypixel.hytale.logger.HytaleLogger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Central logging for the Worldmap plugin using {@link HytaleLogger}
 * and an in-memory ring buffer for in-game viewing via /worldmap logs.
 */
public final class WorldmapLog {

    private static final HytaleLogger LOGGER = HytaleLogger.get("Worldmap");
    private static final int MAX_BUFFER_LINES = 200;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final ConcurrentLinkedQueue<LogEntry> buffer = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger bufferSize = new AtomicInteger(0);

    /** Hex colors for severity when displayed in chat (e.g. /worldmap logs). */
    public static final String COLOR_INFO = "#55FF55";
    public static final String COLOR_WARN = "#FFAA00";
    public static final String COLOR_SEVERE = "#FF5555";
    public static final String COLOR_FINE = "#888888";

    private WorldmapLog() {
    }

    /**
     * Returns the hex color for a log level for use in chat.
     */
    public static String getLevelColorHex(String level) {
        if (level == null)
            return COLOR_INFO;
        return switch (level) {
            case "WARN" -> COLOR_WARN;
            case "SEVERE" -> COLOR_SEVERE;
            case "FINE" -> COLOR_FINE;
            default -> COLOR_INFO;
        };
    }

    /**
     * Log entry stored for in-game display.
     */
    public static final class LogEntry {
        public final String time;
        public final String level;
        public final String message;

        LogEntry(String level, String message) {
            this.time = TIME_FMT.format(Instant.now());
            this.level = level;
            this.message = message;
        }

        @Override
        public String toString() {
            return "[" + time + "] [" + level + "] " + message;
        }
    }

    private static void addToBuffer(String level, String message) {
        buffer.add(new LogEntry(level, message));
        if (bufferSize.incrementAndGet() > MAX_BUFFER_LINES) {
            buffer.poll();
            bufferSize.decrementAndGet();
        }
    }

    /** Info level. */
    public static void info(String message) {
        LOGGER.at(Level.INFO).log(message);
        addToBuffer("INFO", message);
    }

    /** Info with format. */
    public static void info(String format, Object... args) {
        LOGGER.at(Level.INFO).log(format, args);
        addToBuffer("INFO", String.format(format, args));
    }

    /** Warning level. */
    public static void warn(String message) {
        LOGGER.at(Level.WARNING).log(message);
        addToBuffer("WARN", message);
    }

    /** Warning with format. */
    public static void warn(String format, Object... args) {
        LOGGER.at(Level.WARNING).log(format, args);
        addToBuffer("WARN", String.format(format, args));
    }

    /** Severe/error level. */
    public static void severe(String message) {
        LOGGER.at(Level.SEVERE).log(message);
        addToBuffer("SEVERE", message);
    }

    /** Severe with format. */
    public static void severe(String format, Object... args) {
        LOGGER.at(Level.SEVERE).log(format, args);
        addToBuffer("SEVERE", String.format(format, args));
    }

    /** Severe with cause. */
    public static void severe(String message, Throwable cause) {
        ((HytaleLogger.Api) LOGGER.at(Level.SEVERE).withCause(cause)).log(message);
        addToBuffer("SEVERE", message);
        if (cause != null) {
            addToBuffer("SEVERE", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** Fine/debug level. */
    public static void fine(String message) {
        LOGGER.at(Level.FINE).log(message);
        addToBuffer("FINE", message);
    }

    /** Fine with format. */
    public static void fine(String format, Object... args) {
        LOGGER.at(Level.FINE).log(format, args);
        addToBuffer("FINE", String.format(format, args));
    }

    /**
     * Returns the last N log entries (newest at end). Used by /worldmap logs.
     */
    public static List<LogEntry> getRecentEntries(int maxLines) {
        List<LogEntry> all = new ArrayList<>(buffer);
        int size = all.size();
        if (size <= maxLines) {
            return all;
        }
        return new ArrayList<>(all.subList(size - maxLines, size));
    }
}
