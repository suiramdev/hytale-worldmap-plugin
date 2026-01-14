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
        val pluginsDir = File(runDir, "plugins").apply { mkdirs() }
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

        // Copy plugin JAR to plugins folder
        project.tasks.findByName("shadowJar")?.outputs?.files?.firstOrNull()?.let { shadowJar ->
            val targetFile = File(pluginsDir, shadowJar.name)
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

        // Handle graceful shutdown
        val shutdownHook = Thread {
            if (process.isAlive) {
                println("\nStopping server...")
                process.destroy()
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Forward stdout to console
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { println(it) }
            }
        }.start()

        // Forward stderr to console
        Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { System.err.println(it) }
            }
        }.start()

        // Forward stdin to server (for commands)
        Thread {
            System.`in`.bufferedReader().useLines { lines ->
                lines.forEach {
                    process.outputStream.write((it + "\n").toByteArray())
                    process.outputStream.flush()
                }
            }
        }.start()

        // Wait for server to exit
        val exitCode = process.waitFor()
        
        // Remove shutdown hook since process exited normally
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (e: IllegalStateException) {
            // Shutdown already in progress, ignore
        }
        
        println("Server exited with code $exitCode")
    }
}
