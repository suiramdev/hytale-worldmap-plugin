# Worldmap Plugin

> **⚠️ Work in Progress** - This is an ongoing development project for a dynmap application in the Hytale game.

A Hytale server plugin that processes world chunks and sends them to the [Worldmap Web Application](https://github.com/suiramdev/hytale-worldmap) for real-time map visualization.

## About

This plugin is the **server-side component** of the Worldmap system. It runs on your Hytale server and:

- Extracts chunk data (blocks, height maps, tint maps) from the Hytale world
- Sends chunk data to the Worldmap web application's worker API
- Tracks processed chunks to avoid duplicate processing
- Processes chunks asynchronously to avoid impacting server performance

**This plugin is designed to work with:** [https://github.com/suiramdev/hytale-worldmap](https://github.com/suiramdev/hytale-worldmap)

The web application provides:
- Interactive web-based map viewer
- Real-time tile generation and rendering
- Player and administrator map exploration tools

## Features

✅ **Asynchronous Processing** - Chunks are processed in the background without blocking the server  
✅ **Smart Tracking** - Tracks processed chunks to avoid duplicates  
✅ **Configurable** - Easy configuration via `config.json`  
✅ **Error Handling** - Robust error handling with retry logic  
✅ **Performance Optimized** - Uses non-ticking chunks to avoid affecting gameplay  
✅ **First Load Detection** - Automatically processes all existing chunks on first run  

## Prerequisites

- **Hytale Server** - A running Hytale server instance
- **Java 25 JDK** - Required to build the plugin
- **Worldmap Web Application** - The [web application](https://github.com/suiramdev/hytale-worldmap) must be running and accessible
- **Hytale Server JAR** - You need to obtain `HytaleServer.jar` and place it in `libs/HytaleServer.jar` (see [First-Time Setup](#first-time-setup) section)

## First-Time Setup

When setting up the repository for the first time, use the `setup.sh` script to automatically download or copy the required files:

```bash
./scripts/setup.sh
```

The script will prompt you for:

1. **HytaleServer.jar** - You can provide either:
   - A URL (e.g., `https://example.com/HytaleServer.jar`)
   - A local file path (e.g., `/path/to/HytaleServer.jar`)
   
   The file will be copied to `libs/HytaleServer.jar`

2. **Asset.zip** - You can provide either:
   - A URL (e.g., `https://example.com/Asset.zip`)
   - A local file path (e.g., `/path/to/Asset.zip`)
   
   The file will be copied to `run/Asset.zip`

The script automatically:
- Creates the `libs/` and `run/` directories if they don't exist
- Downloads files from URLs using `curl` or `wget`
- Copies files from local paths
- Provides clear feedback on the setup process

**Example:**
```bash
$ ./scripts/setup.sh
Please provide the HytaleServer.jar:
You can provide either:
  - A URL (e.g., https://example.com/HytaleServer.jar)
  - A file path (e.g., /path/to/HytaleServer.jar)
HytaleServer.jar (URL or path): https://example.com/HytaleServer.jar
Downloading from URL: https://example.com/HytaleServer.jar
✓ Successfully copied HytaleServer.jar

Please provide the Asset.zip:
You can provide either:
  - A URL (e.g., https://example.com/Asset.zip)
  - A file path (e.g., /path/to/Asset.zip)
Asset.zip (URL or path): /Users/me/Downloads/Asset.zip
Copying from: /Users/me/Downloads/Asset.zip
✓ Successfully copied Asset.zip

✓ Setup completed successfully!
  - HytaleServer.jar is in: libs
  - Asset.zip is in: run
```

## Quick Start

### 0. Initial Setup (First Time Only)

If you haven't run the setup script yet, do so now:

```bash
./scripts/setup.sh
```

This will set up the required `HytaleServer.jar` and `Asset.zip` files. See the [First-Time Setup](#first-time-setup) section above for details.

**For AI Coding Assistance:** If you're using AI coding assistants, you can decompile the JAR for better code completion:

```bash
./scripts/decompile.sh libs/HytaleServer.jar
```

This decompiles the JAR to `decompiled/` for reference. See the [Development](#development) section for more details.

### 1. Build the Plugin

```bash
# Windows
gradlew.bat shadowJar

# Linux/Mac
./gradlew shadowJar
```

The plugin JAR will be in: `build/libs/Worldmap-1.0.0.jar`

### 2. Install the Plugin

1. Copy the JAR file to your Hytale server's `plugins/` directory
2. Ensure the Worldmap web application is running (see [web app setup](https://github.com/suiramdev/hytale-worldmap#quick-start))
3. Start your Hytale server

### 3. Configure the Plugin

On first run, the plugin will create a `config.json` file in `plugins/Worldmap/`:

```json
{
  "apiUrl": "http://localhost:3000/api/worker/process-chunk",
  "requestTimeout": 30000,
  "maxRetries": 3,
  "batchSize": 10,
  "debugMode": false
}
```

**Important Configuration:**

- **`apiUrl`** - The URL of your Worldmap web application's worker API endpoint
  - Default: `http://localhost:3000/api/worker/process-chunk`
  - If your web app is running on a different host/port, update this accordingly
- **`requestTimeout`** - HTTP request timeout in milliseconds (default: 30000)
- **`maxRetries`** - Number of retry attempts for failed requests (default: 3)
- **`batchSize`** - Number of concurrent chunk processing requests (default: 10)
- **`debugMode`** - Enable debug logging (default: false)

### 4. First Run

On the first run, the plugin will:
1. Detect that no chunks have been processed
2. Scan all chunks in your world
3. Queue them for processing in the background
4. Send chunk data to the web application's worker API

This process runs asynchronously and won't impact server performance. Progress is logged to the server console.

## How It Works

### Chunk Processing Flow

1. **Plugin Startup** - On server start, the plugin checks if this is the first load
2. **Chunk Discovery** - If first load, it discovers all existing chunks in the world
3. **Data Extraction** - For each chunk, it extracts:
   - Block data (32x320x32 array of block IDs)
   - Height map (32x32 array of height values)
   - Tint map (32x32 array of tint values)
   - Environment/biome data (if available)
4. **API Communication** - Chunk data is sent to the web application's worker API
5. **Tracking** - Processed chunks are tracked to avoid duplicate processing

### Integration with Web Application

The plugin communicates with the Worldmap web application via HTTP:

```
Plugin → HTTP POST → Web App Worker API → Tile Generation → Storage
```

The web application's worker receives chunk data and:
- Generates map tiles from the chunk data
- Stores tiles in object storage (MinIO/S3)
- Makes tiles available through the web interface

## Configuration

### config.json

The configuration file is located at: `plugins/Worldmap/config.json`

**Example Configuration:**

```json
{
  "apiUrl": "http://your-server:3000/api/worker/process-chunk",
  "requestTimeout": 30000,
  "maxRetries": 5,
  "batchSize": 20,
  "debugMode": true
}
```

**Configuration Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiUrl` | string | `http://localhost:3000/api/worker/process-chunk` | Worker API endpoint URL |
| `requestTimeout` | number | 30000 | HTTP request timeout (ms) |
| `maxRetries` | number | 3 | Maximum retry attempts for failed requests |
| `batchSize` | number | 10 | Concurrent chunk processing limit |
| `debugMode` | boolean | false | Enable detailed debug logging |

### Network Configuration

If your web application is running on a different machine:

1. Update `apiUrl` in `config.json` to point to your web app's hostname/IP
2. Ensure the Hytale server can reach the web application (firewall rules, network access)
3. If using HTTPS, update the URL scheme: `https://your-server:3000/api/worker/process-chunk`

## Development

### Setting Up for Development

Before building the plugin, you need to obtain the required files. The easiest way is to use the setup script:

```bash
./scripts/setup.sh
```

This will prompt you for `HytaleServer.jar` and `Asset.zip` and automatically place them in the correct locations. See the [First-Time Setup](#first-time-setup) section for details.

**Manual Setup (Alternative):**

If you prefer to set up manually:

1. **Obtain `HytaleServer.jar`** - Get the Hytale server JAR file (it's not included in this repository)
2. **Place it in `libs/`** - Copy the JAR file to `libs/HytaleServer.jar`
3. **Obtain `Asset.zip`** - Get the Asset.zip file
4. **Place it in `run/`** - Copy the file to `run/Asset.zip`

The plugin uses `HytaleServer.jar` as a compile-time dependency to access Hytale's API classes.

### Decompiling for AI Coding Assistance

If you're using AI coding assistants (like Cursor, GitHub Copilot, etc.), you can decompile the Hytale server JAR to make the API classes available for code completion and reference:

```bash
# Decompile the Hytale server JAR
./scripts/decompile.sh libs/HytaleServer.jar
```

This will:
- Decompile `libs/HytaleServer.jar` using CFR (Java decompiler)
- Output the decompiled source code to `decompiled/`
- Make Hytale API classes available for your IDE and AI assistants

**Note:** The decompiled code is for reference only and should not be modified. It helps with:
- Understanding Hytale API structure
- Code completion in your IDE
- AI assistants understanding the API

### Building from Source

```bash
# Clone the repository
git clone https://github.com/suiramdev/worldmap-plugin.git
cd worldmap-plugin

# Run the setup script to obtain required files
./scripts/setup.sh

# Build the plugin
./gradlew shadowJar
```

### Project Structure

```
worldmap-plugin/
├── src/main/
│   ├── java/com/suiramdev/worldmap/
│   │   ├── Main.java                    # Main plugin class
│   │   ├── config/
│   │   │   └── PluginConfig.java         # Configuration management
│   │   ├── services/
│   │   │   ├── ChunkProcessingService.java  # Chunk processing logic
│   │   │   └── HttpClientService.java        # HTTP client for API calls
│   │   └── storage/
│   │       └── StorageService.java       # Chunk tracking storage
│   └── resources/
│       ├── manifest.json                 # Plugin manifest
│       └── config.json                   # Default configuration
├── build.gradle.kts                      # Build configuration
└── README.md                             # This file
```

### Testing

To test the plugin with a local Hytale server:

```bash
# Run server with plugin (requires Hytale server JAR)
# The server will auto-detect Asset.zip, Assets.zip, or assets.zip in run/ directory if present
./gradlew runServer
```

The `runServer` task will automatically look for assets files in the `run/` directory with the following names (in order of priority):
- `run/Asset.zip`
- `run/Assets.zip`
- `run/assets.zip`

If found, the assets file will be automatically passed to the Hytale server.

## Troubleshooting

### Plugin Not Processing Chunks

1. **Check Configuration** - Verify `apiUrl` in `config.json` is correct
2. **Check Web App** - Ensure the web application worker is running and accessible
3. **Check Logs** - Enable `debugMode: true` in config.json for detailed logs
4. **Network Connectivity** - Test if the server can reach the web app: `curl http://your-web-app:3000/api/worker/health`

### Chunks Not Appearing on Map

1. **Worker Status** - Check if the web application worker is processing chunks
2. **API Endpoint** - Verify the worker API endpoint is correct
3. **Storage** - Check if tiles are being generated and stored
4. **Web Interface** - Refresh the web map interface

### Performance Issues

1. **Reduce Batch Size** - Lower `batchSize` in config.json if server is under load
2. **Increase Timeout** - Increase `requestTimeout` if network is slow
3. **Check Web App** - Ensure the web application can handle the request rate

## Logs

The plugin logs to the Hytale server console with the `[Worldmap]` prefix:

```
[Worldmap] Plugin loaded!
[Worldmap] Plugin enabled!
[Worldmap] Configuration loaded - API URL: http://localhost:3000/api/worker/process-chunk
[Worldmap] First load detected - processing all chunks...
[Worldmap] Found 1234 chunks to process
[Worldmap] Queued 100 / 1234 chunks for processing
[Worldmap] Processed 100 chunks (failed: 0)
```

Enable `debugMode: true` for more detailed logging.

## Requirements

- **Hytale Server** - Compatible Hytale server version
- **Java 25** - Required for building (plugin runs on server's Java version)
- **Network Access** - Plugin must be able to reach the web application's API

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Related Projects

- **[Worldmap Web Application](https://github.com/suiramdev/hytale-worldmap)** - The web application that receives and processes chunk data

## License

This project is released under the MIT License.

## Support

- **Issues:** [GitHub Issues](https://github.com/suiramdev/worldmap-plugin/issues)
- **Web App:** [Worldmap Web Application](https://github.com/suiramdev/hytale-worldmap)

---

**Happy Mapping! 🗺️**
