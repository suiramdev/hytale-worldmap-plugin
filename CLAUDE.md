# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hytale server plugin (Java 25) that processes world chunks and sends them to an external API for real-time web-based map visualization. Built on the Hytale Server API (`JavaPlugin` lifecycle).

## Build & Test Commands

```bash
./gradlew build          # Compile and run tests
./gradlew test           # Run tests only
./gradlew shadowJar      # Build fat JAR for deployment
./gradlew clean build    # Clean rebuild
```

The built JAR goes to `app/build/libs/WorldmapHytalePlugin.jar`.

## Architecture

### Layered Structure (`app/src/main/java/com/suiramdev/worldmap/`)

- **Main.java** — Plugin entry point (`JavaPlugin` subclass). Manages lifecycle (`setup` → `start` → `shutdown`), registers commands, wires up event listeners for block changes (place/break/damage) and chunk loads.

- **services/** — HTTP communication with the external map API.
  - `ChunkService` — Sends binary chunk data. Rate-limited to 5 concurrent requests via Semaphore. Handles retry logic.
  - `AssetService` — Syncs block asset maps (textures, draw types, opacity). Uses SHA-256 manifest hashing to skip redundant uploads.

- **managers/** — Business logic orchestration.
  - `ChunkManager` — Async chunk processing (10-thread ExecutorService). Tracks state: RUNNING / HALTED_USER / HALTED_AUTH. Deduplicates against API's processed-chunks list. Schedules resends when blocks change.
  - `AssetManager` — Extracts per-block rendering attributes (per-face textures, draw type, opacity, light emission, material) from BlockType registry. Includes retry logic for uninitialized registry at startup.

- **models/** — Data serialization.
  - `ChunkPayload` — Compact binary chunk format with block palette compression, bit-packed indices, and 1-block halo padding for cross-chunk face culling.
  - `AssetMapPayload` — Block configuration (textures, draw type, opacity, light, material). Uses builder pattern.
  - `ChunkSendResult` — API response model.

- **commands/** — Hierarchical command structure via `AbstractCommandCollection`:
  ```
  /worldmap key get|set, process start|stop|force, status, logs
  ```

- **config/PluginConfig.java** — Configuration via Hytale's Codec system (ApiUrl, ApiKey, AssetsZipPath, RequestTimeout, MaxRetries, BatchSize, DebugMode).

- **util/WorldmapLog.java** — Centralized logging with in-memory ring buffer (200 lines) for the `/worldmap logs` command.

### Key Patterns

- **Async everywhere**: `CompletableFuture` + `ExecutorService` for non-blocking chunk processing. `AtomicInteger`/`AtomicBoolean` for thread-safe counters and state.
- **Halo padding**: ChunkPayload includes 1-block border from neighboring chunks so the renderer can do face culling without additional fetches.
- **Content-addressed sync**: AssetService hashes the asset manifest (SHA-256) and checks with the API before uploading to avoid redundant transfers.
- **ECS event systems**: Block change detection uses Hytale's `EntityEventSystem` to listen for PlaceBlockEvent, BreakBlockEvent, DamageBlockEvent and trigger chunk resends.

## Dependencies

- **Hytale Server API** (`server/Server/HytaleServer.jar`) — compile-only, provided at runtime
- **GSON** — JSON serialization (provided by Hytale runtime)
- **FastUtil** — `LongSet` for chunk coordinate tracking (provided by Hytale runtime)
- **JUnit** — testing

## Commit Style

Conventional commits: `feat:`, `refactor:`, `fix:`, `chore:` with descriptive subjects.
