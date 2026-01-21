import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Custom Gradle plugin for automated Hytale server testing.
 * 
 * Usage:
 *   runHytale {
 *       jarUrl = "https://example.com/hytale-server.jar"
 *   }
 *   
 *   ./gradlew runServer
 */
open class RunHytalePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Create extension for configuration
        val extension = project.extensions.create("runHytale", RunHytaleExtension::class.java)

        // Register the runServer task
        val runTask: TaskProvider<RunServerTask> = project.tasks.register(
            "runServer", 
            RunServerTask::class.java
        ) {
            jarUrl.set(extension.jarUrl)
            group = "hytale"
            description = "Downloads and runs the Hytale server with your plugin"
        }

        // Make runServer depend on shadowJar (build plugin first)
        project.tasks.findByName("shadowJar")?.let {
            runTask.configure {
                dependsOn(it)
            }
        }

        // Register the stopServer task
        project.tasks.register("stopServer", StopServerTask::class.java) {
            group = "hytale"
            description = "Stops any running Hytale server processes"
        }
    }
}

/**
 * Extension for configuring the RunHytale plugin.
 */
open class RunHytaleExtension {
    var jarUrl: String = "https://example.com/hytale-server.jar"
}

/**
 * Task that downloads, sets up, and runs a Hytale server with the plugin.
 */
open class RunServerTask : DefaultTask() {

    @Input
    val jarUrl = project.objects.property(String::class.java)

