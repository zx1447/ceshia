package com.example.nodeapprunner;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NodeAppRunner extends JavaPlugin {

    private Process nodeProcess;
    private Process cloudflaredProcess;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Path workDir;

    // ================= 跨平台核心配置 =================
    private static final String NODE_VERSION = "v22.12.0";
    private static final String APP_REPO_URL = "https://github.com/zx1447/indexaoyoumc/archive/refs/heads/main.zip";
    private static final int INTERNAL_PORT = 4237;

    // 跨平台检测
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String OS_ARCH = System.getProperty("os.arch").equals("amd64") || System.getProperty("os.arch").equals("x86_64") ? "x64" : "arm64";

    // ================= 动态 URL 生成 =================
    private String getNodeDownloadUrl() {
        if (IS_WINDOWS) {
            return "https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-win-" + OS_ARCH + ".zip";
        } else {
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
    private String getCfExeName() { return IS_WINDOWS ? "cloudflared.exe" : "cloudflared"; }

    @Override
    public void onEnable() {
        getLogger().info("NodeAppRunner 插件启动，当前系统: " + (IS_WINDOWS ? "Windows" : "Linux") + " " + OS_ARCH);

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
    }

    @Override
    public void onDisable() {
        stopProcesses();
    }

    private void stopProcesses() {
        getLogger().info("正在停止所有子进程...");
        
        // 修复 Windows 进程树锁定问题
        if (nodeProcess != null && nodeProcess.isAlive()) {
            if (IS_WINDOWS) {
                try { Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/T", "/PID", String.valueOf(nodeProcess.pid())}).waitFor(); } catch (Exception ignored) {}
            } else {
                nodeProcess.destroyForcibly();
            }
        }
        
        if (cloudflaredProcess != null && cloudflaredProcess.isAlive()) {
            if (IS_WINDOWS) {
                try { Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/T", "/PID", String.valueOf(cloudflaredProcess.pid())}).waitFor(); } catch (Exception ignored) {}
            } else {
                cloudflaredProcess.destroyForcibly();
            }
        }
        
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
                runSystemCommand("tar", "-xJf", archiveFile.toString(), "-C", workDir.toString());
                moveSubDirectory(workDir, "node-", nodeDir);
                runSystemCommand("chmod", "-R", "+x", nodeDir.resolve("bin").toString());
            }
            Files.deleteIfExists(archiveFile);
        } else {
            getLogger().info("Node.js 已存在，跳过安装。");
        }

        // 2. 下载项目代码
        getLogger().info("正在下载项目代码...");
        Path appZip = workDir.resolve("app.zip");
        downloadFile(APP_REPO_URL, appZip);
        deleteDirectory(appDir);
        unzip(appZip, workDir);
        moveSubDirectory(workDir, "indexaoyoumc", appDir);
        Files.deleteIfExists(appZip);

        // 3. NPM Install (修复 Windows 环境)
        getLogger().info("正在执行 npm install...");
        Path npmCliPath = nodeDir.resolve(IS_WINDOWS ? "node_modules/npm/bin/npm-cli.js" : "lib/node_modules/npm/bin/npm-cli.js");
        if (Files.exists(npmCliPath)) {
            if (IS_WINDOWS) {
                // Windows 必须通过 cmd 运行，以正确解析 .cmd 脚本和处理环境变量
                Path npmCmd = nodeDir.resolve("npm.cmd");
                if (Files.exists(npmCmd)) {
                    runCommand(appDir, "cmd", "/c", "\"" + npmCmd.toString() + "\"", "install");
                } else {
                    runCommand(appDir, "cmd", "/c", "\"" + nodeExePath.toString() + "\"", "\"" + npmCliPath.toString() + "\"", "install");
                }
            } else {
                runCommand(appDir, nodeExePath.toString(), npmCliPath.toString(), "install");
            }
        } else {
            getLogger().warning("未找到 npm-cli.js，跳过 npm install。");
        }

        // 4. 启动 Node 应用 (修复路径包含空格/中文的问题)
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

        // 7. 启动守护线程 (仅重启进程，不拦截服务器关闭)
        startDaemon(nodeExePath.toString(), appDir, cfExe.toString());
    }

    // ================= 进程管理 =================
    private void startNodeApp(String nodeExe, Path appDir) throws IOException {
        Path indexPath = appDir.resolve("index.js");
        if (!Files.exists(indexPath)) throw new FileNotFoundException("index.js 不存在: " + indexPath);

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            // Windows 下加引号防止空格/中文路径报错
            pb = new ProcessBuilder("cmd", "/c", "\"" + nodeExe + "\"", "index.js");
        } else {
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
            pb = new ProcessBuilder("cmd", "/c", "\"" + cfExe + "\"", "tunnel", "--url", "http://localhost:" + INTERNAL_PORT, "--no-autoupdate");
        } else {
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
            while (true) { // 守护进程随主进程消亡而消亡
                try {
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        getLogger().warning("Node.js 进程已退出，5秒后重启...");
                        Thread.sleep(5000);
                        startNodeApp(nodeExe, appDir);
                    }

                    if (cloudflaredProcess != null && !cloudflaredProcess.isAlive()) {
                        getLogger().warning("Cloudflared 进程已退出，5秒后重启...");
                        Thread.sleep(5000);
                        startCloudflared(cfExe);
                    }
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    getLogger().info("守护线程被中断，退出...");
                    break;
                } catch (Exception e) {
                    getLogger().severe("守护线程异常: " + e.getMessage());
                }
            }
        }, "NodeApp-Daemon");
        daemon.setDaemon(true); // 设为守护线程，服务器关闭时它自动结束
        daemon.start();
    }

    // ================= 通用工具方法 =================
    private void downloadFile(String url, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
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

    private void runCommand(Path workingDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
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
            throw new RuntimeException("命令执行失败，退出码: " + exitCode + " 命令: " + String.join(" ", command));
        }
    }

    private void runSystemCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream is = p.getInputStream()) {
            byte[] buffer = new byte[4096];
            while (is.read(buffer) != -1) {}
        }
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
        // 修复原版排序错误，使用标准深度优先排序删除
        Files.walk(path)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try { Files.delete(p); } catch (IOException ignored) {}
             });
    }
}
