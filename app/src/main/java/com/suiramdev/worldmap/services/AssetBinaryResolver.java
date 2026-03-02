package com.suiramdev.worldmap.services;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.suiramdev.worldmap.models.MissingAssetManifestItem;
import com.suiramdev.worldmap.util.WorldmapLog;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves asset binaries from the Hytale asset system or configured fallbacks.
 */
public class AssetBinaryResolver {

    private final String assetsZipPath;
    private final boolean debugMode;
    private final ClassLoader classLoader;

    public AssetBinaryResolver(String assetsZipPath, boolean debugMode, ClassLoader classLoader) {
        this.assetsZipPath = assetsZipPath != null ? assetsZipPath.trim() : "";
        this.debugMode = debugMode;
        this.classLoader = classLoader != null ? classLoader : AssetBinaryResolver.class.getClassLoader();
    }

    public Optional<byte[]> resolve(MissingAssetManifestItem item) {
        if (item == null) {
            return Optional.empty();
        }

        // Resolution order:
        // 1) AssetRegistry lookup (reflection) -> AssetPack.getRoot() path lookup
        // 2) Assets.zip (configured path)
        // 3) Embedded plugin assets (classpath)
        String path = normalizePath(item.getPath());
        String assetId = item.getAssetId();

        Optional<byte[]> fromRegistry = resolveFromAssetRegistry(assetId, path);
        if (fromRegistry.isPresent()) {
            return fromRegistry;
        }

        Optional<byte[]> fromZip = resolveFromAssetsZip(path);
        if (fromZip.isPresent()) {
            return fromZip;
        }

        Optional<byte[]> fromClasspath = resolveFromClasspath(path);
        if (fromClasspath.isPresent()) {
            return fromClasspath;
        }

        return Optional.empty();
    }

    private Optional<byte[]> resolveFromAssetRegistry(String assetId, String path) {
        if (assetId == null || assetId.isEmpty()) {
            return Optional.empty();
        }

        AssetRegistry registry = getRegistryInstance();
        if (registry == null) {
            return Optional.empty();
        }

        try {
            Object assetOptObj = invokeGetOptional(registry, assetId);
            Optional<?> assetOpt = asOptional(assetOptObj);
            if (assetOpt == null || assetOpt.isEmpty()) {
                return Optional.empty();
            }
            Object asset = assetOpt.get();
            Object pack = invokeNoArg(asset, "getPack");
            if (pack == null) {
                return Optional.empty();
            }
            Object rootObj = invokeNoArg(pack, "getRoot");
            if (!(rootObj instanceof Path)) {
                return Optional.empty();
            }
            Path root = (Path) rootObj;
            if (root == null) {
                return Optional.empty();
            }

            List<Path> candidates = buildCandidatePaths(root, path, assetId);
            for (Path candidate : candidates) {
                if (candidate != null && Files.exists(candidate)) {
                    return Optional.of(Files.readAllBytes(candidate));
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                WorldmapLog.fine("Error resolving asset from registry (%s): %s", assetId, e.getMessage());
            }
        }

        return Optional.empty();
    }

    private Object invokeGetOptional(AssetRegistry registry, String assetId) {
        if (registry == null || assetId == null || assetId.isEmpty()) {
            return null;
        }
        try {
            Method method = registry.getClass().getMethod("getOptional", String.class, Class.class);
            Object jsonAssetWithMapClass = Class.forName("com.hypixel.hytale.assetstore.map.JsonAssetWithMap");
            return method.invoke(registry, assetId, jsonAssetWithMapClass);
        } catch (Exception ignored) {
            // Method shape is runtime/version-dependent.
        }
        try {
            Method method = registry.getClass().getMethod("getOptional", String.class);
            return method.invoke(registry, assetId);
        } catch (Exception ignored) {
            // Method shape is runtime/version-dependent.
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Optional<?> asOptional(Object value) {
        if (value instanceof Optional<?>) {
            return (Optional<?>) value;
        }
        return null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isEmpty()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Optional<byte[]> resolveFromAssetsZip(String path) {
        Path zipPath = resolveAssetsZipPath();
        if (zipPath == null || !Files.exists(zipPath)) {
            return Optional.empty();
        }
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = findZipEntry(zipFile, path);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                byte[] data = inputStream.readAllBytes();
                if (data.length == 0) {
                    return Optional.empty();
                }
                return Optional.of(data);
            }
        } catch (IOException e) {
            if (debugMode) {
                WorldmapLog.fine("Error reading Assets.zip: %s", e.getMessage());
            }
            return Optional.empty();
        }
    }

    private Optional<byte[]> resolveFromClasspath(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        if (!normalized.startsWith("assets/")) {
            candidates.add("assets/" + normalized);
        }

        for (String candidate : candidates) {
            try (InputStream stream = classLoader.getResourceAsStream(candidate)) {
                if (stream != null) {
                    byte[] data = stream.readAllBytes();
                    if (data.length > 0) {
                        return Optional.of(data);
                    }
                }
            } catch (IOException e) {
                if (debugMode) {
                    WorldmapLog.fine("Error reading classpath asset %s: %s", candidate, e.getMessage());
                }
            }
        }

        return Optional.empty();
    }

    private AssetRegistry getRegistryInstance() {
        try {
            Method getMethod = AssetRegistry.class.getMethod("get");
            Object result = getMethod.invoke(null);
            if (result instanceof AssetRegistry) {
                return (AssetRegistry) result;
            }
        } catch (Exception ignored) {
            // Fall through
        }

        try {
            Method getInstanceMethod = AssetRegistry.class.getMethod("getInstance");
            Object result = getInstanceMethod.invoke(null);
            if (result instanceof AssetRegistry) {
                return (AssetRegistry) result;
            }
        } catch (Exception ignored) {
            // Fall through
        }

        return null;
    }

    private Path resolveAssetsZipPath() {
        if (assetsZipPath == null || assetsZipPath.isEmpty()) {
            return null;
        }
        Path path = Paths.get(assetsZipPath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
        }
        return path;
    }

    private ZipEntry findZipEntry(ZipFile zipFile, String path) {
        if (zipFile == null || path == null || path.isEmpty()) {
            return null;
        }
        ZipEntry entry = zipFile.getEntry(path);
        if (entry != null) {
            return entry;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!normalized.equals(path)) {
            entry = zipFile.getEntry(normalized);
            if (entry != null) {
                return entry;
            }
        }
        if (!normalized.startsWith("assets/")) {
            entry = zipFile.getEntry("assets/" + normalized);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private List<Path> buildCandidatePaths(Path root, String path, String assetId) {
        List<Path> candidates = new ArrayList<>();
        if (root == null) {
            return candidates;
        }

        if (path != null && !path.isEmpty()) {
            candidates.add(root.resolve(path));
            candidates.add(root.resolve("assets").resolve(path));
        }

        if (assetId != null && assetId.contains(":")) {
            String[] parts = assetId.split(":", 2);
            String namespace = parts[0];
            String localId = parts[1];
            if (path != null && !path.isEmpty()) {
                candidates.add(root.resolve("assets").resolve(namespace).resolve(path));
            }
            if (localId != null && !localId.isEmpty()) {
                candidates.add(root.resolve("assets").resolve(namespace).resolve(localId));
            }
        }

        return candidates;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }
}