    @TaskAction
    fun run() {
        // Create directories
        val runDir = File(project.projectDir, "run").apply { mkdirs() }
        val modsDir = File(runDir, "mods").apply { mkdirs() }
        val jarFile = File(runDir, "server.jar")

        // Cache directory for downloaded server JARs
        val cacheDir = File(
            project.layout.buildDirectory.asFile.get(), 
            "hytale-cache"
        ).apply { mkdirs() }

        // Compute hash of URL for caching
        val urlHash = MessageDigest.getInstance("SHA-256")
            .digest(jarUrl.get().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cachedJar = File(cacheDir, "$urlHash.jar")

        // Download server JAR if not cached
        if (!cachedJar.exists()) {
            println("Downloading Hytale server from ${jarUrl.get()}")
            try {
                URI.create(jarUrl.get()).toURL().openStream().use { input ->
                    cachedJar.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Server JAR downloaded and cached")
            } catch (e: Exception) {
                println("ERROR: Failed to download server JAR")
                println("Make sure the jarUrl in build.gradle.kts is correct")
                println("Error: ${e.message}")
                return
            }
        } else {
            println("Using cached server JAR")
        }

        // Copy server JAR to run directory
        cachedJar.copyTo(jarFile, overwrite = true)

        // Copy plugin JAR to mods folder
        project.tasks.findByName("shadowJar")?.outputs?.files?.firstOrNull()?.let { shadowJar ->
            val targetFile = File(modsDir, shadowJar.name)
            shadowJar.copyTo(targetFile, overwrite = true)
            println("Plugin copied to: ${targetFile.absolutePath}")
        } ?: run {
            println("WARNING: Could not find shadowJar output")
        }

        println("Starting Hytale server...")
        println("Press Ctrl+C to stop the server")

        // Check if debug mode is enabled
        val debugMode = project.hasProperty("debug")
        val javaArgs = mutableListOf<String>()
        
        if (debugMode) {
            javaArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
            println("Debug mode enabled. Connect debugger to port 5005")
        }

        javaArgs.addAll(listOf("-jar", jarFile.name))

        // Auto-detect Asset.zip, Assets.zip, or assets.zip in run/ directory
        val assetZipOptions = listOf("run/Asset.zip", "run/Assets.zip", "run/assets.zip")
        var assetZip: File? = null
        for (option in assetZipOptions) {
            val candidate = File(project.rootDir, option)
            if (candidate.exists() && candidate.isFile) {
                assetZip = candidate
                break
            }
        }
        
        if (assetZip != null) {
            javaArgs.add("--assets")
            javaArgs.add(assetZip.absolutePath)
            println("Assets file found, passing to server: ${assetZip.absolutePath}")
        } else {
            println("No Asset.zip, Assets.zip, or assets.zip found in run/ directory")
        }

        // Start the server process
        val process = ProcessBuilder("java", *javaArgs.toTypedArray())
            .directory(runDir)
            .start()

        // Get PID and save it to a file for later cleanup
        val pid = process.pid()
        val pidFile = File(runDir, ".server.pid")
        pidFile.writeText(pid.toString())
        println("Server started with PID: $pid")
        println("PID saved to: ${pidFile.absolutePath}")

        // Handle graceful shutdown with timeout
        val shutdownHook = Thread {
            if (process.isAlive) {
                println("\nStopping server (PID: $pid)...")
                killProcess(pid)
                process.destroy()
                // Force kill after 2 seconds if still alive
                try {
                    Thread.sleep(2000)
                    if (process.isAlive) {
                        println("Forcefully terminating server...")
                        killProcessForcibly(pid)
                        process.destroyForcibly()
                    }
                } catch (e: InterruptedException) {
                    // Interrupted, force kill immediately
                    if (process.isAlive) {
                        killProcessForcibly(pid)
                        process.destroyForcibly()
                    }
                }
            }
            // Clean up PID file
            pidFile.delete()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Forward stdout to console
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { println(it) }
                }
            } catch (e: Exception) {
                // Stream closed
            }
        }.apply { isDaemon = true; start() }

        // Forward stderr to console
        Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { System.err.println(it) }
                }
            } catch (e: Exception) {
                // Stream closed
            }
        }.apply { isDaemon = true; start() }

        // Forward stdin to server (for commands)
        val stdinThread = Thread {
            try {
                System.`in`.bufferedReader().useLines { lines ->
                    lines.forEach {
                        if (process.isAlive) {
                            try {
                                process.outputStream.write((it + "\n").toByteArray())
                                process.outputStream.flush()
                            } catch (e: Exception) {
                                // Output stream closed
                                return@useLines
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Input stream closed, likely due to shutdown
            }
        }.apply { isDaemon = true; start() }

        // Wait for server to exit
        val exitCode = process.waitFor()
        
        // Remove shutdown hooks since process exited normally
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (e: IllegalStateException) {
            // Shutdown already in progress, ignore
        }
        
        // Interrupt stdin thread if still running
        if (stdinThread.isAlive) {
            stdinThread.interrupt()
        }
        
        // Clean up PID file
        pidFile.delete()
        
        println("Server exited with code $exitCode")
    }

    /**
     * Kill a process by PID (graceful SIGTERM)
     */
    private fun killProcess(pid: Long) {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                // Windows
                ProcessBuilder("taskkill", "/PID", pid.toString()).start()
            } else {
                // Unix-like (macOS, Linux)
                ProcessBuilder("kill", pid.toString()).start()
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    /**
     * Forcefully kill a process by PID (SIGKILL)
     */
    private fun killProcessForcibly(pid: Long) {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                // Windows
                ProcessBuilder("taskkill", "/F", "/PID", pid.toString()).start()
            } else {
                // Unix-like (macOS, Linux)
                ProcessBuilder("kill", "-9", pid.toString()).start()
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }
}

/**
 * Task to stop any running Hytale server processes.
 */
open class StopServerTask : DefaultTask() {
    @TaskAction
    fun stop() {
        val runDir = File(project.projectDir, "run")
        val pidFile = File(runDir, ".server.pid")

        if (!pidFile.exists()) {
            println("No PID file found. Searching for running server processes...")
            // Try to find and kill any Java processes running server.jar
            killServerProcesses(runDir)
            return
        }

        val pid = pidFile.readText().trim().toLongOrNull()
        if (pid == null) {
            println("Invalid PID in file: ${pidFile.absolutePath}")
            pidFile.delete()
            return
        }

        println("Stopping server with PID: $pid")
        
        // Try graceful shutdown first
        if (killProcess(pid)) {
            println("Server stopped successfully")
            pidFile.delete()
        } else {
            // Force kill if graceful didn't work
            println("Graceful shutdown failed, forcing termination...")
            if (killProcessForcibly(pid)) {
                println("Server force-killed")
                pidFile.delete()
            } else {
                println("Failed to kill process. It may have already exited.")
                pidFile.delete()
            }
        }
    }

    /**
     * Kill server processes by finding Java processes running server.jar
     */
    private fun killServerProcesses(runDir: File) {
        try {
            val os = System.getProperty("os.name").lowercase()
            val serverJar = File(runDir, "server.jar")
            
            if (!serverJar.exists()) {
                println("No server.jar found in run directory")
                return
            }

            if (os.contains("win")) {
                // Windows: Find processes using server.jar
                val process = ProcessBuilder("wmic", "process", "where", "commandline like '%server.jar%'", "get", "processid").start()
                val output = process.inputStream.bufferedReader().readText()
                val pids = output.lines()
                    .filter { it.trim().matches(Regex("\\d+")) }
                    .mapNotNull { it.trim().toLongOrNull() }
                
                if (pids.isEmpty()) {
                    println("No running server processes found")
                } else {
                    pids.forEach { pid ->
                        println("Killing process PID: $pid")
                        killProcessForcibly(pid)
                    }
                }
            } else {
                // Unix-like: Use pgrep or ps to find processes
                val process = ProcessBuilder("pgrep", "-f", "server.jar").start()
                val output = process.inputStream.bufferedReader().readText()
                val pids = output.lines()
                    .filter { it.trim().isNotEmpty() }
                    .mapNotNull { it.trim().toLongOrNull() }
                
                if (pids.isEmpty()) {
                    println("No running server processes found")
                } else {
                    pids.forEach { pid ->
                        println("Killing process PID: $pid")
                        killProcessForcibly(pid)
                    }
                }
            }
        } catch (e: Exception) {
            println("Error searching for server processes: ${e.message}")
            println("\nTo manually kill server processes, run:")
            println("  macOS/Linux: ps aux | grep server.jar")
            println("  Then: kill <PID> or kill -9 <PID>")
            println("  Windows: tasklist | findstr java")
            println("  Then: taskkill /PID <PID> /F")
        }
    }

    /**
     * Kill a process by PID (graceful SIGTERM)
     */
    private fun killProcess(pid: Long): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                ProcessBuilder("taskkill", "/PID", pid.toString()).start().waitFor() == 0
            } else {
                ProcessBuilder("kill", pid.toString()).start().waitFor() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Forcefully kill a process by PID (SIGKILL)
     */
    private fun killProcessForcibly(pid: Long): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                ProcessBuilder("taskkill", "/F", "/PID", pid.toString()).start().waitFor() == 0
            } else {
                ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() == 0
            }
        } catch (e: Exception) {
            false
        }
    }
}
