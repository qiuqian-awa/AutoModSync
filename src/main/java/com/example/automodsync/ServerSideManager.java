package com.example.automodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.level.ServerPlayer;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ServerSideManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORAGE_DIR = FMLPaths.GAMEDIR.get().resolve("automodsync_server_storage");
    private static final Path CHANGELOG_FILE = STORAGE_DIR.resolve("changelog.json");
    private static final Path MANIFEST_DIR = STORAGE_DIR.resolve("manifests");
    private static final Path WAREHOUSE_METADATA_DIR = STORAGE_DIR.resolve("warehouse").resolve("metadata");
    private static final Path DOWNLOAD_SOURCES_FILE = WAREHOUSE_METADATA_DIR.resolve("download_sources.json");
    private static final Path VERIFIED_REQUIREMENTS_FILE = WAREHOUSE_METADATA_DIR.resolve("verified_requirements.json");
    private static final Path VERIFIED_CLIENT_ARTIFACTS_FILE = WAREHOUSE_METADATA_DIR.resolve("verified_client_artifacts.json");
    private static final Path ADMIN_UPLOAD_DIR = STORAGE_DIR.resolve("temp").resolve("admin_uploads");
    private static final int MAX_UPLOAD_CHUNK_BYTES = 30 * 1024;
    private static final long MAX_ADMIN_ARTIFACT_BYTES = 512L * 1024L * 1024L;
    private static HttpServer serverInstance;
    private static ExecutorService serverExecutor;
    private static int currentLatestVersion = 0;
    private static int currentLatestVerifiedVersion = 0;
    private static int currentRevision = 0;
    private static VerificationStatus currentVerificationStatus = VerificationStatus.UNVERIFIED;
    private static Map<String, DownloadSources> downloadSources = new HashMap<>();
    private static Map<String, ModRequirementDetector.Requirement> verifiedRequirements = new HashMap<>();
    private static Map<String, ModInfo> verifiedClientArtifacts = new LinkedHashMap<>();
    private static final Map<UUID, AdminUploadSession> activeAdminUploads = new HashMap<>();
    private static final Map<UUID, Map<String, ModInfo>> completedAdminUploads = new HashMap<>();

    public enum VerificationStatus { UNVERIFIED, VERIFIED, SUPERSEDED }
    public static class Changelog { public int latestVersion = 0; public int latestVerifiedVersion = 0; public long lastUpdated; }
    public static class FullManifest {
        public int version;
        public int revision;
        public VerificationStatus verificationStatus;
        public String manifestHash;
        public List<ModInfo> files = new ArrayList<>();
        public List<ForcedRule> forcedFiles = new ArrayList<>();
        @SerializedName(value = "verifiedModRemovals", alternate = {"clientRemovals"})
        public List<String> verifiedModRemovals = new ArrayList<>();
    }
    public static class DownloadSources {
        public String curseForgeUrl;
        public String modrinthUrl;
    }
    public static class ModInfo {
        public String fileName;
        public String modId;
        public List<String> modIds = new ArrayList<>();
        public String hash;
        public long size;
        @SerializedName(value = "clientRequired", alternate = {"isServerRequired"})
        public boolean isServerRequired;
        public ModRequirementDetector.Requirement requirement = ModRequirementDetector.Requirement.UNKNOWN;
        public String requirementEvidence;
        public String curseForgeUrl;
        public String modrinthUrl;
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; ModInfo modInfo = (ModInfo) o; return Objects.equals(modId, modInfo.modId); }
        @Override public int hashCode() { return Objects.hash(modId); }
    }
    public static class PatchPacket {
        public int targetVersion;
        public int targetRevision;
        public VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;
        public boolean allowFolderDelete;
        public List<ModInfo> toDownload = new ArrayList<>();
        public List<String> toRemove = new ArrayList<>();
        public List<ForcedRule> forcedActions = new ArrayList<>();
        public List<ModInfo> authorityManifest = new ArrayList<>();
    }
    public static class ForcedRule {
        public String action;
        public String source;
        public String target;
        public String hash;
        public String id;
        public String kind;
        public int changedVersion;
        public long size;
        public ForcedRule(String a, String s, String t, String h) { action=a; source=s; target=t; hash=h; }
    }
    public static class AdminModState {
        public String modId;
        public String fileName;
        public String hash;
        public String previousHash;
        public boolean baselineKnown;
        public boolean administratorAddedClientArtifact;
        public boolean administratorRemovedClientArtifact;
        public AdminModState(String modId, String fileName, String hash, String previousHash,
                             boolean baselineKnown, boolean administratorAddedClientArtifact,
                             boolean administratorRemovedClientArtifact) {
            this.modId = modId; this.fileName = fileName; this.hash = hash;
            this.previousHash = previousHash; this.baselineKnown = baselineKnown;
            this.administratorAddedClientArtifact = administratorAddedClientArtifact;
            this.administratorRemovedClientArtifact = administratorRemovedClientArtifact;
        }
        public boolean isPresent() {
            return hash != null && !hash.isEmpty();
        }
    }
    private static class AdminUploadSession {
        int candidateVersion;
        String fileName;
        String hash;
        long expectedSize;
        long receivedSize;
        List<String> modIds;
        Path partFile;
    }

    public static int getCurrentVersion() { return currentLatestVersion; }
    public static int getCurrentRevision() { return currentRevision; }
    public static VerificationStatus getCurrentVerificationStatus() { return currentVerificationStatus; }
    public static synchronized String getCurrentManifestHash() {
        FullManifest manifest = loadManifest(currentLatestVersion);
        return manifest == null || manifest.manifestHash == null ? "" : manifest.manifestHash;
    }
    public static boolean hasPendingVerification() { return currentVerificationStatus == VerificationStatus.UNVERIFIED; }
    public static boolean isVerificationAdministrator(ServerPlayer player) {
        return player != null && player.getServer() != null
                && (player.getServer().getPlayerList().isOp(player.getGameProfile()) || player.hasPermissions(4));
    }

    public static void startServerLogic() {
        LOGGER.info("AutoModSync: Initializing HTTP Server Logic (Lazy Start)...");
        Thread serverThread = new Thread(() -> {
            try {
                if (!Files.exists(STORAGE_DIR)) Files.createDirectories(STORAGE_DIR);
                WarehouseManager.init(STORAGE_DIR);
                migrateStorageLayout();
                cleanupStaleAdminUploads();
                CustomSyncInternal.init();
                loadDownloadSources();
                migrateManifestDownloadSources();
                loadVerifiedRequirements();
                loadVerifiedClientArtifacts();
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
                        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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

                        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) { exchange.sendResponseHeaders(403, -1); return; }

                        LOGGER.debug("AutoModSync Download: Req=" + rawUri + " -> File=" + filename);

                        Path filePath;
                        if (filename.matches("(?i)[0-9a-f]{40}(?:\\.jar)?")) {
                            String sha1 = filename.toLowerCase(Locale.ROOT).replace(".jar", "");
                            filePath = WarehouseManager.getFileByHash(sha1);
                        } else {
                            Path modsRoot = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
                            filePath = modsRoot.resolve(filename).normalize();
                            if (!filePath.startsWith(modsRoot) || !Files.isRegularFile(filePath)) {
                                filePath = WarehouseManager.getFileInWarehouse(filename);
                            }
                        }

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

                int workerThreads = ModSyncMain.CONFIG.httpWorkerThreads.get();
                serverExecutor = Executors.newFixedThreadPool(workerThreads, task -> {
                    Thread thread = new Thread(task, "AutoModSync-HTTP");
                    thread.setDaemon(true);
                    return thread;
                });
                serverInstance.setExecutor(serverExecutor);
                serverInstance.start();
                LOGGER.info("AutoModSync: HTTP Server STARTED on port {} with {} worker threads. Version: v{}",
                        port, workerThreads, currentLatestVersion);
            } catch (Exception e) { LOGGER.error("Critical Startup Error", e); }
        }, "ModSync-Server");
        serverThread.setPriority(Thread.MIN_PRIORITY);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public static synchronized void stopServer() {
        if (serverInstance != null) {
            serverInstance.stop(0);
            serverInstance = null;
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
    }

    private static void migrateStorageLayout() throws IOException {
        Files.createDirectories(MANIFEST_DIR);
        Files.createDirectories(WAREHOUSE_METADATA_DIR);
        Files.createDirectories(ADMIN_UPLOAD_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(STORAGE_DIR, "manifest_v*.json")) {
            for (Path legacy : stream) {
                Path target = MANIFEST_DIR.resolve(legacy.getFileName());
                if (!Files.exists(target)) Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        migrateMetadataFile("download_sources.json", DOWNLOAD_SOURCES_FILE);
        migrateMetadataFile("verified_requirements.json", VERIFIED_REQUIREMENTS_FILE);
        migrateMetadataFile("verified_client_artifacts.json", VERIFIED_CLIENT_ARTIFACTS_FILE);
    }

    private static void migrateMetadataFile(String legacyName, Path target) throws IOException {
        Path legacy = STORAGE_DIR.resolve(legacyName);
        if (!Files.isRegularFile(target) && Files.isRegularFile(legacy)) {
            Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void cleanupStaleAdminUploads() throws IOException {
        Files.createDirectories(ADMIN_UPLOAD_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ADMIN_UPLOAD_DIR, "*.part")) {
            for (Path part : stream) if (Files.isRegularFile(part)) Files.deleteIfExists(part);
        }
        activeAdminUploads.clear();
        completedAdminUploads.clear();
    }

    private static void performVersionControl() throws IOException {
        Changelog log = new Changelog();
        if (Files.exists(CHANGELOG_FILE)) { try (Reader r = Files.newBufferedReader(CHANGELOG_FILE)) { log = GSON.fromJson(r, Changelog.class); } }
        // The first manifest is the installation baseline. Requiring administrator
        // verification here can mistake artifacts from an unrelated client snapshot
        // for newly-added client mods and upload them into a fresh server repository.
        // Existing installations that have an unverified v1 are migrated as well.
        if (log.latestVersion == 1 && log.latestVerifiedVersion == 0) {
            FullManifest initial = loadManifest(1);
            if (initial != null && initial.verificationStatus == VerificationStatus.UNVERIFIED) {
                initial.verificationStatus = VerificationStatus.VERIFIED;
                initial.manifestHash = calculateManifestHash(initial);
                saveManifest(initial);
                log.latestVerifiedVersion = 1;
                log.lastUpdated = System.currentTimeMillis();
                try (Writer writer = Files.newBufferedWriter(CHANGELOG_FILE)) { GSON.toJson(log, writer); }
                LOGGER.info("AutoModSync: Promoted initial baseline v1 to VERIFIED");
            }
        }
        if (log.latestVerifiedVersion == 0 && log.latestVersion > 0) {
            FullManifest legacy = loadManifest(log.latestVersion);
            if (legacy != null && (legacy.verificationStatus == null || legacy.verificationStatus == VerificationStatus.VERIFIED)) {
                log.latestVerifiedVersion = log.latestVersion;
            }
        }
        currentLatestVerifiedVersion = log.latestVerifiedVersion;
        FullManifest lastManifest = null;
        if (log.latestVersion > 0) {
            Path p = MANIFEST_DIR.resolve("manifest_v" + log.latestVersion + ".json");
            if (Files.exists(p)) { try (Reader r = Files.newBufferedReader(p)) { lastManifest = GSON.fromJson(r, FullManifest.class); } }
        }
        currentLatestVersion = log.latestVersion;
        List<ForcedRule> forcedRules = CustomSyncInternal.generateRules(
                lastManifest == null ? Collections.emptyList() : lastManifest.forcedFiles,
                log.latestVersion + 1);
        FullManifest currentManifest = new FullManifest();
        currentManifest.files = generateRuntimeManifest(forcedRules);
        currentManifest.forcedFiles = forcedRules;
        if (lastManifest != null && lastManifest.verifiedModRemovals != null) {
            currentManifest.verifiedModRemovals = new ArrayList<>(lastManifest.verifiedModRemovals);
        }
        // A filename that is authoritative again must no longer be deleted by a
        // historical tombstone. SHA-1 validation proves artifact contents; the
        // tombstone is a separate filename-level instruction.
        Set<String> currentlyRetainedClientFiles = currentManifest.files.stream()
                .filter(info -> info != null && info.fileName != null
                        && (info.isServerRequired
                        || info.requirement == ModRequirementDetector.Requirement.OPTIONAL))
                .map(info -> info.fileName).collect(Collectors.toSet());
        currentManifest.verifiedModRemovals.removeIf(currentlyRetainedClientFiles::contains);

        boolean hasChanges = false;
        if (lastManifest == null) { hasChanges = true; }
        else {
            hasChanges = isDifferent(currentManifest.files, lastManifest.files) ||
                    !GSON.toJson(currentManifest.forcedFiles).equals(GSON.toJson(lastManifest.forcedFiles)) ||
                    !Objects.equals(currentManifest.verifiedModRemovals, lastManifest.verifiedModRemovals);
        }

        if (hasChanges) {
            currentLatestVersion = log.latestVersion + 1;
            LOGGER.info("Changes detected. Creating Version v" + currentLatestVersion);
            currentManifest.version = currentLatestVersion;
            currentManifest.revision = 0;
            boolean initialBaseline = log.latestVersion == 0;
            currentManifest.verificationStatus = initialBaseline
                    ? VerificationStatus.VERIFIED : VerificationStatus.UNVERIFIED;
            currentManifest.manifestHash = calculateManifestHash(currentManifest);
            currentRevision = currentManifest.revision;
            currentVerificationStatus = currentManifest.verificationStatus;
            saveManifest(currentManifest);
            for (ModInfo info : currentManifest.files) {
                if (info.isServerRequired) {
                    Path p = FMLPaths.MODSDIR.get().resolve(info.fileName);
                    WarehouseManager.backupToWarehouse(p);
                }
            }
            log.latestVersion = currentLatestVersion;
            if (initialBaseline) {
                log.latestVerifiedVersion = currentLatestVersion;
                currentLatestVerifiedVersion = currentLatestVersion;
                LOGGER.info("AutoModSync: Initial baseline v{} is trusted as VERIFIED", currentLatestVersion);
            }
            log.lastUpdated = System.currentTimeMillis();
            try (Writer w = Files.newBufferedWriter(CHANGELOG_FILE)) { GSON.toJson(log, w); }
            CustomSyncInternal.commitInbox(currentLatestVersion, true);
        } else {
            currentLatestVersion = log.latestVersion;
            if (lastManifest != null) {
                currentRevision = lastManifest.revision;
                currentVerificationStatus = lastManifest.verificationStatus == null
                        ? VerificationStatus.VERIFIED : lastManifest.verificationStatus;
            }
            LOGGER.info("No changes detected. Active Version: v" + currentLatestVersion);
            CustomSyncInternal.commitInbox(currentLatestVersion, false);
        }
    }

    private static PatchPacket calculatePatch(int clientVer) {
        PatchPacket packet = new PatchPacket();
        packet.targetVersion = currentLatestVersion;
        packet.targetRevision = currentRevision;
        packet.verificationStatus = currentVerificationStatus;
        packet.allowFolderDelete = ModSyncMain.CONFIG.allowDirectoryDeletion.get();

        FullManifest latest = loadManifest(currentLatestVersion);
        if (latest == null) return packet;
        List<ModInfo> latestFiles = withDownloadSources(latest.files);
        packet.authorityManifest = latestFiles;
        packet.forcedActions = latest.forcedFiles;
        if (latest.verifiedModRemovals != null) packet.toRemove.addAll(latest.verifiedModRemovals);
        FullManifest client = null;
        if (clientVer > 0) client = loadManifest(clientVer);
        List<ModInfo> clientFiles = (client != null) ? client.files : new ArrayList<>();
        Map<String, ModInfo> clientMap = clientFiles.stream().collect(Collectors.toMap(m -> m.modId, m -> m, (k1, k2) -> k1));

        Set<String> ignoreSet = new HashSet<>();
        if (latest.forcedFiles != null) {
            for (ForcedRule r : latest.forcedFiles) if ("IGNORE".equals(r.action)) ignoreSet.add(r.target);
        }

        for (ModInfo serverMod : latestFiles) {
            if (!serverMod.isServerRequired) continue;
            if (ignoreSet.stream().anyMatch(ign -> serverMod.fileName.contains(ign) || serverMod.modId.contains(ign))) continue;

            ModInfo clientMod = clientMap.get(serverMod.modId);
            if (clientMod == null) { packet.toDownload.add(serverMod); }
            else { if (!clientMod.hash.equals(serverMod.hash) || !clientMod.fileName.equals(serverMod.fileName)) packet.toDownload.add(serverMod); }
        }
        Set<String> serverModIds = latestFiles.stream().map(m -> m.modId).collect(Collectors.toSet());
        for (ModInfo old : clientFiles) {
            if (!old.isServerRequired) continue;
            if (ignoreSet.stream().anyMatch(ign -> old.fileName.contains(ign) || old.modId.contains(ign))) continue;

            if (!serverModIds.contains(old.modId)) { if (!packet.toRemove.contains(old.fileName)) packet.toRemove.add(old.fileName); }
            else {
                ModInfo newVer = latestFiles.stream().filter(m -> m.modId.equals(old.modId)).findFirst().orElse(null);
                if (newVer != null && !newVer.fileName.equals(old.fileName) && !packet.toRemove.contains(old.fileName)) { packet.toRemove.add(old.fileName); }
            }
        }
        return packet;
    }

    private static boolean isDifferent(List<ModInfo> current, List<ModInfo> last) {
        if (current.size() != last.size()) return true;
        Set<String> currentHashes = current.stream().map(ServerSideManager::manifestIdentity).collect(Collectors.toSet());
        Set<String> lastHashes = last.stream().map(ServerSideManager::manifestIdentity).collect(Collectors.toSet());
        return !currentHashes.equals(lastHashes);
    }

    private static String manifestIdentity(ModInfo mod) {
        List<String> ids = mod.modIds == null || mod.modIds.isEmpty()
                ? Collections.singletonList(mod.modId) : new ArrayList<>(mod.modIds);
        ids.sort(Comparator.nullsFirst(String::compareTo));
        return String.join(",", ids) + "|" + mod.fileName + "|" + mod.hash + "|" + mod.isServerRequired;
    }

    private static FullManifest loadManifest(int ver) {
        Path p = MANIFEST_DIR.resolve("manifest_v" + ver + ".json");
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

        Map<String, ModInfo> byFileName = new LinkedHashMap<>();
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

            ModRequirementDetector.Result detection = ModRequirementDetector.detect(mod, modPath);
            ModInfo info = byFileName.get(fileName);
            if (info == null) {
                info = new ModInfo();
                info.modId = modId;
                info.fileName = fileName;
                info.hash = calculateHash(modPath.toFile());
                try { info.size = Files.size(modPath); } catch (IOException ignored) {}
                ModRequirementDetector.Requirement verifiedRequirement = verifiedRequirements.get(info.hash);
                if (verifiedRequirement != null) {
                    info.requirement = verifiedRequirement;
                    info.requirementEvidence = "administrator verification for this SHA-1";
                    info.isServerRequired = verifiedRequirement == ModRequirementDetector.Requirement.REQUIRED
                            || verifiedRequirement == ModRequirementDetector.Requirement.UNKNOWN;
                } else {
                    info.requirement = detection.requirement;
                    info.requirementEvidence = detection.evidence;
                    info.isServerRequired = detection.isClientRequired();
                }
                WarehouseManager.store(modPath);
                byFileName.put(fileName, info);
            } else if (!verifiedRequirements.containsKey(info.hash)
                    && detection.isClientRequired() && !info.isServerRequired) {
                info.requirement = ModRequirementDetector.Requirement.REQUIRED;
                info.requirementEvidence = "one or more mod ids in this JAR require the client";
                info.isServerRequired = true;
            }
            if (!info.modIds.contains(modId)) info.modIds.add(modId);
        }
        for (ModInfo persisted : verifiedClientArtifacts.values()) {
            if (persisted == null || persisted.hash == null || persisted.fileName == null
                    || WarehouseManager.getFileByHash(persisted.hash) == null) continue;
            boolean hashAlreadyPresent = byFileName.values().stream()
                    .anyMatch(existing -> Objects.equals(existing.hash, persisted.hash));
            if (hashAlreadyPresent || byFileName.containsKey(persisted.fileName)) continue;
            ModInfo copy = GSON.fromJson(GSON.toJson(persisted), ModInfo.class);
            ModRequirementDetector.Requirement persistedRequirement =
                    verifiedRequirements.getOrDefault(copy.hash, copy.requirement);
            if (persistedRequirement == null) persistedRequirement = ModRequirementDetector.Requirement.REQUIRED;
            copy.requirement = persistedRequirement;
            copy.requirementEvidence = persistedRequirement == ModRequirementDetector.Requirement.OPTIONAL
                    ? "administrator retained compatible client artifact"
                    : "administrator-uploaded client artifact";
            copy.isServerRequired = persistedRequirement == ModRequirementDetector.Requirement.REQUIRED
                    || persistedRequirement == ModRequirementDetector.Requirement.UNKNOWN;
            byFileName.put(copy.fileName, copy);
        }
        List<ModInfo> result = new ArrayList<>(byFileName.values());
        resolveMissingModrinthSources(result);
        for (ModInfo info : result) {
            DownloadSources sources = downloadSources.get(info.hash);
            if (sources != null) {
                info.curseForgeUrl = sources.curseForgeUrl;
                info.modrinthUrl = sources.modrinthUrl;
            }
        }
        return result;
    }

    private static String calculateHash(File file) {
        return WarehouseManager.calculateSha1(file.toPath());
    }

    private static void loadDownloadSources() {
        downloadSources = new LinkedHashMap<>();
        if (Files.isRegularFile(DOWNLOAD_SOURCES_FILE)) {
            try (Reader reader = Files.newBufferedReader(DOWNLOAD_SOURCES_FILE)) {
                Map<String, DownloadSources> loaded = GSON.fromJson(reader,
                        new TypeToken<Map<String, DownloadSources>>(){}.getType());
                if (loaded != null) downloadSources.putAll(loaded);
            } catch (Exception e) {
                LOGGER.warn("AutoModSync: Could not read download_sources.json", e);
            }
        }
        saveDownloadSources();
    }

    private static void saveDownloadSources() {
        try {
            downloadSources.entrySet().removeIf(entry -> entry.getValue() == null
                    || (isBlank(entry.getValue().curseForgeUrl) && isBlank(entry.getValue().modrinthUrl)));
            Path part = DOWNLOAD_SOURCES_FILE.resolveSibling("download_sources.json.part");
            try (Writer writer = Files.newBufferedWriter(part)) { GSON.toJson(downloadSources, writer); }
            try {
                Files.move(part, DOWNLOAD_SOURCES_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(part, DOWNLOAD_SOURCES_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("AutoModSync: Could not write download_sources.json", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<ModInfo> withDownloadSources(List<ModInfo> files) {
        List<ModInfo> result = new ArrayList<>();
        if (files != null) {
            for (ModInfo file : files) {
                if (file != null) result.add(GSON.fromJson(GSON.toJson(file), ModInfo.class));
            }
        }
        for (ModInfo info : result) {
            DownloadSources sources = downloadSources.get(info.hash);
            if (sources != null) {
                info.curseForgeUrl = sources.curseForgeUrl;
                info.modrinthUrl = sources.modrinthUrl;
            }
        }
        return result;
    }

    private static void migrateManifestDownloadSources() {
        boolean sourcesChanged = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MANIFEST_DIR, "manifest_v*.json")) {
            for (Path path : stream) {
                FullManifest manifest;
                try (Reader reader = Files.newBufferedReader(path)) {
                    manifest = GSON.fromJson(reader, FullManifest.class);
                }
                if (manifest == null || manifest.files == null) continue;
                boolean containedSources = false;
                for (ModInfo info : manifest.files) {
                    if (info == null || info.hash == null) continue;
                    if (!isBlank(info.curseForgeUrl) || !isBlank(info.modrinthUrl)) {
                        containedSources = true;
                        DownloadSources sources = downloadSources.computeIfAbsent(info.hash,
                                ignored -> new DownloadSources());
                        if (isBlank(sources.curseForgeUrl) && !isBlank(info.curseForgeUrl)) {
                            sources.curseForgeUrl = info.curseForgeUrl;
                            sourcesChanged = true;
                        }
                        if (isBlank(sources.modrinthUrl) && !isBlank(info.modrinthUrl)) {
                            sources.modrinthUrl = info.modrinthUrl;
                            sourcesChanged = true;
                        }
                    }
                }
                if (containedSources) saveManifest(manifest);
            }
        } catch (IOException e) {
            LOGGER.warn("AutoModSync: Could not migrate manifest download sources", e);
        }
        if (sourcesChanged) saveDownloadSources();
    }

    private static void loadVerifiedRequirements() {
        verifiedRequirements = new LinkedHashMap<>();
        if (!Files.isRegularFile(VERIFIED_REQUIREMENTS_FILE)) return;
        try (Reader reader = Files.newBufferedReader(VERIFIED_REQUIREMENTS_FILE)) {
            Map<String, ModRequirementDetector.Requirement> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<String, ModRequirementDetector.Requirement>>(){}.getType());
            if (loaded != null) verifiedRequirements.putAll(loaded);
        } catch (Exception e) {
            LOGGER.warn("AutoModSync: Could not read verified_requirements.json", e);
        }
    }

    private static void saveVerifiedRequirements() throws IOException {
        Path part = VERIFIED_REQUIREMENTS_FILE.resolveSibling("verified_requirements.json.part");
        try (Writer writer = Files.newBufferedWriter(part)) { GSON.toJson(verifiedRequirements, writer); }
        try {
            Files.move(part, VERIFIED_REQUIREMENTS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(part, VERIFIED_REQUIREMENTS_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void loadVerifiedClientArtifacts() {
        verifiedClientArtifacts = new LinkedHashMap<>();
        if (!Files.isRegularFile(VERIFIED_CLIENT_ARTIFACTS_FILE)) return;
        try (Reader reader = Files.newBufferedReader(VERIFIED_CLIENT_ARTIFACTS_FILE)) {
            Map<String, ModInfo> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<String, ModInfo>>(){}.getType());
            if (loaded != null) {
                for (Map.Entry<String, ModInfo> entry : loaded.entrySet()) {
                    ModInfo info = entry.getValue();
                    if (info == null) continue;
                    info.modIds = sanitizeModIds(info.modIds);
                    if (info.modId == null || info.modId.equals("minecraft") || info.modId.equals("forge")) {
                        info.modId = info.modIds.isEmpty() ? null : info.modIds.get(0);
                    }
                    verifiedClientArtifacts.put(entry.getKey(), info);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("AutoModSync: Could not read verified_client_artifacts.json", e);
        }
    }

    private static void saveVerifiedClientArtifacts() throws IOException {
        Path part = VERIFIED_CLIENT_ARTIFACTS_FILE.resolveSibling("verified_client_artifacts.json.part");
        try (Writer writer = Files.newBufferedWriter(part)) { GSON.toJson(verifiedClientArtifacts, writer); }
        try {
            Files.move(part, VERIFIED_CLIENT_ARTIFACTS_FILE,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(part, VERIFIED_CLIENT_ARTIFACTS_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Resolves all missing Modrinth links in one public SHA-1 API request. */
    private static void resolveMissingModrinthSources(List<ModInfo> files) {
        for (ModInfo file : files) {
            if (file.hash != null && !file.hash.isEmpty()) {
                downloadSources.computeIfAbsent(file.hash, ignored -> new DownloadSources());
            }
        }
        saveDownloadSources();
        List<String> missing = files.stream()
                .map(info -> info.hash)
                .filter(hash -> hash != null && !hash.isEmpty())
                .filter(hash -> {
                    DownloadSources source = downloadSources.get(hash);
                    return source == null || source.modrinthUrl == null || source.modrinthUrl.isEmpty();
                })
                .distinct().collect(Collectors.toList());
        if (missing.isEmpty()) return;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://api.modrinth.com/v2/version_files").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "AutoModSync/3.3.0 (Minecraft Forge)");
            JsonObject request = new JsonObject();
            JsonArray hashes = new JsonArray();
            missing.forEach(hashes::add);
            request.add("hashes", hashes);
            request.addProperty("algorithm", "sha1");
            byte[] requestBytes = GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(requestBytes); }
            if (connection.getResponseCode() != 200) return;
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject versions = GSON.fromJson(reader, JsonObject.class);
                for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonArray remoteFiles = entry.getValue().getAsJsonObject().getAsJsonArray("files");
                    if (remoteFiles == null) continue;
                    for (JsonElement remoteFile : remoteFiles) {
                        JsonObject object = remoteFile.getAsJsonObject();
                        JsonObject remoteHashes = object.getAsJsonObject("hashes");
                        if (remoteHashes == null || !remoteHashes.has("sha1") || !object.has("url")) continue;
                        if (!entry.getKey().equalsIgnoreCase(remoteHashes.get("sha1").getAsString())) continue;
                        String url = object.get("url").getAsString();
                        if (!url.startsWith("https://cdn.modrinth.com/")) continue;
                        DownloadSources source = downloadSources.computeIfAbsent(entry.getKey(), ignored -> new DownloadSources());
                        source.modrinthUrl = url;
                        break;
                    }
                }
            }
            saveDownloadSources();
        } catch (Exception e) {
            LOGGER.info("AutoModSync: Modrinth source lookup unavailable; server warehouse will be used as fallback");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String calculateManifestHash(FullManifest manifest) {
        String previous = manifest.manifestHash;
        try {
            FullManifest canonical = manifestWithoutDownloadSources(manifest);
            canonical.manifestHash = null;
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = GSON.toJson(canonical).getBytes(StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder(40);
            for (byte value : digest.digest(bytes)) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            return previous;
        }
    }

    public static synchronized List<String> getChangedModIdsForVerification() {
        if (!hasPendingVerification()) return Collections.emptyList();
        FullManifest candidate = loadManifest(currentLatestVersion);
        FullManifest previous = loadManifest(currentLatestVerifiedVersion);
        Map<String, ModInfo> oldById = manifestByModId(previous);
        Map<String, ModInfo> newById = manifestByModId(candidate);
        Set<String> changed = new TreeSet<>();
        Set<String> ids = new HashSet<>(oldById.keySet());
        ids.addAll(newById.keySet());
        for (String id : ids) {
            ModInfo oldFile = oldById.get(id);
            ModInfo newFile = newById.get(id);
            if (oldFile == null || newFile == null || !Objects.equals(oldFile.hash, newFile.hash)) changed.add(id);
        }
        return new ArrayList<>(changed);
    }

    public static synchronized List<String> getChangedModHashesForVerification() {
        if (!hasPendingVerification()) return Collections.emptyList();
        FullManifest candidate = loadManifest(currentLatestVersion);
        FullManifest previous = loadManifest(currentLatestVerifiedVersion);
        Map<String, ModInfo> oldById = manifestByModId(previous);
        Map<String, ModInfo> newById = manifestByModId(candidate);
        Set<String> hashes = new TreeSet<>();
        for (Map.Entry<String, ModInfo> entry : newById.entrySet()) {
            ModInfo oldFile = oldById.get(entry.getKey());
            ModInfo newFile = entry.getValue();
            if ((oldFile == null || !Objects.equals(oldFile.hash, newFile.hash)) && newFile.hash != null) {
                hashes.add(newFile.hash);
            }
        }
        return new ArrayList<>(hashes);
    }

    public static synchronized boolean beginAdminArtifactUpload(ServerPlayer player, int candidateVersion,
                                                                 String fileName, String hash, long size,
                                                                 List<String> claimedModIds) {
        if (!isVerificationAdministrator(player) || !hasPendingVerification()
                || candidateVersion != currentLatestVersion || !isValidSha1(hash)
                || size <= 0 || size > MAX_ADMIN_ARTIFACT_BYTES) return false;
        String safeName;
        try { safeName = Path.of(fileName).getFileName().toString(); }
        catch (Exception e) { return false; }
        if (!safeName.equals(fileName) || safeName.length() > 512 || !safeName.toLowerCase().endsWith(".jar")) return false;

        abortAdminUpload(player.getUUID());
        WarehouseManager.init(STORAGE_DIR);
        Path existing = WarehouseManager.getFileByHash(hash);
        if (existing != null) {
            ModInfo info = createUploadedClientArtifact(existing, safeName, hash, claimedModIds);
            completedAdminUploads.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>())
                    .put(hash.toLowerCase(), info);
            return true;
        }
        try {
            Files.createDirectories(ADMIN_UPLOAD_DIR);
            AdminUploadSession session = new AdminUploadSession();
            session.candidateVersion = candidateVersion;
            session.fileName = safeName;
            session.hash = hash.toLowerCase();
            session.expectedSize = size;
            session.modIds = sanitizeModIds(claimedModIds);
            session.partFile = ADMIN_UPLOAD_DIR.resolve(player.getUUID() + "-" + session.hash + ".part");
            Files.newOutputStream(session.partFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).close();
            activeAdminUploads.put(player.getUUID(), session);
            return true;
        } catch (IOException e) {
            LOGGER.warn("AutoModSync: Could not start administrator artifact upload", e);
            return false;
        }
    }

    public static synchronized boolean appendAdminArtifactUpload(ServerPlayer player, int candidateVersion,
                                                                  String hash, long offset, byte[] data) {
        if (!isVerificationAdministrator(player) || data == null || data.length == 0
                || data.length > MAX_UPLOAD_CHUNK_BYTES) return false;
        AdminUploadSession session = activeAdminUploads.get(player.getUUID());
        if (session == null) return WarehouseManager.getFileByHash(hash) != null;
        if (session.candidateVersion != candidateVersion || !session.hash.equalsIgnoreCase(hash)
                || offset != session.receivedSize
                || session.receivedSize + data.length > session.expectedSize) {
            abortAdminUpload(player.getUUID());
            return false;
        }
        try {
            Files.write(session.partFile, data, StandardOpenOption.APPEND);
            session.receivedSize += data.length;
            return true;
        } catch (IOException e) {
            abortAdminUpload(player.getUUID());
            LOGGER.warn("AutoModSync: Could not append administrator artifact upload", e);
            return false;
        }
    }

    public static synchronized boolean finishAdminArtifactUpload(ServerPlayer player, int candidateVersion,
                                                                  String hash) {
        if (!isVerificationAdministrator(player)) return false;
        AdminUploadSession session = activeAdminUploads.get(player.getUUID());
        if (session == null) {
            Map<String, ModInfo> completed = completedAdminUploads.get(player.getUUID());
            return completed != null && completed.containsKey(hash.toLowerCase());
        }
        if (session.candidateVersion != candidateVersion || !session.hash.equalsIgnoreCase(hash)
                || session.receivedSize != session.expectedSize
                || !session.hash.equalsIgnoreCase(WarehouseManager.calculateSha1(session.partFile))) {
            abortAdminUpload(player.getUUID());
            return false;
        }
        try {
            List<String> actualModIds = readModIds(session.partFile);
            if (actualModIds.isEmpty()) actualModIds = session.modIds;
            WarehouseManager.init(STORAGE_DIR);
            String stored = WarehouseManager.store(session.partFile, session.fileName);
            if (!session.hash.equalsIgnoreCase(stored)) throw new IOException("warehouse SHA-1 mismatch");
            ModInfo info = createUploadedClientArtifact(session.partFile, session.fileName,
                    session.hash, actualModIds);
            completedAdminUploads.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>())
                    .put(session.hash, info);
            activeAdminUploads.remove(player.getUUID());
            Files.deleteIfExists(session.partFile);
            return true;
        } catch (IOException e) {
            abortAdminUpload(player.getUUID());
            LOGGER.warn("AutoModSync: Could not finish administrator artifact upload", e);
            return false;
        }
    }

    private static ModInfo createUploadedClientArtifact(Path file, String fileName, String hash,
                                                         List<String> claimedModIds) {
        ModInfo info = new ModInfo();
        info.fileName = fileName;
        info.hash = hash.toLowerCase();
        try { info.size = Files.size(file); } catch (IOException ignored) {}
        List<String> actualModIds = readModIds(file);
        info.modIds = actualModIds.isEmpty() ? sanitizeModIds(claimedModIds) : actualModIds;
        info.modId = info.modIds.isEmpty() ? null : info.modIds.get(0);
        info.requirement = ModRequirementDetector.Requirement.REQUIRED;
        info.requirementEvidence = "administrator uploaded client artifact after successful login";
        info.isServerRequired = true;
        return info;
    }

    private static List<String> sanitizeModIds(List<String> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().filter(Objects::nonNull)
                .filter(value -> value.matches("[a-z][a-z0-9_]{0,127}"))
                .filter(value -> !value.equals("minecraft") && !value.equals("forge"))
                .distinct().limit(128).collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<String> readModIds(Path jar) {
        List<String> ids = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/mods.toml");
            if (entry == null) return ids;
            try (InputStream input = zip.getInputStream(entry)) {
                String toml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                boolean inModsSection = false;
                Pattern modIdPattern = Pattern.compile("^\\s*modId\\s*=\\s*\"([a-z][a-z0-9_]{0,127})\"");
                for (String line : toml.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("[[")) {
                        inModsSection = trimmed.startsWith("[[mods]]");
                        continue;
                    }
                    if (!inModsSection) continue;
                    Matcher matcher = modIdPattern.matcher(line);
                    if (matcher.find() && !ids.contains(matcher.group(1))
                            && !matcher.group(1).equals("minecraft") && !matcher.group(1).equals("forge")) {
                        ids.add(matcher.group(1));
                        if (ids.size() >= 128) break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return ids;
    }

    private static boolean isValidSha1(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{40}");
    }

    private static void abortAdminUpload(UUID playerId) {
        AdminUploadSession session = activeAdminUploads.remove(playerId);
        if (session != null && session.partFile != null) {
            try { Files.deleteIfExists(session.partFile); } catch (IOException ignored) {}
        }
    }

    public static synchronized boolean submitAdminVerification(ServerPlayer player, int candidateVersion,
                                                                List<AdminModState> states) {
        if (!isVerificationAdministrator(player) || !hasPendingVerification()
                || candidateVersion != currentLatestVersion) return false;
        FullManifest candidate = loadManifest(candidateVersion);
        FullManifest previous = loadManifest(currentLatestVerifiedVersion);
        if (candidate == null) return false;
        if (candidate.verifiedModRemovals == null) candidate.verifiedModRemovals = new ArrayList<>();
        if (candidate.forcedFiles == null) candidate.forcedFiles = new ArrayList<>();
        String clientPlanBeforeVerification = clientPlanIdentity(candidate);

        Map<String, ModInfo> oldById = manifestByModId(previous);
        Map<String, ModInfo> newById = manifestByModId(candidate);
        Set<String> serverChangedIds = new HashSet<>(oldById.keySet());
        serverChangedIds.addAll(newById.keySet());
        serverChangedIds.removeIf(id -> {
            ModInfo oldFile = oldById.get(id);
            ModInfo newFile = newById.get(id);
            return oldFile != null && newFile != null && Objects.equals(oldFile.hash, newFile.hash);
        });
        Map<String, AdminModState> clientById = new HashMap<>();
        if (states != null) {
            for (AdminModState state : states) {
                if (state != null && state.modId != null) clientById.put(state.modId, state);
            }
        }

        boolean requiresNextVersion = false;
        Set<String> claimedClientArtifactHashes = states == null ? Collections.emptySet() : states.stream()
                .filter(Objects::nonNull)
                .filter(state -> state.administratorAddedClientArtifact && state.baselineKnown && isValidSha1(state.hash))
                .map(state -> state.hash.toLowerCase()).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, ModInfo> completedUploads = completedAdminUploads.getOrDefault(
                player.getUUID(), Collections.emptyMap());
        for (String hash : claimedClientArtifactHashes) {
            ModInfo uploaded = completedUploads.get(hash);
            AdminModState uploadedState = states.stream()
                    .filter(Objects::nonNull)
                    .filter(state -> state.administratorAddedClientArtifact
                            && hash.equalsIgnoreCase(state.hash))
                    .findFirst().orElse(null);
            if (uploaded == null || WarehouseManager.getFileByHash(hash) == null
                    || uploaded.modIds == null || uploaded.modIds.isEmpty()) {
                LOGGER.warn("AutoModSync: Rejecting verification because client artifact {} was not fully uploaded", hash);
                return false;
            }
            boolean duplicateHash = candidate.files.stream().anyMatch(file -> Objects.equals(file.hash, hash));
            ModInfo replaced = uploadedState == null || !isValidSha1(uploadedState.previousHash)
                    ? null : candidate.files.stream()
                    .filter(file -> uploadedState.previousHash.equalsIgnoreCase(file.hash))
                    .findFirst().orElse(null);
            ForcedRule replacedOverride = uploadedState == null || !isValidSha1(uploadedState.previousHash)
                    ? null : candidate.forcedFiles.stream()
                    .filter(rule -> rule != null && "ADD".equals(rule.action)
                            && uploadedState.previousHash.equalsIgnoreCase(rule.hash)
                            && rule.target != null && rule.target.startsWith("mods/"))
                    .findFirst().orElse(null);
            ModInfo conflictingName = candidate.files.stream()
                    .filter(file -> Objects.equals(file.fileName, uploaded.fileName)
                            && !Objects.equals(file.hash, hash))
                    .findFirst().orElse(null);
            if (conflictingName != null && conflictingName != replaced && replacedOverride == null) {
                LOGGER.warn("AutoModSync: Rejecting uploaded client artifact with unrelated conflicting name {}",
                        uploaded.fileName);
                return false;
            }
            boolean publishAsForcedOverride = replacedOverride != null;
            if (replacedOverride != null) {
                candidate.forcedFiles.remove(replacedOverride);
                if (!Objects.equals(replacedOverride.target, "mods/" + uploaded.fileName)) {
                    String oldName = replacedOverride.target.substring("mods/".length());
                    if (!candidate.verifiedModRemovals.contains(oldName)) {
                        candidate.verifiedModRemovals.add(oldName);
                    }
                }
            }
            if (replaced != null) {
                boolean serverRuntime = isCurrentServerRuntimeArtifact(replaced);
                if (serverRuntime && Objects.equals(replaced.fileName, uploaded.fileName)) {
                    publishAsForcedOverride = true;
                }
                if (serverRuntime) {
                    replaced.requirement = ModRequirementDetector.Requirement.SERVER_ONLY;
                    replaced.requirementEvidence = "administrator replaced unchanged client artifact";
                    replaced.isServerRequired = false;
                    verifiedRequirements.put(replaced.hash, ModRequirementDetector.Requirement.SERVER_ONLY);
                } else {
                    candidate.files.remove(replaced);
                    verifiedClientArtifacts.remove(replaced.hash.toLowerCase(Locale.ROOT));
                    verifiedRequirements.remove(replaced.hash.toLowerCase(Locale.ROOT));
                }
                if (!Objects.equals(replaced.fileName, uploaded.fileName)
                        && !candidate.verifiedModRemovals.contains(replaced.fileName)) {
                    candidate.verifiedModRemovals.add(replaced.fileName);
                }
            }
            if (publishAsForcedOverride) {
                String target = "mods/" + uploaded.fileName;
                candidate.forcedFiles.removeIf(rule -> rule != null && target.equals(rule.target));
                ForcedRule override = new ForcedRule("ADD", "administrator-upload", target, uploaded.hash);
                override.kind = "MOD";
                override.id = "admin-mod:" + uploaded.modId;
                override.size = uploaded.size;
                override.changedVersion = candidate.version + 1;
                candidate.forcedFiles.add(override);
            } else if (!duplicateHash) {
                candidate.files.add(GSON.fromJson(GSON.toJson(uploaded), ModInfo.class));
            }
            verifiedClientArtifacts.put(hash, GSON.fromJson(GSON.toJson(uploaded), ModInfo.class));
            verifiedRequirements.put(hash, ModRequirementDetector.Requirement.REQUIRED);
            candidate.verifiedModRemovals.remove(uploaded.fileName);
            requiresNextVersion = true;
        }
        Set<String> handledFiles = new HashSet<>();
        for (Map.Entry<String, ModInfo> entry : newById.entrySet()) {
            String modId = entry.getKey();
            ModInfo newFile = entry.getValue();
            ModInfo oldFile = oldById.get(modId);
            if (oldFile != null && Objects.equals(oldFile.hash, newFile.hash)) continue;
            if (!handledFiles.add(newFile.fileName)) continue;

            AdminModState clientState = findClientState(clientById, newFile);
            if (clientState == null || !clientState.isPresent()) {
                newFile.requirement = ModRequirementDetector.Requirement.SERVER_ONLY;
                newFile.requirementEvidence = "administrator joined without changed server mod";
                newFile.isServerRequired = false;
                verifiedRequirements.put(newFile.hash, newFile.requirement);
                if (!candidate.verifiedModRemovals.contains(newFile.fileName)) {
                    candidate.verifiedModRemovals.add(newFile.fileName);
                }
            } else if (Objects.equals(clientState.hash, newFile.hash)) {
                newFile.requirement = ModRequirementDetector.Requirement.REQUIRED;
                newFile.requirementEvidence = "administrator successful-login verification (exact SHA-1)";
                newFile.isServerRequired = true;
                verifiedRequirements.put(newFile.hash, newFile.requirement);
                candidate.verifiedModRemovals.remove(newFile.fileName);
            } else {
                newFile.requirement = ModRequirementDetector.Requirement.OPTIONAL;
                newFile.requirementEvidence = "administrator joined with a different/compatible client artifact";
                newFile.isServerRequired = false;
                verifiedRequirements.put(newFile.hash, newFile.requirement);
                candidate.verifiedModRemovals.remove(newFile.fileName);
            }
        }

        if (previous != null) {
            Set<String> removedFiles = new HashSet<>();
            for (Map.Entry<String, ModInfo> entry : oldById.entrySet()) {
                if (newById.containsKey(entry.getKey())) continue;
                ModInfo oldFile = entry.getValue();
                if (!removedFiles.add(oldFile.fileName)) continue;
                AdminModState removedState = findClientState(clientById, oldFile);
                if ((removedState == null || !removedState.isPresent())
                        && !candidate.verifiedModRemovals.contains(oldFile.fileName)) {
                    candidate.verifiedModRemovals.add(oldFile.fileName);
                    // The administrator also removed the artifact successfully.
                    // Persist the verified client-side requirement separately from
                    // the removal action so a later reappearance keeps its evidence.
                    if (oldFile.hash != null) {
                        verifiedRequirements.put(oldFile.hash,
                                ModRequirementDetector.Requirement.REQUIRED);
                    }
                } else if (removedState != null && removedState.isPresent()
                        && Objects.equals(removedState.hash, oldFile.hash)) {
                    ModInfo retainedClientArtifact = GSON.fromJson(GSON.toJson(oldFile), ModInfo.class);
                    retainedClientArtifact.isServerRequired = false;
                    retainedClientArtifact.requirement = ModRequirementDetector.Requirement.OPTIONAL;
                    retainedClientArtifact.requirementEvidence =
                            "administrator retained removed server mod and joined successfully";
                    boolean alreadyPresent = candidate.files.stream()
                            .anyMatch(file -> Objects.equals(file.hash, retainedClientArtifact.hash));
                    if (!alreadyPresent) candidate.files.add(retainedClientArtifact);
                    verifiedClientArtifacts.put(retainedClientArtifact.hash,
                            GSON.fromJson(GSON.toJson(retainedClientArtifact), ModInfo.class));
                    verifiedRequirements.put(retainedClientArtifact.hash,
                            ModRequirementDetector.Requirement.OPTIONAL);
                    candidate.verifiedModRemovals.remove(retainedClientArtifact.fileName);
                }
            }
        }

        // Client-side removals unrelated to this server update are an
        // administrator pack change. Publish them as candidate + 1 instead of
        // silently losing the delta. The server still keeps runtime artifacts
        // in the audit manifest, but marks them SERVER_ONLY for clients.
        Set<String> handledAdministratorRemovals = new HashSet<>();
        if (states != null) {
            for (AdminModState state : states) {
                if (state == null || !state.administratorRemovedClientArtifact
                        || !state.baselineKnown || state.isPresent()
                        || !isValidSha1(state.previousHash) || state.fileName == null
                        || serverChangedIds.contains(state.modId)) continue;
                String safeName;
                try { safeName = Path.of(state.fileName).getFileName().toString(); }
                catch (Exception ignored) { continue; }
                if (!safeName.equals(state.fileName) || !safeName.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                String removalKey = state.previousHash.toLowerCase(Locale.ROOT) + "|" + safeName;
                if (!handledAdministratorRemovals.add(removalKey)) continue;

                ModInfo currentArtifact = candidate.files.stream()
                        .filter(file -> file != null && state.previousHash.equalsIgnoreCase(file.hash))
                        .findFirst().orElse(null);
                ForcedRule forcedArtifact = candidate.forcedFiles.stream()
                        .filter(rule -> rule != null && "ADD".equals(rule.action)
                                && state.previousHash.equalsIgnoreCase(rule.hash)
                                && ("mods/" + safeName).equals(rule.target))
                        .findFirst().orElse(null);
                if (forcedArtifact != null) candidate.forcedFiles.remove(forcedArtifact);
                if (currentArtifact != null && isCurrentServerRuntimeArtifact(currentArtifact)) {
                    currentArtifact.requirement = ModRequirementDetector.Requirement.SERVER_ONLY;
                    currentArtifact.requirementEvidence =
                            "administrator removed unchanged client artifact and joined successfully";
                    currentArtifact.isServerRequired = false;
                    verifiedRequirements.put(currentArtifact.hash,
                            ModRequirementDetector.Requirement.SERVER_ONLY);
                } else {
                    candidate.files.removeIf(file -> file != null
                            && state.previousHash.equalsIgnoreCase(file.hash));
                    verifiedClientArtifacts.remove(state.previousHash.toLowerCase(Locale.ROOT));
                    verifiedRequirements.remove(state.previousHash.toLowerCase(Locale.ROOT));
                }
                if (!candidate.verifiedModRemovals.contains(safeName)) {
                    candidate.verifiedModRemovals.add(safeName);
                }
                requiresNextVersion = true;
            }
        }

        candidate.forcedFiles.sort(Comparator
                .comparing((ForcedRule rule) -> rule.target == null ? "" : rule.target)
                .thenComparing(rule -> rule.action == null ? "" : rule.action));

        // A status/revision-only verification may stay on the candidate version.
        // Any change that affects what clients install or remove must be published
        // as a new main version because clients intentionally gate validation by vN.
        requiresNextVersion = requiresNextVersion
                || !clientPlanBeforeVerification.equals(clientPlanIdentity(candidate));

        try {
            saveVerifiedRequirements();
            saveVerifiedClientArtifacts();
            if (requiresNextVersion) {
                candidate.verificationStatus = VerificationStatus.SUPERSEDED;
                candidate.revision++;
                candidate.manifestHash = calculateManifestHash(candidate);
                saveManifest(candidate);

                FullManifest verified = GSON.fromJson(GSON.toJson(candidate), FullManifest.class);
                verified.version = candidate.version + 1;
                verified.revision = 0;
                verified.verificationStatus = VerificationStatus.VERIFIED;
                verified.manifestHash = calculateManifestHash(verified);
                saveManifest(verified);
                currentLatestVersion = verified.version;
                currentLatestVerifiedVersion = verified.version;
                currentRevision = verified.revision;
                currentVerificationStatus = verified.verificationStatus;
            } else {
                candidate.revision++;
                candidate.verificationStatus = VerificationStatus.VERIFIED;
                candidate.manifestHash = calculateManifestHash(candidate);
                saveManifest(candidate);
                currentLatestVerifiedVersion = candidate.version;
                currentRevision = candidate.revision;
                currentVerificationStatus = candidate.verificationStatus;
            }
            saveChangelogState();
            completedAdminUploads.remove(player.getUUID());
            LOGGER.info("AutoModSync: {} verified version v{} (revision {})",
                    player.getGameProfile().getName(), currentLatestVersion, currentRevision);
            return true;
        } catch (IOException e) {
            LOGGER.error("AutoModSync: Failed to publish administrator verification", e);
            return false;
        }
    }

    private static Map<String, ModInfo> manifestByModId(FullManifest manifest) {
        Map<String, ModInfo> result = new HashMap<>();
        if (manifest == null || manifest.files == null) return result;
        for (ModInfo file : manifest.files) {
            List<String> ids = file.modIds == null || file.modIds.isEmpty()
                    ? Collections.singletonList(file.modId) : file.modIds;
            for (String id : ids) if (id != null) result.put(id, file);
        }
        return result;
    }

    private static String clientPlanIdentity(FullManifest manifest) {
        if (manifest == null) return "";
        List<String> files = new ArrayList<>();
        if (manifest.files != null) {
            for (ModInfo file : manifest.files) {
                if (file == null) continue;
                files.add(String.join("|",
                        Objects.toString(file.fileName, ""),
                        Objects.toString(file.modId, ""),
                        Objects.toString(file.hash, ""),
                        Boolean.toString(file.isServerRequired),
                        Objects.toString(file.requirement, "")));
            }
        }
        Collections.sort(files);
        List<String> forced = new ArrayList<>();
        if (manifest.forcedFiles != null) {
            for (ForcedRule rule : manifest.forcedFiles) {
                if (rule == null) continue;
                forced.add(String.join("|",
                        Objects.toString(rule.action, ""),
                        Objects.toString(rule.target, ""),
                        Objects.toString(rule.hash, ""),
                        Objects.toString(rule.id, ""),
                        Objects.toString(rule.kind, "")));
            }
        }
        Collections.sort(forced);
        List<String> removals = manifest.verifiedModRemovals == null
                ? new ArrayList<>() : new ArrayList<>(manifest.verifiedModRemovals);
        Collections.sort(removals);
        return GSON.toJson(files) + GSON.toJson(forced) + GSON.toJson(removals);
    }

    private static boolean isCurrentServerRuntimeArtifact(ModInfo artifact) {
        if (artifact == null || artifact.fileName == null || artifact.hash == null) return false;
        Path modsRoot = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        Path path = modsRoot.resolve(artifact.fileName).normalize();
        return path.startsWith(modsRoot) && Files.isRegularFile(path)
                && artifact.hash.equalsIgnoreCase(WarehouseManager.calculateSha1(path));
    }

    private static AdminModState findClientState(Map<String, AdminModState> clientById, ModInfo file) {
        List<String> ids = file.modIds == null || file.modIds.isEmpty()
                ? Collections.singletonList(file.modId) : file.modIds;
        for (String id : ids) {
            AdminModState state = clientById.get(id);
            if (state != null) return state;
        }
        return null;
    }

    private static void saveManifest(FullManifest manifest) throws IOException {
        Path target = MANIFEST_DIR.resolve("manifest_v" + manifest.version + ".json");
        Path part = target.resolveSibling(target.getFileName() + ".part");
        FullManifest stored = manifestWithoutDownloadSources(manifest);
        try (Writer writer = Files.newBufferedWriter(part)) { GSON.toJson(stored, writer); }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static FullManifest manifestWithoutDownloadSources(FullManifest manifest) {
        FullManifest copy = GSON.fromJson(GSON.toJson(manifest), FullManifest.class);
        if (copy.files != null) {
            for (ModInfo info : copy.files) {
                info.curseForgeUrl = null;
                info.modrinthUrl = null;
            }
        }
        return copy;
    }

    private static void saveChangelogState() throws IOException {
        Changelog log = new Changelog();
        log.latestVersion = currentLatestVersion;
        log.latestVerifiedVersion = currentLatestVerifiedVersion;
        log.lastUpdated = System.currentTimeMillis();
        Path part = CHANGELOG_FILE.resolveSibling("changelog.json.part");
        try (Writer writer = Files.newBufferedWriter(part)) { GSON.toJson(log, writer); }
        try {
            Files.move(part, CHANGELOG_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(part, CHANGELOG_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static class CustomSyncInternal {
        private static final Path ROOT = STORAGE_DIR.resolve("custom_sync");
        private static final Path MODS_ADD = ROOT.resolve("mods").resolve("add");
        private static final Path MODS_DELETE = ROOT.resolve("mods").resolve("delete");
        private static final Path MODS_IGNORE = ROOT.resolve("mods").resolve("ignore");
        private static final Path FILES = ROOT.resolve("files");
        private static final Path RULES = FILES.resolve("rules.json");
        private static final Path HISTORY = ROOT.resolve("history.json");
        private static final Path STORAGE_README = STORAGE_DIR.resolve("存储目录说明.txt");
        private static final Path CUSTOM_README = ROOT.resolve("使用说明.txt");
        private static final Path LEGACY = STORAGE_DIR.resolve("forced_sync");
        private static final Path LEGACY_MIGRATION_MARKER = ROOT.resolve(".legacy-imported");
        private static final List<HistoryChange> pendingChanges = new ArrayList<>();
        private static boolean pendingInbox;

        private static class InputRule {
            String id;
            String action;
            String file;
            String path;
            boolean isDir;
            String value;
        }
        private static class HistoryFile {
            int schemaVersion = 1;
            int latestRecordedVersion;
            List<HistoryUpdate> updates = new ArrayList<>();
        }
        private static class HistoryUpdate {
            int version;
            long time;
            List<HistoryChange> changes = new ArrayList<>();
        }
        private static class HistoryChange {
            String kind;
            String action;
            String id;
            String source;
            String target;
            String previousSha1;
            String sha1;
        }

        static void init() throws IOException {
            Files.createDirectories(MODS_ADD);
            Files.createDirectories(MODS_DELETE);
            Files.createDirectories(MODS_IGNORE);
            Files.createDirectories(FILES);
            migrateLegacyInbox();
            if (!Files.exists(RULES)) Files.writeString(RULES, "[]", StandardCharsets.UTF_8);
            if (!Files.exists(HISTORY)) {
                HistoryFile empty = new HistoryFile();
                try (Writer writer = Files.newBufferedWriter(HISTORY)) { GSON.toJson(empty, writer); }
            }
            writeDocumentation();
        }

        private static void writeDocumentation() throws IOException {
            Files.writeString(STORAGE_README,
                    "=== AutoModSync 服务器存储目录说明 ===\n\n" +
                    "changelog.json：记录当前最新版本和最新已验证版本，请勿手动修改。\n" +
                    "manifests/manifest_vN.json：各主版本的完整清单、验证状态和强制规则。\n" +
                    "warehouse/sha1/<SHA-1>/<原文件名>：按内容哈希保存的模组和自定义文件。\n" +
                    "warehouse/metadata/artifact_index.json：仓库文件索引。\n" +
                    "warehouse/metadata/download_sources.json：按 SHA-1 保存网站优先下载源。\n" +
                    "warehouse/metadata/verified_client_artifacts.json：管理员进服后确认并上传的客户端模组。\n" +
                    "warehouse/metadata/verified_requirements.json：已验证的客户端模组要求。\n" +
                    "custom_sync/：一次性同步操作区，具体写法见 custom_sync/使用说明.txt。\n" +
                    "temp/admin_uploads/：管理员验证期间的分片上传临时目录。\n\n" +
                    "除 custom_sync 操作区和 download_sources.json 外，其余文件均由系统维护。\n",
                    StandardCharsets.UTF_8);

            Files.writeString(CUSTOM_README,
                    "=== AutoModSync custom_sync 一次性同步区域 ===\n\n" +
                    "【目录用途】\n" +
                    "mods/add/：放入需要新增或替换的模组 JAR。\n" +
                    "mods/delete/：放入与目标模组同名的空文件，用于删除客户端模组。\n" +
                    "mods/ignore/：文件名或文件内容填写要忽略的 modId 或文件名。\n" +
                    "files/：放自定义源文件、源文件夹和 rules.json。\n" +
                    "history.json：系统自动记录版本号以及全部高级自定义变更，仅供查看。\n\n" +
                    "版本成功生成后，操作区内容会按 SHA-1 备份进 warehouse，然后自动清空；" +
                    "files/rules.json 会重置为 []。当前生效规则保存在 manifest_vN.json 中。\n" +
                    "如果规则无效、路径不安全或源文件不存在，本批次不会提交，输入文件会保留。\n\n" +
                    "【rules.json 字段】\n" +
                    "id：规则标识；建议填写稳定且唯一的名称。\n" +
                    "action：ADD、REPLACE_DIRECTORY、DELETE、IGNORE 或 UNIGNORE。\n" +
                    "file：ADD/REPLACE_DIRECTORY 的源路径，相对于 custom_sync/files/。\n" +
                    "path：客户端目标路径，例如 config/example.json；禁止绝对路径和 ..。\n" +
                    "isDir：DELETE 目录时填写 true；目录删除还要求服务端开启 allowDirectoryDeletion。\n" +
                    "value：IGNORE/UNIGNORE 使用的 modId 或文件名匹配值。\n\n" +
                    "【五种 action 的用途】\n" +
                    "ADD：把 files/ 下的源文件按 SHA-1 入仓，并要求客户端把它下载到 path。" +
                    "适合新增文件或覆盖同一路径的旧文件；目标已存在但哈希不同也会被替换。" +
                    "通常用于单个配置、脚本、资源包等文件。\n\n" +
                    "REPLACE_DIRECTORY：把源目录递归展开并同步到客户端目标目录。" +
                    "除了新增和覆盖文件，还会对比同一 id 的上一版目录规则：上一版存在、这次源目录中已经消失的文件会生成 DELETE。" +
                    "适合要求客户端目录内容跟随服务端模板的场景。注意：当前 ADD 的源如果是目录，也执行相同的目录镜像逻辑；为便于阅读和维护，目录建议明确写 REPLACE_DIRECTORY。\n\n" +
                    "DELETE：删除客户端 path 指向的文件或目录，不需要 file。" +
                    "删除普通文件时 isDir=false；删除整个目录时 isDir=true，并且服务端必须开启 allowDirectoryDeletion。" +
                    "它只删除明确指定的目标，不代表删除同名 modId；模组删除优先使用 mods/delete/。\n\n" +
                    "IGNORE：把 value 作为模组文件名或 modId 的匹配片段加入持续生效的忽略策略。" +
                    "命中的模组不会参与服务器主清单要求、本地基线额外模组检查、仓库完整性检查和管理员验证差异。" +
                    "它不会删除、下载或修改该模组，只表示 AutoModSync 不再管理它。匹配采用片段匹配，value 不要写得过短。\n\n" +
                    "UNIGNORE：撤销与 value 完全相同的现有 IGNORE 策略。" +
                    "撤销后，该模组会从当前版本开始重新进入正常校验；如果重新纳管后客户端与服务器要求不同，会按普通差分显示。" +
                    "UNIGNORE 只撤销策略，不会立即新增或删除文件。\n\n" +
                    "【可直接修改的完整示例】\n" +
                    "[\n" +
                    "  {\n" +
                    "    \"id\": \"example-config\",\n" +
                    "    \"action\": \"ADD\",\n" +
                    "    \"file\": \"example.json\",\n" +
                    "    \"path\": \"config/example.json\",\n" +
                    "    \"isDir\": false\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"example-folder\",\n" +
                    "    \"action\": \"REPLACE_DIRECTORY\",\n" +
                    "    \"file\": \"example-folder\",\n" +
                    "    \"path\": \"config/example-folder\",\n" +
                    "    \"isDir\": true\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"remove-old-file\",\n" +
                    "    \"action\": \"DELETE\",\n" +
                    "    \"path\": \"config/old-example.json\",\n" +
                    "    \"isDir\": false\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"ignore-client-mod\",\n" +
                    "    \"action\": \"IGNORE\",\n" +
                    "    \"value\": \"examplemod\"\n" +
                    "  }\n" +
                    "]\n\n" +
                    "要撤销 IGNORE，可提交 {\"action\":\"UNIGNORE\",\"value\":\"examplemod\"}。\n",
                    StandardCharsets.UTF_8);
        }

        static List<ForcedRule> generateRules(List<ForcedRule> previous, int nextVersion) {
            pendingChanges.clear();
            pendingInbox = false;
            Map<String, ForcedRule> effective = new LinkedHashMap<>();
            if (previous != null) {
                for (ForcedRule rule : previous) {
                    if (rule == null || rule.target == null) continue;
                    ForcedRule copy = GSON.fromJson(GSON.toJson(rule), ForcedRule.class);
                    effective.put(key(copy), copy);
                }
            }
            try {
                scanModDeletes(effective, nextVersion);
                scanModIgnores(effective, nextVersion);
                scanModAdds(effective, nextVersion);
                scanFileRules(effective, nextVersion);
            } catch (IOException e) {
                throw new IllegalStateException("custom_sync batch is invalid; input was preserved", e);
            }
            List<ForcedRule> result = new ArrayList<>(effective.values());
            result.sort(Comparator.comparing((ForcedRule rule) -> rule.target == null ? "" : rule.target)
                    .thenComparing(rule -> rule.action == null ? "" : rule.action));
            return result;
        }

        static void commitInbox(int version, boolean versionCreated) throws IOException {
            if (!pendingInbox) return;
            if (versionCreated && !pendingChanges.isEmpty()) appendHistory(version);
            clearContents(MODS_ADD);
            clearContents(MODS_DELETE);
            clearContents(MODS_IGNORE);
            clearContents(FILES);
            Files.writeString(RULES, "[]", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            pendingChanges.clear();
            pendingInbox = false;
        }

        private static void scanModDeletes(Map<String, ForcedRule> rules, int version) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(MODS_DELETE)) {
                for (Path marker : stream) {
                    if (!Files.isRegularFile(marker)) continue;
                    pendingInbox = true;
                    String name = marker.getFileName().toString();
                    put(rules, newRule("DELETE", "MOD", "mod:" + name,
                            null, "mods/" + name, null, 0L, version));
                }
            }
        }

        private static void scanModIgnores(Map<String, ForcedRule> rules, int version) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(MODS_IGNORE)) {
                for (Path marker : stream) {
                    if (!Files.isRegularFile(marker)) continue;
                    pendingInbox = true;
                    List<String> values = Files.readAllLines(marker, StandardCharsets.UTF_8).stream()
                            .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toList());
                    if (values.isEmpty()) values = Collections.singletonList(marker.getFileName().toString());
                    for (String value : values) {
                        put(rules, newRule("IGNORE", "MOD", "ignore:" + value,
                                null, value, null, 0L, version));
                    }
                }
            }
        }

        private static void scanModAdds(Map<String, ForcedRule> rules, int version) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(MODS_ADD, "*.jar")) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) continue;
                    pendingInbox = true;
                    String hash = WarehouseManager.store(file, file.getFileName().toString());
                    if (hash == null) throw new IOException("Could not store " + file.getFileName());
                    List<String> ids = readModIds(file);
                    String id = ids.isEmpty() ? "mod:" + file.getFileName() : "mod:" + ids.get(0);
                    List<String> obsoleteKeys = rules.entrySet().stream()
                            .filter(entry -> id.equals(entry.getValue().id)
                                    && !Objects.equals(entry.getValue().target, "mods/" + file.getFileName()))
                            .map(Map.Entry::getKey).collect(Collectors.toList());
                    for (String obsoleteKey : obsoleteKeys) {
                        ForcedRule obsolete = rules.remove(obsoleteKey);
                        if (obsolete != null && obsolete.target != null) {
                            ForcedRule deletion = newRule("DELETE", "MOD", id, null,
                                    obsolete.target, null, 0L, version);
                            rules.put(key(deletion), deletion);
                            record(obsolete, deletion, "DELETE");
                        }
                    }
                    put(rules, newRule("ADD", "MOD", id,
                            "mods/add/" + file.getFileName(), "mods/" + file.getFileName(),
                            hash, Files.size(file), version));
                }
            }
        }

        private static void scanFileRules(Map<String, ForcedRule> rules, int version) throws IOException {
            if (!Files.isRegularFile(RULES)) return;
            List<InputRule> entries;
            try (Reader reader = Files.newBufferedReader(RULES)) {
                entries = GSON.fromJson(reader, new TypeToken<List<InputRule>>(){}.getType());
            } catch (Exception e) {
                throw new IOException("Invalid custom_sync/files/rules.json", e);
            }
            if (entries == null || entries.isEmpty()) return;
            pendingInbox = true;
            for (InputRule entry : entries) {
                if (entry == null || entry.action == null) continue;
                String action = entry.action.toUpperCase(Locale.ROOT);
                if ("IGNORE".equals(action) && entry.value != null && !entry.value.isBlank()) {
                    put(rules, newRule("IGNORE", "POLICY", inputId(entry, "ignore:" + entry.value),
                            null, entry.value, null, 0L, version));
                    continue;
                }
                if ("UNIGNORE".equals(action) && entry.value != null) {
                    ForcedRule removed = rules.remove("IGNORE|" + entry.value);
                    if (removed != null) record(removed, null, "UNIGNORE");
                    continue;
                }
                if (!safeRelative(entry.path)) throw new IOException("Unsafe target path: " + entry.path);
                String target = normalize(entry.path);
                String id = inputId(entry, "file:" + target);
                if ("DELETE".equals(action)) {
                    if (entry.isDir && !ModSyncMain.CONFIG.allowDirectoryDeletion.get()) {
                        throw new IOException("Directory deletion is disabled: " + target);
                    }
                    put(rules, newRule("DELETE", "FILE", id, null, target, null, 0L, version));
                } else if ("ADD".equals(action) || "REPLACE_DIRECTORY".equals(action)) {
                    if (!safeRelative(entry.file)) throw new IOException("Unsafe source path: " + entry.file);
                    Path source = FILES.resolve(entry.file).normalize();
                    Path root = FILES.toAbsolutePath().normalize();
                    Path absoluteSource = source.toAbsolutePath().normalize();
                    if (!absoluteSource.startsWith(root) || !Files.exists(absoluteSource)) {
                        throw new IOException("Missing custom source: " + entry.file);
                    }
                    if (Files.isDirectory(absoluteSource)) replaceDirectory(rules, absoluteSource, target, id, version);
                    else storeFile(rules, absoluteSource, target, id, version);
                }
            }
        }

        private static void replaceDirectory(Map<String, ForcedRule> rules, Path sourceRoot,
                                             String targetRoot, String id, int version) throws IOException {
            Set<String> targets = new HashSet<>();
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = sourceRoot.relativize(file).toString().replace("\\", "/");
                    String target = targetRoot + "/" + relative;
                    targets.add(target);
                    storeFile(rules, file, target, id, version);
                    return FileVisitResult.CONTINUE;
                }
            });
            List<ForcedRule> removed = rules.values().stream()
                    .filter(rule -> id.equals(rule.id) && "ADD".equals(rule.action)
                            && !targets.contains(rule.target)).collect(Collectors.toList());
            for (ForcedRule old : removed) {
                put(rules, newRule("DELETE", "FILE", id, null, old.target, null, 0L, version));
            }
        }

        private static void storeFile(Map<String, ForcedRule> rules, Path source,
                                      String target, String id, int version) throws IOException {
            String hash = WarehouseManager.store(source, source.getFileName().toString());
            if (hash == null) throw new IOException("Could not store " + source);
            String sourceLabel = "files/" + FILES.toAbsolutePath().normalize()
                    .relativize(source.toAbsolutePath().normalize()).toString().replace("\\", "/");
            put(rules, newRule("ADD", "FILE", id, sourceLabel, target,
                    hash, Files.size(source), version));
        }

        private static ForcedRule newRule(String action, String kind, String id, String source,
                                          String target, String hash, long size, int version) {
            ForcedRule rule = new ForcedRule(action, source, target, hash);
            rule.kind = kind;
            rule.id = id;
            rule.size = size;
            rule.changedVersion = version;
            return rule;
        }

        private static void put(Map<String, ForcedRule> rules, ForcedRule next) {
            String key = key(next);
            ForcedRule previous = rules.get(key);
            if (same(previous, next)) return;
            rules.put(key, next);
            record(previous, next, next.action);
        }

        private static String key(ForcedRule rule) {
            return "IGNORE".equals(rule.action) ? "IGNORE|" + rule.target : "TARGET|" + rule.target;
        }

        private static boolean same(ForcedRule left, ForcedRule right) {
            return left != null && Objects.equals(left.action, right.action)
                    && Objects.equals(left.target, right.target)
                    && Objects.equals(left.hash, right.hash)
                    && Objects.equals(left.id, right.id)
                    && Objects.equals(left.kind, right.kind);
        }

        private static void record(ForcedRule previous, ForcedRule next, String action) {
            ForcedRule source = next == null ? previous : next;
            HistoryChange change = new HistoryChange();
            change.action = action;
            change.kind = source == null ? null : source.kind;
            change.id = source == null ? null : source.id;
            change.source = source == null ? null : source.source;
            change.target = source == null ? null : source.target;
            change.previousSha1 = previous == null ? null : previous.hash;
            change.sha1 = next == null ? null : next.hash;
            pendingChanges.add(change);
        }

        private static void appendHistory(int version) throws IOException {
            HistoryFile history = new HistoryFile();
            if (Files.isRegularFile(HISTORY)) {
                try (Reader reader = Files.newBufferedReader(HISTORY)) {
                    HistoryFile loaded = GSON.fromJson(reader, HistoryFile.class);
                    if (loaded != null) history = loaded;
                } catch (Exception e) {
                    throw new IOException("Invalid custom_sync/history.json", e);
                }
            }
            if (history.updates == null) history.updates = new ArrayList<>();
            HistoryUpdate update = new HistoryUpdate();
            update.version = version;
            update.time = System.currentTimeMillis();
            update.changes = GSON.fromJson(GSON.toJson(pendingChanges),
                    new TypeToken<List<HistoryChange>>(){}.getType());
            history.updates.add(update);
            history.latestRecordedVersion = version;
            Path part = HISTORY.resolveSibling("history.json.part");
            try (Writer writer = Files.newBufferedWriter(part)) { GSON.toJson(history, writer); }
            try {
                Files.move(part, HISTORY, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(part, HISTORY, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static String inputId(InputRule entry, String fallback) {
            return entry.id == null || entry.id.isBlank() ? fallback : entry.id.trim();
        }

        private static String normalize(String value) {
            return Paths.get(value).normalize().toString().replace("\\", "/");
        }

        private static boolean safeRelative(String value) {
            if (value == null || value.isBlank() || value.contains("..")) return false;
            try {
                Path path = Paths.get(value);
                return !path.isAbsolute() && path.getRoot() == null;
            } catch (InvalidPathException e) {
                return false;
            }
        }

        private static void clearContents(Path directory) throws IOException {
            if (!Files.isDirectory(directory)) return;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    Files.walkFileTree(child, new SimpleFileVisitor<Path>() {
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            if (exc != null) throw exc;
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }

        private static void migrateLegacyInbox() throws IOException {
            if (!Files.isDirectory(LEGACY) || Files.exists(LEGACY_MIGRATION_MARKER)) return;
            copyTree(LEGACY.resolve("mods_add"), MODS_ADD);
            copyTree(LEGACY.resolve("mods_remove"), MODS_DELETE);
            copyTree(LEGACY.resolve("custom").resolve("files"), FILES);
            Path oldRules = LEGACY.resolve("custom").resolve("rules.json");
            if (!Files.exists(RULES) && Files.isRegularFile(oldRules)) Files.copy(oldRules, RULES);
            Files.writeString(LEGACY_MIGRATION_MARKER,
                    "Legacy forced_sync inbox imported; original directory retained for recovery.",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }

        private static void copyTree(Path source, Path target) throws IOException {
            if (!Files.isDirectory(source)) return;
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destination = target.resolve(source.relativize(file));
                    if (!Files.exists(destination)) Files.copy(file, destination);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** Legacy reader retained only so old installations can still migrate their inbox. */
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

                {
                    String text = "=== AutoModSync 服务器存储目录说明 ===\n" +
                            "1. manifest_vX.json: 自动生成的版本全量清单 (请勿手动修改)。\n" +
                            "2. changelog.json: 记录当前最新版本号 (请勿手动修改)。\n" +
                            "3. warehouse/sha1/<SHA-1>/<原文件名>.jar: 模组内容仓库。\n" +
                            "4. download_sources.json: 按 SHA-1 统一配置 CurseForge/Modrinth 直链，版本清单不重复保存。\n" +
                            "5. forced_sync/: [管理员操作区] 用于强制分发文件或删除客户端文件。\n";
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
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR_MODS_REMOVE)) {
                for (Path p : stream) if (Files.isRegularFile(p)) rules.add(new ForcedRule("DELETE", null, "mods/" + p.getFileName(), null));
            } catch (IOException ignored) {}
            // Apply additions after removals so a file deliberately re-added in
            // the current rule set wins over a stale remove marker from an
            // earlier version (for example v2 DELETE -> v3 ADD).
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR_MODS_ADD)) {
                for (Path p : stream) if (Files.isRegularFile(p)) rules.add(new ForcedRule("ADD", "mods_add/" + p.getFileName(), "mods/" + p.getFileName(), calculateHash(p.toFile())));
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

                            if (!isSafeRelativePath(entry.path)) continue;

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
            // A target must have one effective forced action. Keep the last
            // ADD/DELETE declaration so a current re-add overrides an older
            // remove marker instead of sending contradictory actions.
            Map<String, Integer> effectiveIndex = new LinkedHashMap<>();
            for (int i = 0; i < rules.size(); i++) {
                ForcedRule rule = rules.get(i);
                if (("ADD".equals(rule.action) || "DELETE".equals(rule.action))
                        && rule.target != null) {
                    effectiveIndex.put(rule.target, i);
                }
            }
            List<ForcedRule> effectiveRules = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                ForcedRule rule = rules.get(i);
                if (("ADD".equals(rule.action) || "DELETE".equals(rule.action))
                        && rule.target != null && effectiveIndex.get(rule.target) != i) continue;
                effectiveRules.add(rule);
            }
            return effectiveRules;
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
            Path root = ROOT_DIR.toAbsolutePath().normalize();
            Path p = root.resolve(relativePath).normalize();
            return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
        }

        private static boolean isSafeRelativePath(String value) {
            if (value == null || value.isBlank() || value.contains("..")) return false;
            try {
                Path path = Paths.get(value);
                return !path.isAbsolute() && path.getRoot() == null;
            } catch (InvalidPathException e) {
                return false;
            }
        }
    }
}
