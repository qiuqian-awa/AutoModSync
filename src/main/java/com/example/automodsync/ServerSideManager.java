package com.example.automodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ServerSideManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORAGE_DIR = FMLPaths.GAMEDIR.get().resolve("automodsync_server_storage");
    private static final Path CHANGELOG_FILE = STORAGE_DIR.resolve("changelog.json");
    private static HttpServer serverInstance;
    private static int currentLatestVersion = 0;

    public static class Changelog { public int latestVersion = 0; public long lastUpdated; }
    public static class FullManifest { public int version; public List<ModInfo> files = new ArrayList<>(); public List<ForcedRule> forcedFiles = new ArrayList<>(); }
    public static class ModInfo {
        public String fileName; public String modId; public String hash; public boolean isServerRequired;
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; ModInfo modInfo = (ModInfo) o; return Objects.equals(modId, modInfo.modId); }
        @Override public int hashCode() { return Objects.hash(modId); }
    }
    public static class PatchPacket {
        public int targetVersion;
        public boolean allowFolderDelete;
        public List<ModInfo> toDownload = new ArrayList<>();
        public List<String> toRemove = new ArrayList<>();
        public List<ForcedRule> forcedActions = new ArrayList<>();
        public List<ModInfo> authorityManifest = new ArrayList<>();
    }
    public static class ForcedRule { public String action; public String source; public String target; public String hash; public ForcedRule(String a, String s, String t, String h) { action=a; source=s; target=t; hash=h; } }

    public static int getCurrentVersion() { return currentLatestVersion; }

    public static void startServerLogic() {
        LOGGER.info("AutoModSync: Initializing HTTP Server Logic (Lazy Start)...");
        Thread serverThread = new Thread(() -> {
            try {
                Thread.sleep(10000);
                if (!Files.exists(STORAGE_DIR)) Files.createDirectories(STORAGE_DIR);
                WarehouseManager.init(STORAGE_DIR);
                ForcedSyncInternal.init();
                performVersionControl();

                int port = ModSyncMain.CONFIG.serverPort.get();
                try { serverInstance = HttpServer.create(new InetSocketAddress(port), 0); }
                catch (IOException e) { LOGGER.error("Failed to bind port " + port, e); return; }

                serverInstance.createContext("/diff", exchange -> {
                    try {
                        int clientVer = 0;
                        String query = exchange.getRequestURI().getQuery();
                        if (query != null && query.contains("ver=")) {
                            String v = query.substring(query.indexOf("ver=") + 4);
                            if (v.contains("&")) v = v.substring(0, v.indexOf("&"));
                            try { clientVer = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                        }
                        PatchPacket packet = calculatePatch(clientVer);
                        String json = GSON.toJson(packet);
                        byte[] response = json.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                    } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
                });

                serverInstance.createContext("/download/", exchange -> {
                    try {
                        String rawUri = exchange.getRequestURI().getRawPath();
                        int idx = rawUri.indexOf("/download/");
                        if (idx == -1) { exchange.sendResponseHeaders(404, -1); return; }

                        String pathPart = rawUri.substring(idx + 10);
                        String filename = URLDecoder.decode(pathPart, StandardCharsets.UTF_8.toString());

                        if (filename.contains("..")) { exchange.sendResponseHeaders(403, -1); return; }

                        LOGGER.debug("AutoModSync Download: Req=" + rawUri + " -> File=" + filename);

                        Path filePath = FMLPaths.MODSDIR.get().resolve(filename);
                        if (!Files.exists(filePath)) filePath = WarehouseManager.getFileInWarehouse(filename);

                        if (filePath != null && Files.exists(filePath)) {
                            exchange.sendResponseHeaders(200, Files.size(filePath));
                            try (OutputStream os = exchange.getResponseBody()) { Files.copy(filePath, os); }
                        } else {
                            LOGGER.warn("AutoModSync: File not found for download: " + filename);
                            exchange.sendResponseHeaders(404, -1);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Download Error", e);
                        exchange.sendResponseHeaders(500, -1);
                    }
                });

                serverInstance.createContext("/forced/", exchange -> {
                    try {
                        String rawUri = exchange.getRequestURI().getRawPath();
                        int idx = rawUri.indexOf("/forced/");
                        if (idx == -1) { exchange.sendResponseHeaders(404, -1); return; }

                        String pathPart = rawUri.substring(idx + 8);
                        String relativePath = URLDecoder.decode(pathPart, StandardCharsets.UTF_8.toString());

                        Path filePath = ForcedSyncInternal.getFile(relativePath);
                        if (filePath != null && Files.isRegularFile(filePath)) {
                            exchange.sendResponseHeaders(200, Files.size(filePath));
                            try (OutputStream os = exchange.getResponseBody()) { Files.copy(filePath, os); }
                        } else { exchange.sendResponseHeaders(404, -1); }
                    } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
                });

                serverInstance.setExecutor(Executors.newFixedThreadPool(2));
                serverInstance.start();
                LOGGER.info("AutoModSync: HTTP Server STARTED on port " + port + ". Version: v" + currentLatestVersion);
            } catch (Exception e) { LOGGER.error("Critical Startup Error", e); }
        }, "ModSync-Server");
        serverThread.setPriority(Thread.MIN_PRIORITY);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public static void stopServer() { if (serverInstance != null) { serverInstance.stop(0); } }

    private static void performVersionControl() throws IOException {
        Changelog log = new Changelog();
        if (Files.exists(CHANGELOG_FILE)) { try (Reader r = Files.newBufferedReader(CHANGELOG_FILE)) { log = GSON.fromJson(r, Changelog.class); } }
        List<ForcedRule> forcedRules = ForcedSyncInternal.generateRules();
        FullManifest currentManifest = new FullManifest();
        currentManifest.files = generateRuntimeManifest(forcedRules);
        currentManifest.forcedFiles = forcedRules;

        FullManifest lastManifest = null;
        if (log.latestVersion > 0) {
            Path p = STORAGE_DIR.resolve("manifest_v" + log.latestVersion + ".json");
            if (Files.exists(p)) { try (Reader r = Files.newBufferedReader(p)) { lastManifest = GSON.fromJson(r, FullManifest.class); } }
        }

        boolean hasChanges = false;
        if (lastManifest == null) { hasChanges = true; }
        else {
            hasChanges = isDifferent(currentManifest.files, lastManifest.files) ||
                    !GSON.toJson(currentManifest.forcedFiles).equals(GSON.toJson(lastManifest.forcedFiles));
        }

        if (hasChanges) {
            currentLatestVersion = log.latestVersion + 1;
            LOGGER.info("Changes detected. Creating Version v" + currentLatestVersion);
            currentManifest.version = currentLatestVersion;
            try (Writer w = Files.newBufferedWriter(STORAGE_DIR.resolve("manifest_v" + currentLatestVersion + ".json"))) { GSON.toJson(currentManifest, w); }
            for (ModInfo info : currentManifest.files) {
                if (info.isServerRequired) {
                    Path p = FMLPaths.MODSDIR.get().resolve(info.fileName);
                    WarehouseManager.backupToWarehouse(p);
                }
            }
            log.latestVersion = currentLatestVersion;
            log.lastUpdated = System.currentTimeMillis();
            try (Writer w = Files.newBufferedWriter(CHANGELOG_FILE)) { GSON.toJson(log, w); }
        } else {
            currentLatestVersion = log.latestVersion;
            LOGGER.info("No changes detected. Active Version: v" + currentLatestVersion);
        }
    }

    private static PatchPacket calculatePatch(int clientVer) {
        PatchPacket packet = new PatchPacket();
        packet.targetVersion = currentLatestVersion;
        packet.allowFolderDelete = ModSyncMain.CONFIG.allowDirectoryDeletion.get();

        FullManifest latest = loadManifest(currentLatestVersion);
        if (latest == null) return packet;
        packet.authorityManifest = latest.files;
        packet.forcedActions = latest.forcedFiles;
        FullManifest client = null;
        if (clientVer > 0) client = loadManifest(clientVer);
        List<ModInfo> clientFiles = (client != null) ? client.files : new ArrayList<>();
        Map<String, ModInfo> clientMap = clientFiles.stream().collect(Collectors.toMap(m -> m.modId, m -> m, (k1, k2) -> k1));

        Set<String> ignoreSet = new HashSet<>();
        if (latest.forcedFiles != null) {
            for (ForcedRule r : latest.forcedFiles) if ("IGNORE".equals(r.action)) ignoreSet.add(r.target);
        }

        for (ModInfo serverMod : latest.files) {
            if (!serverMod.isServerRequired) continue;
            if (ignoreSet.stream().anyMatch(ign -> serverMod.fileName.contains(ign) || serverMod.modId.contains(ign))) continue;

            ModInfo clientMod = clientMap.get(serverMod.modId);
            if (clientMod == null) { packet.toDownload.add(serverMod); }
            else { if (!clientMod.hash.equals(serverMod.hash) || !clientMod.fileName.equals(serverMod.fileName)) packet.toDownload.add(serverMod); }
        }
        Set<String> serverModIds = latest.files.stream().map(m -> m.modId).collect(Collectors.toSet());
        for (ModInfo old : clientFiles) {
            if (!old.isServerRequired) continue;
            if (ignoreSet.stream().anyMatch(ign -> old.fileName.contains(ign) || old.modId.contains(ign))) continue;

            if (!serverModIds.contains(old.modId)) { packet.toRemove.add(old.fileName); }
            else {
                ModInfo newVer = latest.files.stream().filter(m -> m.modId.equals(old.modId)).findFirst().orElse(null);
                if (newVer != null && !newVer.fileName.equals(old.fileName)) { packet.toRemove.add(old.fileName); }
            }
        }
        return packet;
    }

    private static boolean isDifferent(List<ModInfo> current, List<ModInfo> last) {
        if (current.size() != last.size()) return true;
        Set<String> currentHashes = current.stream().filter(m->m.isServerRequired).map(m -> m.modId + m.hash).collect(Collectors.toSet());
        Set<String> lastHashes = last.stream().filter(m->m.isServerRequired).map(m -> m.modId + m.hash).collect(Collectors.toSet());
        return !currentHashes.equals(lastHashes);
    }

    private static FullManifest loadManifest(int ver) {
        Path p = STORAGE_DIR.resolve("manifest_v" + ver + ".json");
        if (!Files.exists(p)) return null;
        try (Reader r = Files.newBufferedReader(p)) { return GSON.fromJson(r, FullManifest.class); } catch (Exception e) { return null; }
    }

    private static List<ModInfo> generateRuntimeManifest(List<ForcedRule> forcedRules) {
        Set<String> deletedMods = new HashSet<>();
        Set<String> ignoredPatterns = new HashSet<>();

        for (ForcedRule rule : forcedRules) {
            if ("DELETE".equals(rule.action) && rule.target.startsWith("mods/")) {
                deletedMods.add(rule.target.replace("mods/", ""));
            }
            if ("IGNORE".equals(rule.action)) {
                ignoredPatterns.add(rule.target);
            }
        }

        List<ModInfo> list = new ArrayList<>();
        // 🟢 初始化绝对路径并标准化
        Path modsDirAbs = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();

        for (IModInfo mod : ModList.get().getMods()) {
            if (mod.getModId().equals("minecraft") || mod.getModId().equals("forge")) continue;
            Path modPath = mod.getOwningFile().getFile().getFilePath();
            if (!Files.isRegularFile(modPath)) continue;

            // 🟢 核心修复：使用 relativize 安全判断目录深度，防止 NPE
            Path modPathAbs = modPath.toAbsolutePath().normalize();
            try {
                // 1. 必须位于 mods 目录下
                if (!modPathAbs.startsWith(modsDirAbs)) continue;

                // 2. 计算相对路径
                Path relative = modsDirAbs.relativize(modPathAbs);

                // 3. 只有当层级为 1 (直接在 mods/ 下) 时才通过
                // 例如: "mod.jar" -> getNameCount=1 (通过)
                //      ".connector/mod.jar" -> getNameCount=2 (跳过)
                if (relative.getNameCount() != 1) {
                    continue;
                }
            } catch (Exception e) {
                // 如果路径解析出错，安全跳过
                continue;
            }

            String fileName = modPath.getFileName().toString();
            if (deletedMods.contains(fileName)) continue;

            String modId = mod.getModId();
            if (ignoredPatterns.stream().anyMatch(p -> fileName.contains(p) || modId.contains(p))) {
                LOGGER.info("AutoModSync: Ignored mod due to rule: " + fileName + " (" + modId + ")");
                continue;
            }

            ModInfo info = new ModInfo();
            info.modId = modId;
            info.fileName = fileName;
            boolean isClientOnly = isClientOnlyMod(mod, modPath);
            info.isServerRequired = !isClientOnly;
            info.hash = calculateHash(modPath.toFile());

            if (list.stream().noneMatch(m -> m.fileName.equals(info.fileName))) list.add(info);
        }
        return list;
    }

    private static boolean isClientOnlyMod(IModInfo mod, Path jarPath) {
        try {
            Optional<? extends ModContainer> container = ModList.get().getModContainerById(mod.getModId());
            if (container.isPresent()) {
                Optional<IExtensionPoint.DisplayTest> testOpt = container.get().getCustomExtension(IExtensionPoint.DisplayTest.class);
                if (testOpt.isPresent()) { if (testOpt.get().remoteVersionTest().test(NetworkConstants.IGNORESERVERONLY, false)) return true; }
            }
        } catch (Exception ignored) {}
        try {
            if (mod.getOwningFile().getFile().getModFileInfo().getConfig().getConfigElement("displayTest").map(o -> o.equals("IGNORE_SERVER_VERSION")).orElse(false)) return true;
        } catch (Exception ignored) {}
        try (ZipFile z = new ZipFile(jarPath.toFile())) {
            ZipEntry t = z.getEntry("META-INF/mods.toml");
            if (t != null) {
                try (InputStream i = z.getInputStream(t)) {
                    String content = new Scanner(i).useDelimiter("\\A").next();
                    if (content.contains("OHNOES") || content.contains("IGNORE_SERVER_VERSION")) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String calculateHash(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[4096]; int n;
            while ((n = fis.read(buffer)) != -1) digest.update(buffer, 0, n);
            StringBuilder sb = new StringBuilder(); for (byte b : digest.digest()) sb.append(String.format("%02x", b)); return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static class ForcedSyncInternal {
        private static final Path ROOT_DIR = STORAGE_DIR.resolve("forced_sync");
        private static final Path DIR_MODS_ADD = ROOT_DIR.resolve("mods_add");
        private static final Path DIR_MODS_REMOVE = ROOT_DIR.resolve("mods_remove");
        private static final Path DIR_CUSTOM = ROOT_DIR.resolve("custom");
        private static final Path CUSTOM_RULES_FILE = DIR_CUSTOM.resolve("rules.json");
        private static final Path CUSTOM_FILES_DIR = DIR_CUSTOM.resolve("files");
        private static final Path README_ROOT = STORAGE_DIR.resolve("存储目录说明.txt");
        private static final Path README_FORCED = ROOT_DIR.resolve("强制同步使用说明.txt");
        private static class CustomRuleEntry { String action; String file; String path; boolean isDir; String value; }

        public static void init() {
            try {
                if (!Files.exists(ROOT_DIR)) Files.createDirectories(ROOT_DIR);
                if (!Files.exists(DIR_MODS_ADD)) Files.createDirectories(DIR_MODS_ADD);
                if (!Files.exists(DIR_MODS_REMOVE)) Files.createDirectories(DIR_MODS_REMOVE);
                if (!Files.exists(DIR_CUSTOM)) Files.createDirectories(DIR_CUSTOM);
                if (!Files.exists(CUSTOM_FILES_DIR)) Files.createDirectories(CUSTOM_FILES_DIR);

                if (!Files.exists(README_ROOT)) {
                    String text = "=== AutoModSync 服务器存储目录说明 ===\n" +
                            "1. manifest_vX.json: 自动生成的版本全量清单 (请勿手动修改)。\n" +
                            "2. changelog.json: 记录当前最新版本号 (请勿手动修改)。\n" +
                            "3. warehouse/: 模组备份仓库，用于客户端回滚。\n" +
                            "4. forced_sync/: [管理员操作区] 用于强制分发文件或删除客户端文件。\n";
                    Files.writeString(README_ROOT, text, StandardCharsets.UTF_8);
                }

                if (!Files.exists(README_FORCED) || true) {
                    String text = "=== AutoModSync 强制同步使用说明 ===\n\n" +
                            "[傻瓜式模组管理]\n" +
                            "1. mods_add/ 文件夹: 放入 .jar，客户端自动下载覆盖。\n" +
                            "2. mods_remove/ 文件夹: 放入空文件(文件名对应)，客户端自动删除。\n\n" +
                            "[高级自定义同步 (custom/rules.json)]\n" +
                            "警告：修改此处需要一定的 JSON 基础。\n\n" +
                            "=== rules.json 参数详解 ===\n" +
                            "\"action\": \"ADD\",     # 执行规则：下载\n" +
                            "\"file\": \"xxx\",       # 下载源文件名称 (位于 custom/files/ 下)\n" +
                            "\"path\": \"xxx\",       # 客户端目标路径 (如 config/xxx)\n" +
                            "\"isDir\": false,      # 是否为文件夹 (默认 false)\n\n" +
                            "\"action\": \"DELETE\",  # 执行规则：删除\n" +
                            "\"path\": \"xxx\",       # 客户端目标路径\n" +
                            "\"isDir\": true,       # 删除文件夹时必须为 true\n\n" +
                            "\"action\": \"IGNORE\",  # 执行规则：白名单忽略 (服务端不检查此模组)\n" +
                            "\"value\": \"xxx\"       # 模组ID或文件名片段 (如 tritium)\n\n" +
                            "=== rules.json 编写示例 (请复制下方内容使用) ===\n" +
                            "[\n" +
                            "  {\n" +
                            "    \"action\": \"ADD\",\n" +
                            "    \"file\": \"my-folder\",\n" +
                            "    \"path\": \"config/my-folder\"\n" +
                            "  },\n" +
                            "  {\n" +
                            "    \"action\": \"DELETE\",\n" +
                            "    \"path\": \"config/old-folder\",\n" +
                            "    \"isDir\": true\n" +
                            "  },\n" +
                            "  {\n" +
                            "    \"action\": \"IGNORE\",\n" +
                            "    \"value\": \"tritium\"\n" +
                            "  }\n" +
                            "]\n\n" +
                            "===========================================================\n" +
                            "【免责声明】\n" +
                            "1. 默认情况下 allowDirectoryDeletion 为 false，目录删除指令无效。\n" +
                            "2. 开启该选项即代表您愿意承担误配置导致文件丢失的责任。\n" +
                            "===========================================================\n";
                    Files.writeString(README_FORCED, text, StandardCharsets.UTF_8);
                }

                if (!Files.exists(CUSTOM_RULES_FILE)) {
                    try (Writer w = Files.newBufferedWriter(CUSTOM_RULES_FILE)) { w.write("[]"); }
                }
            } catch (IOException e) { e.printStackTrace(); }
        }

        public static List<ForcedRule> generateRules() {
            List<ForcedRule> rules = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR_MODS_ADD)) {
                for (Path p : stream) if (Files.isRegularFile(p)) rules.add(new ForcedRule("ADD", "mods_add/" + p.getFileName(), "mods/" + p.getFileName(), calculateHash(p.toFile())));
            } catch (IOException ignored) {}
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR_MODS_REMOVE)) {
                for (Path p : stream) if (Files.isRegularFile(p)) rules.add(new ForcedRule("DELETE", null, "mods/" + p.getFileName(), null));
            } catch (IOException ignored) {}
            if (Files.exists(CUSTOM_RULES_FILE)) {
                try (Reader r = Files.newBufferedReader(CUSTOM_RULES_FILE)) {
                    List<CustomRuleEntry> entries = GSON.fromJson(r, new TypeToken<List<CustomRuleEntry>>(){}.getType());
                    if (entries != null) {
                        for (CustomRuleEntry entry : entries) {
                            if ("IGNORE".equalsIgnoreCase(entry.action) && entry.value != null && !entry.value.isEmpty()) {
                                rules.add(new ForcedRule("IGNORE", null, entry.value, null));
                                continue;
                            }

                            if (entry.path == null || entry.path.contains("..")) continue;

                            if ("DELETE".equalsIgnoreCase(entry.action)) {
                                if (entry.isDir) {
                                    if (ModSyncMain.CONFIG.allowDirectoryDeletion.get()) {
                                        rules.add(new ForcedRule("DELETE", null, entry.path, null));
                                    } else {
                                        LOGGER.warn("AutoModSync: Skipped DELETE rule for directory '" + entry.path + "' (Config disallowed)");
                                    }
                                } else {
                                    rules.add(new ForcedRule("DELETE", null, entry.path, null));
                                }
                            } else if ("ADD".equalsIgnoreCase(entry.action) && entry.file != null) {
                                Path src = CUSTOM_FILES_DIR.resolve(entry.file);
                                if (Files.exists(src)) {
                                    if (Files.isDirectory(src)) {
                                        addDirectoryRules(rules, src, entry.file, entry.path);
                                    } else {
                                        rules.add(new ForcedRule("ADD", "custom/files/" + entry.file, entry.path, calculateHash(src.toFile())));
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            return rules;
        }

        private static void addDirectoryRules(List<ForcedRule> rules, Path rootSrc, String rootSrcRel, String rootTargetRel) {
            try {
                Files.walkFileTree(rootSrc, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relative = rootSrc.relativize(file);
                        String sourcePath = "custom/files/" + rootSrcRel + "/" + relative.toString().replace("\\", "/");
                        String targetPath = rootTargetRel + "/" + relative.toString().replace("\\", "/");
                        rules.add(new ForcedRule("ADD", sourcePath, targetPath, calculateHash(file.toFile())));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) { e.printStackTrace(); }
        }

        public static Path getFile(String relativePath) {
            if (relativePath.contains("..")) return null;
            Path p = ROOT_DIR.resolve(relativePath);
            return Files.isRegularFile(p) ? p : null;
        }
    }
}
