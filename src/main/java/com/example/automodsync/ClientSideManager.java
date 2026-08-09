package com.example.automodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;

import java.io.*;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mod.EventBusSubscriber(modid = ModSyncMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientSideManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final AtomicReference<String> CACHED_SYNC_URL = new AtomicReference<>("");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static int liveServerVersion = 0;
    private static int liveServerRevision = 0;
    private static ServerSideManager.VerificationStatus liveVerificationStatus = ServerSideManager.VerificationStatus.UNVERIFIED;

    // ================== 路径分离逻辑 (v3.1.3) ==================
    // 1. 配置文件路径：config/automodsync/
    private static final Path CONFIG_ROOT_DIR = FMLPaths.CONFIGDIR.get().resolve("automodsync");
    // 2. 仓库备份路径：游戏根目录/automodsync_client_storage/ (保持不动)
    private static final Path WAREHOUSE_ROOT_DIR = FMLPaths.GAMEDIR.get().resolve("automodsync_client_storage");

    // 具体文件指向 Config 目录
    private static final Path CLIENT_SNAPSHOT_FILE = CONFIG_ROOT_DIR.resolve("client_snapshot.json");
    private static final Path PENDING_VERSION_FILE = CONFIG_ROOT_DIR.resolve("pending_version.dat");
    private static final Path SYNC_URL_FILE = CONFIG_ROOT_DIR.resolve("last_sync_url.txt");

    // 备份目录指向 Warehouse 目录
    private static final Path BACKUP_DIR = WAREHOUSE_ROOT_DIR.resolve("backup");

    public static class LocalModInfo {
        public String fileName;
        public String hash;
        public String modId;
        public List<String> modIds = new ArrayList<>();
        public boolean isClientOnly;
        public LocalModInfo(String fileName, String hash, String modId, boolean isClientOnly) {
            this.fileName = fileName; this.hash = hash; this.modId = modId; this.isClientOnly = isClientOnly;
            if (modId != null) this.modIds.add(modId);
        }
    }
    public static class ClientSnapshot {
        public String autoModSyncVersion;
        public int version;
        public int revision;
        public ServerSideManager.VerificationStatus verificationStatus;
        public long lastSuccessfulLogin;
        public int provisionalVersion;
        public int provisionalRevision;
        public List<LocalModInfo> files = new ArrayList<>();
    }
    public static class PatchPacket {
        public int targetVersion;
        public int targetRevision;
        public ServerSideManager.VerificationStatus verificationStatus;
        public boolean allowFolderDelete;
        public List<ServerSideManager.ModInfo> toDownload;
        public List<String> toRemove;
        public List<ServerSideManager.ForcedRule> forcedActions;
        public List<ServerSideManager.ModInfo> authorityManifest;
    }
    public static class PendingSyncState {
        public int version;
        public int revision;
        public ServerSideManager.VerificationStatus status;
    }

    public static class ModActionItem {
        String name; Path path; String type; boolean selected; String extraInfo;
        String expectedHash; long expectedSize; String curseForgeUrl; String modrinthUrl;
        List<ModActionItem> subItems;
        public ModActionItem(String name, Path path, String type, boolean defaultSelected) {
            this.name = name; this.path = path; this.type = type; this.selected = defaultSelected;
        }
        public ModActionItem setExtra(String info) { this.extraInfo = info; return this; }
        public ModActionItem setArtifact(ServerSideManager.ModInfo info) {
            if (info != null) {
                this.expectedHash = info.hash; this.expectedSize = info.size;
                this.curseForgeUrl = info.curseForgeUrl; this.modrinthUrl = info.modrinthUrl;
            }
            return this;
        }
        public ModActionItem setSubItems(List<ModActionItem> subs) { this.subItems = subs; return this; }
    }

    private static class SyncCalculationResult {
        List<ModActionItem> actions;
        PatchPacket packet;
        String status1, statusF, status2, status3;
        boolean hasSnapshot;
        boolean overallPassed;
    }

    static {
        // 初始化 Config 目录
        try { if (!Files.exists(CONFIG_ROOT_DIR)) Files.createDirectories(CONFIG_ROOT_DIR); } catch (IOException e) {}
        // 初始化 Warehouse 目录 (保持在根目录)
        try { if (!Files.exists(WAREHOUSE_ROOT_DIR)) Files.createDirectories(WAREHOUSE_ROOT_DIR); } catch (IOException e) {}
        
        WarehouseManager.init(WAREHOUSE_ROOT_DIR);
        loadCachedUrl();
    }

    public static void updateCachedUrl(String url) {
        if (url == null || url.isEmpty()) return;
        if (!url.equals(CACHED_SYNC_URL.get())) {
            CACHED_SYNC_URL.set(url);
            try { Files.writeString(SYNC_URL_FILE, url, StandardCharsets.UTF_8); } catch (IOException e) { e.printStackTrace(); }
        }
    }
    private static void loadCachedUrl() {
        if (Files.exists(SYNC_URL_FILE)) {
            try {
                String url = Files.readString(SYNC_URL_FILE, StandardCharsets.UTF_8).trim();
                if (!url.isEmpty()) CACHED_SYNC_URL.set(url);
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    public static void setServerVersion(int ver, int revision, ServerSideManager.VerificationStatus status) {
        liveServerVersion = ver;
        liveServerRevision = revision;
        liveVerificationStatus = status == null ? ServerSideManager.VerificationStatus.UNVERIFIED : status;
        LOGGER.info("AutoModSync: Received live server version: v{}-r{} ({})", ver, revision, liveVerificationStatus);
    }

    public static void respondToVerification(int candidateVersion, List<String> changedModIds,
                                             List<String> changedServerHashes) {
        CompletableFuture.runAsync(() -> {
            Set<String> requested = changedModIds == null ? Collections.emptySet() : new HashSet<>(changedModIds);
            Set<String> serverHashes = changedServerHashes == null ? Collections.emptySet()
                    : changedServerHashes.stream().filter(Objects::nonNull).map(String::toLowerCase)
                    .collect(Collectors.toSet());
            ClientSnapshot baseline = loadSnapshot();
            boolean baselineKnown = baseline != null && baseline.version > 0
                    && baseline.files != null && !baseline.files.isEmpty();
            Map<String, String> previousById = new HashMap<>();
            Map<String, String> previousByFile = new HashMap<>();
            Set<String> previousHashes = new HashSet<>();
            if (baseline != null && baseline.files != null) {
                for (LocalModInfo old : baseline.files) {
                    List<String> oldIds = old.modIds == null || old.modIds.isEmpty()
                            ? Collections.singletonList(old.modId) : old.modIds;
                    for (String id : oldIds) if (id != null) previousById.put(id, old.hash);
                    if (old.fileName != null) previousByFile.put(old.fileName, old.hash);
                    if (old.hash != null) previousHashes.add(old.hash.toLowerCase());
                }
            }
            Map<String, ServerSideManager.AdminModState> statesById = new LinkedHashMap<>();
            Set<String> currentHashes = new HashSet<>();
            Set<String> currentModIds = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(FMLPaths.MODSDIR.get(), "*.jar")) {
                for (Path path : stream) {
                    Set<String> ids = getModIds(path);
                    String hash = calculateHash(path.toFile());
                    if (!hash.isEmpty()) currentHashes.add(hash.toLowerCase());
                    currentModIds.addAll(ids);
                    boolean administratorAddedClientArtifact = baselineKnown && !hash.isEmpty()
                            && !previousHashes.contains(hash.toLowerCase())
                            && !serverHashes.contains(hash.toLowerCase()) && !ids.isEmpty()
                            && Collections.disjoint(ids, requested);
                    if (administratorAddedClientArtifact) {
                        for (String id : ids) {
                            statesById.put(id, new ServerSideManager.AdminModState(id,
                                    path.getFileName().toString(), hash, previousById.get(id),
                                    true, true, false));
                        }
                        uploadAdministratorArtifact(candidateVersion, path, hash, ids);
                    }
                    for (String id : ids) {
                        if (requested.contains(id)) {
                            String previousHash = previousById.get(id);
                            if (previousHash == null) previousHash = previousByFile.get(path.getFileName().toString());
                            statesById.putIfAbsent(id, new ServerSideManager.AdminModState(id,
                                    path.getFileName().toString(), hash, previousHash,
                                    baselineKnown, false, false));
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("AutoModSync: Failed to prepare administrator verification response", e);
            }
            // During an unverified server update, publish client removals that
            // are unrelated to the server-declared changed mod ids. This is a
            // baseline delta, not a full client-vs-server manifest comparison.
            if (baselineKnown && baseline != null && baseline.files != null) {
                for (LocalModInfo old : baseline.files) {
                    if (old == null || old.hash == null || old.hash.isBlank()
                            || old.fileName == null || old.fileName.isBlank()
                            || currentHashes.contains(old.hash.toLowerCase())) continue;
                    List<String> oldIds = old.modIds == null || old.modIds.isEmpty()
                            ? Collections.singletonList(old.modId) : old.modIds;
                    List<String> validIds = oldIds.stream().filter(Objects::nonNull)
                            .filter(id -> id.matches("[a-z][a-z0-9_]{0,127}"))
                            .distinct().collect(Collectors.toList());
                    if (validIds.isEmpty() || validIds.stream().anyMatch(currentModIds::contains)
                            || validIds.stream().anyMatch(requested::contains)) continue;
                    for (String id : validIds) {
                        statesById.putIfAbsent(id, new ServerSideManager.AdminModState(id,
                                old.fileName, "", old.hash, true, false, true));
                    }
                }
            }
            for (String id : requested) {
                statesById.computeIfAbsent(id, ignored -> new ServerSideManager.AdminModState(
                        id, "", "", previousById.get(id), baselineKnown, false, false));
            }
            ModSyncMain.CHANNEL.sendToServer(new ModSyncMain.VerificationResponsePacket(
                    candidateVersion, new ArrayList<>(statesById.values())));
        });
    }

    private static void uploadAdministratorArtifact(int candidateVersion, Path file, String hash,
                                                     Collection<String> modIds) throws IOException {
        long size = Files.size(file);
        if (size <= 0 || size > 512L * 1024L * 1024L) {
            throw new IOException("administrator client artifact exceeds upload limit: " + file.getFileName());
        }
        ModSyncMain.CHANNEL.sendToServer(new ModSyncMain.AdminArtifactStartPacket(candidateVersion,
                file.getFileName().toString(), hash, size, new ArrayList<>(modIds)));
        byte[] buffer = new byte[30 * 1024];
        long offset = 0;
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] chunk = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                ModSyncMain.CHANNEL.sendToServer(new ModSyncMain.AdminArtifactChunkPacket(
                        candidateVersion, hash, offset, chunk));
                offset += read;
            }
        }
        ModSyncMain.CHANNEL.sendToServer(new ModSyncMain.AdminArtifactCompletePacket(candidateVersion, hash));
    }

    public static void commitAdministratorVerification(int version, int revision) {
        CompletableFuture.runAsync(() -> {
            ClientSnapshot old = loadSnapshot();
            int localVersion = effectiveSnapshotVersion(old);
            SyncCalculationResult result = CACHED_SYNC_URL.get().isBlank()
                    ? null : calculateSyncPlan(CACHED_SYNC_URL.get(), localVersion);
            if (result == null || !result.actions.isEmpty() || !result.overallPassed) {
                LOGGER.info("AutoModSync: Administrator verification published v{}, but local actions remain; snapshot was not advanced",
                        version);
                Minecraft.getInstance().execute(() -> showPostLoginSyncNotice(localVersion, version));
                return;
            }
            List<LocalModInfo> currentFiles = scanLoadedMods();
            PendingSyncState verified = new PendingSyncState();
            verified.version = version;
            verified.revision = revision;
            verified.status = ServerSideManager.VerificationStatus.VERIFIED;
            saveClientSnapshot(currentFiles, verified);
            try { Files.deleteIfExists(PENDING_VERSION_FILE); } catch (IOException ignored) {}
            backupLocalMods(currentFiles);
        });
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // v3.1.3: 添加 .requires(s -> true) 确保 ClickEvent 有权限执行
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("automodsync_open_gui")
                        .requires(s -> true)
                        .executes(context -> {
                            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new SyncInputScreen(null)));
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
        );
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("automodsync_disconnect_update")
                        .requires(s -> true)
                        .executes(context -> {
                            Minecraft.getInstance().execute(() -> {
                                if (Minecraft.getInstance().getConnection() != null) {
                                    Minecraft.getInstance().getConnection().getConnection().disconnect(
                                            Component.literal("§b[AutoModSync] 已主动断开连接\n§f请点击右上角按钮进行更新")
                                    );
                                }
                            });
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
        );
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        new Thread(() -> {
            try {
                for (int attempt = 0; attempt < 60; attempt++) {
                    boolean playerReady = Minecraft.getInstance().player != null
                            && Minecraft.getInstance().getConnection() != null;
                    if (playerReady && liveServerVersion > 0 && !CACHED_SYNC_URL.get().isBlank()) break;
                    Thread.sleep(250);
                }
                if (Minecraft.getInstance().player == null || Minecraft.getInstance().getConnection() == null
                        || liveServerVersion <= 0 || CACHED_SYNC_URL.get().isBlank()) {
                    LOGGER.warn("AutoModSync: Login snapshot refresh skipped because live server metadata was unavailable");
                    return;
                }
                WarehouseManager.init(WAREHOUSE_ROOT_DIR);

                ClientSnapshot old = loadSnapshot();
                int localVer = effectiveSnapshotVersion(old);
                boolean pendingSync = Files.exists(PENDING_VERSION_FILE);
                boolean versionMismatch = old == null || localVer != liveServerVersion || pendingSync;
                if (!versionMismatch) {
                    if (liveVerificationStatus == ServerSideManager.VerificationStatus.VERIFIED
                            && old.verificationStatus != ServerSideManager.VerificationStatus.VERIFIED) {
                        PendingSyncState promoted = new PendingSyncState();
                        promoted.version = liveServerVersion;
                        promoted.revision = liveServerRevision;
                        promoted.status = liveVerificationStatus;
                        List<LocalModInfo> currentFiles = scanLoadedMods();
                        saveClientSnapshot(currentFiles, promoted);
                        backupLocalMods(currentFiles);
                    }
                    LOGGER.info("AutoModSync: Login validation skipped because client pack version already matches v{}",
                            liveServerVersion);
                    return;
                }
                SyncCalculationResult result = calculateSyncPlan(CACHED_SYNC_URL.get(), localVer);
                List<LocalModInfo> currentFiles = scanLoadedMods();
                if (result != null && result.packet != null) {
                    if (result.actions.isEmpty() && result.overallPassed) {
                        PendingSyncState live = new PendingSyncState();
                        live.version = result.packet.targetVersion;
                        live.revision = result.packet.targetRevision;
                        live.status = result.packet.verificationStatus == null
                                ? liveVerificationStatus : result.packet.verificationStatus;
                        saveClientSnapshot(currentFiles, live);
                        Files.deleteIfExists(PENDING_VERSION_FILE);
                        backupLocalMods(currentFiles);
                        LOGGER.info("AutoModSync: Successful login refreshed snapshot to v{}-r{} ({})",
                                live.version, live.revision, live.status);
                    } else {
                        int retainedVersion = old == null ? 0 : localVer;
                        LOGGER.info("AutoModSync: Successful login did not advance the snapshot; {} planned changes remain",
                                result.actions.size());
                        Minecraft.getInstance().execute(() -> showPostLoginSyncNotice(retainedVersion,
                                result.packet.targetVersion));
                    }
                }

            } catch (Exception e) { LOGGER.error("AutoModSync: Failed to refresh snapshot after login", e); }
        }, "ModSync-Snapshot-Thread").start();
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // A following connection must wait for its own VersionPacket instead of
        // accepting metadata left by the previous server.
        liveServerVersion = 0;
        liveServerRevision = 0;
        liveVerificationStatus = ServerSideManager.VerificationStatus.UNVERIFIED;
    }

    private static void showPostLoginSyncNotice(int localVersion, int targetVersion) {
        if (Minecraft.getInstance().player == null) return;
        Minecraft.getInstance().player.displayClientMessage(Component.literal(
                "§b[AutoModSync] §f发现待处理变更 (v" + localVersion + " -> v" + targetVersion + ")"), false);
        Component clickToUpdate = Component.literal("§a§l[点击此处退出服务器并更新]")
                .withStyle(net.minecraft.network.chat.Style.EMPTY
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,
                                "/automodsync_disconnect_update"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击后指令将填入聊天框，请按回车执行"))));
        Minecraft.getInstance().player.displayClientMessage(clickToUpdate, false);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen == null) return;
        String title = screen.getTitle().getString().toLowerCase();
        boolean isErrorScreen = (screen instanceof DisconnectedScreen) ||
                screen.getClass().getName().contains("Mismatch") ||
                title.contains("connection closed") ||
                title.contains("mismatch");
        if (isErrorScreen && !screen.getClass().getName().contains("ConnectScreen")) {
            boolean hasButton = event.getListenersList().stream().anyMatch(l -> l instanceof Button && ((Button) l).getMessage().getString().equals("打开模组同步"));
            if (!hasButton) {
                event.addListener(Button.builder(Component.literal("打开模组同步"), b -> {
                    Minecraft.getInstance().setScreen(new SyncInputScreen(screen));
                }).bounds(screen.width - 110, 10, 100, 20).build());
            }
        }
    }

    private static void analyzeDiff(String rawUrl, SyncInputScreen inputScreen) {
        try {
            ClientSnapshot snapshot = loadSnapshot();
            int localVersion = effectiveSnapshotVersion(snapshot);
            SyncCalculationResult result = calculateSyncPlan(rawUrl, localVersion);

            if (result == null) {
                inputScreen.resetState("连接失败: 无法获取清单");
                return;
            }

            final boolean showWarning = result.actions.size() > 5;

            Minecraft.getInstance().execute(() -> {
                if (result.actions.isEmpty()) {
                    inputScreen.resetState("环境完全一致，无需同步");
                } else {
                    Minecraft.getInstance().setScreen(new SyncResultScreen(inputScreen, result.packet, result.actions, rawUrl, result.status1, result.statusF, result.status2, result.status3, showWarning));
                }
            });
        } catch (Exception e) { e.printStackTrace(); inputScreen.resetState("错误: " + e.getMessage()); }
    }

    private static SyncCalculationResult calculateSyncPlan(String rawUrl, int localVersion) {
        try {
            String baseUrl = rawUrl.trim();
            boolean isIPv6 = baseUrl.chars().filter(ch -> ch == ':').count() > 1;
            boolean hasBrackets = baseUrl.contains("[") && baseUrl.contains("]");
            if (isIPv6 && !hasBrackets) {
                if (baseUrl.startsWith("http://")) baseUrl = "http://[" + baseUrl.substring(7) + "]";
                else if (baseUrl.startsWith("https://")) baseUrl = "https://[" + baseUrl.substring(8) + "]";
                else baseUrl = "http://[" + baseUrl + "]";
            } else {
                if (!baseUrl.startsWith("http")) baseUrl = "http://" + baseUrl;
            }
            if (!baseUrl.endsWith("/")) baseUrl += "/";

            ClientSnapshot snapshot = loadSnapshot();
            Map<String, LocalModInfo> snapshotMap = new HashMap<>();
            if (snapshot != null && snapshot.files != null) {
                for (LocalModInfo info : snapshot.files) snapshotMap.put(info.fileName, info);
            }
            boolean hasSnapshot = !snapshotMap.isEmpty();
            boolean isPendingState = Files.exists(PENDING_VERSION_FILE);

            String json = downloadString(new URL(baseUrl + "diff?ver=" + localVersion));
            if (json == null) return null;

            PatchPacket packet = GSON.fromJson(json, PatchPacket.class);
            Set<String> authorityFileNames = new HashSet<>();
            Map<String, ServerSideManager.ModInfo> authorityByFile = new HashMap<>();
            if (packet.authorityManifest != null) {
                for (ServerSideManager.ModInfo info : packet.authorityManifest) {
                    if (info != null && info.fileName != null) {
                        authorityFileNames.add(info.fileName);
                        authorityByFile.put(info.fileName, info);
                    }
                }
            }
            Map<String, ServerSideManager.ModInfo> serverDownloads = new HashMap<>();
            if (packet.toDownload != null) {
                for (ServerSideManager.ModInfo info : packet.toDownload) {
                    if (info != null && info.fileName != null) serverDownloads.put(info.fileName, info);
                }
            }
            Set<String> serverRemovalTargets = packet.toRemove == null ? new HashSet<>()
                    : new HashSet<>(packet.toRemove);
            List<LocalModInfo> localScanList = scanLocalMods();
            Map<String, LocalModInfo> localMap = new HashMap<>();
            for (LocalModInfo info : localScanList) localMap.put(info.fileName, info);
            Path localModsDir = FMLPaths.MODSDIR.get();
            Path gameDir = FMLPaths.GAMEDIR.get();

            List<ModActionItem> actionItems = new ArrayList<>();
            Set<String> processedFiles = new HashSet<>();
            Set<String> forcedAddTargets = new HashSet<>();
            Map<String, String> forcedAddHashes = new HashMap<>();
            Set<String> forcedDeleteTargets = new HashSet<>();
            Set<String> ignorePatterns = new HashSet<>();

            if (packet.forcedActions != null) {
                for (ServerSideManager.ForcedRule rule : packet.forcedActions) {
                    if ("IGNORE".equals(rule.action)) {
                        ignorePatterns.add(rule.target);
                        continue;
                    }
                    if (rule.target == null) continue;
                    Path p = Paths.get(rule.target);
                    String fname = p.getFileName().toString();
                    String normalizedTarget = rule.target.replace("\\", "/");
                    boolean topLevelModTarget = normalizedTarget.startsWith("mods/")
                            && normalizedTarget.substring("mods/".length()).indexOf('/') < 0;
                    if ("ADD".equals(rule.action) && topLevelModTarget) {
                        forcedAddTargets.add(fname);
                        forcedAddHashes.put(fname, rule.hash);
                    }
                    if ("DELETE".equals(rule.action) && topLevelModTarget) forcedDeleteTargets.add(fname);
                }
            }

            SyncCalculationResult res = new SyncCalculationResult();
            res.packet = packet;
            res.hasSnapshot = hasSnapshot;

            // Step 1: complete client-baseline delta. Additions, deletions and
            // replacements belong here; server/custom/IGNORE-explained changes
            // are subtracted before a local anomaly is reported.
            boolean localConsistent = true;
            boolean unresolvedLocalAnomaly = false;
            if (!hasSnapshot) {
                res.status1 = "§7[本地校验] 暂未开启";
            } else {
                for (LocalModInfo baseline : snapshotMap.values()) {
                    if (baseline == null || baseline.fileName == null
                            || isIgnored(baseline, ignorePatterns)) continue;
                    LocalModInfo current = localMap.get(baseline.fileName);
                    if (current == null) {
                        boolean explained = serverRemovalTargets.contains(baseline.fileName)
                                || forcedDeleteTargets.contains(baseline.fileName);
                        if (!explained) {
                            localConsistent = false;
                            if (baseline.hash != null && WarehouseManager.isHashInWarehouse(baseline.hash)) {
                                ModActionItem restore = new ModActionItem(baseline.fileName, null, "RESTORE", true);
                                restore.expectedHash = baseline.hash;
                                actionItems.add(restore);
                                processedFiles.add(baseline.fileName);
                            } else {
                                ServerSideManager.ModInfo authority = authorityByFile.get(baseline.fileName);
                                if (authority != null && authority.isServerRequired
                                        && hashesEqual(authority.hash, baseline.hash)) {
                                    actionItems.add(new ModActionItem(baseline.fileName, null, "NEW", true)
                                            .setArtifact(authority));
                                    processedFiles.add(baseline.fileName);
                                } else {
                                    unresolvedLocalAnomaly = true;
                                }
                            }
                        }
                        continue;
                    }
                    if (Objects.equals(baseline.hash, current.hash)) continue;
                    ServerSideManager.ModInfo serverChange = serverDownloads.get(current.fileName);
                    ServerSideManager.ModInfo authority = authorityByFile.get(current.fileName);
                    boolean explained = serverChange != null
                            || forcedAddHashes.containsKey(current.fileName)
                            || authority != null && hashesEqual(authority.hash, current.hash);
                    if (!explained) {
                        localConsistent = false;
                        if (baseline.hash != null && WarehouseManager.isHashInWarehouse(baseline.hash)) {
                            ModActionItem restore = new ModActionItem(current.fileName,
                                    localModsDir.resolve(current.fileName), "RESTORE", true);
                            restore.expectedHash = baseline.hash;
                            actionItems.add(restore);
                        } else {
                            ServerSideManager.ModInfo expected = authorityByFile.get(current.fileName);
                            if (expected != null && expected.isServerRequired
                                    && hashesEqual(expected.hash, baseline.hash)) {
                                actionItems.add(new ModActionItem(current.fileName,
                                        localModsDir.resolve(current.fileName), "UPDATE", true)
                                        .setArtifact(expected));
                            } else {
                                actionItems.add(new ModActionItem(current.fileName,
                                        localModsDir.resolve(current.fileName), "ILLEGAL", true));
                                unresolvedLocalAnomaly = true;
                            }
                        }
                        processedFiles.add(current.fileName);
                    }
                }
                for (LocalModInfo current : localScanList) {
                    if (current == null || current.fileName == null
                            || snapshotMap.containsKey(current.fileName)
                            || current.fileName.toLowerCase(Locale.ROOT).contains("automodsync")
                            || isIgnored(current, ignorePatterns)) continue;
                    ServerSideManager.ModInfo serverChange = serverDownloads.get(current.fileName);
                    ServerSideManager.ModInfo authority = authorityByFile.get(current.fileName);
                    boolean explained = serverChange != null
                            || forcedAddHashes.containsKey(current.fileName)
                            || authority != null && hashesEqual(authority.hash, current.hash);
                    if (!explained) {
                        localConsistent = false;
                        actionItems.add(new ModActionItem(current.fileName,
                                localModsDir.resolve(current.fileName), "ILLEGAL", true));
                        processedFiles.add(current.fileName);
                    }
                }
                if (localConsistent && isPendingState) {
                    res.status1 = "§e[本地校验] 变更已解释，等待成功进服";
                } else {
                    res.status1 = localConsistent
                            ? "§a[本地校验] 状态正常" : "§c[本地校验] 状态异常";
                }
            }

            // Step 2.5: Forced Actions
            int forcedCount = 0;
            Map<String, List<ModActionItem>> folderGroups = new HashMap<>();
            if (packet.forcedActions != null) {
                for (ServerSideManager.ForcedRule rule : packet.forcedActions) {
                    if ("IGNORE".equals(rule.action)) continue;
                    if (rule.target == null || rule.target.contains("..")) continue;
                    Path gameRoot = gameDir.toAbsolutePath().normalize();
                    Path targetPath = gameRoot.resolve(rule.target).normalize();
                    if (!targetPath.startsWith(gameRoot)) continue;
                    String displayName = rule.target;

                    if ("DELETE".equals(rule.action)) {
                        if (Files.exists(targetPath)) {
                            actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(targetPath.getFileName().toString()));
                            actionItems.add(new ModActionItem(displayName, targetPath, "FORCED_DELETE", true));
                            forcedCount++;
                            if (displayName.startsWith("mods/")) processedFiles.add(targetPath.getFileName().toString());
                        }
                    } else if ("ADD".equals(rule.action)) {
                        boolean needUpdate = true;
                        if (Files.exists(targetPath)) {
                            String localHash = calculateHash(targetPath.toFile());
                            if (localHash.equals(rule.hash)) needUpdate = false;
                        }
                        if (needUpdate) {
                            ModActionItem item = new ModActionItem(displayName, targetPath, "FORCED_OVERWRITE", true).setExtra(rule.source);
                            item.expectedHash = rule.hash;
                            item.expectedSize = rule.size;
                            Path parent = Paths.get(rule.target).getParent();
                            if (parent != null) {
                                String parentKey = parent.toString().replace("\\", "/");
                                folderGroups.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(item);
                            } else {
                                actionItems.add(item);
                            }
                            forcedCount++;
                            if (displayName.startsWith("mods/")) processedFiles.add(targetPath.getFileName().toString());
                        }
                    }
                }
            }
            for (Map.Entry<String, List<ModActionItem>> entry : folderGroups.entrySet()) {
                List<ModActionItem> items = entry.getValue();
                if (items.size() > 3) {
                    ModActionItem groupItem = new ModActionItem(entry.getKey() + "/ (包含 " + items.size() + " 个文件)", null, "FORCED_OVERWRITE", true);
                    groupItem.setSubItems(items);
                    actionItems.add(groupItem);
                } else {
                    actionItems.addAll(items);
                }
            }
            res.statusF = (forcedCount == 0) ? "§a[强制变更] 暂无变更" : "§e[强制变更] " + forcedCount + "项变更";

            // Step 2: Incremental Sync
            boolean serverHasUpdates = packet.targetVersion != localVersion;
            if (!serverHasUpdates) { res.status2 = "§a[版本同步] 版本一致"; }
            else {
                res.status2 = "§c[版本同步] 有新版本";
                if (packet.toRemove != null) {
                    for (String fileToRemove : packet.toRemove) {
                        actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(fileToRemove));
                        processedFiles.add(fileToRemove);
                        if (localMap.containsKey(fileToRemove)) {
                            actionItems.add(new ModActionItem(fileToRemove, localModsDir.resolve(fileToRemove), "SERVER_REMOVED", true));
                        }
                    }
                }
                if (packet.toDownload != null) {
                    for (ServerSideManager.ModInfo info : packet.toDownload) {
                        actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(info.fileName));
                        if (forcedAddTargets.contains(info.fileName)) continue;

                        if (localMap.containsKey(info.fileName)) {
                            if (!localMap.get(info.fileName).hash.equals(info.hash)) {
                                actionItems.add(new ModActionItem(info.fileName, localModsDir.resolve(info.fileName), "UPDATE", true).setArtifact(info));
                                processedFiles.add(info.fileName);
                            } else { processedFiles.add(info.fileName); }
                        } else {
                            actionItems.add(new ModActionItem(info.fileName, null, "NEW", true).setArtifact(info));
                            processedFiles.add(info.fileName);
                            for (LocalModInfo local : localScanList) {
                                if (local.modId != null && local.modId.equals(info.modId) && !local.fileName.equals(info.fileName)) {
                                    actionItems.add(new ModActionItem(local.fileName, localModsDir.resolve(local.fileName), "SERVER_REMOVED", true));
                                    processedFiles.add(local.fileName);
                                }
                            }
                        }
                    }
                }
            }

            // Step 4: projected qualification light. It never creates ordinary
            // actions; it checks the state after all plans from steps 1-3 run.
            boolean integrityPassed = !unresolvedLocalAnomaly;
            if (packet.authorityManifest != null) {
                for (ServerSideManager.ModInfo info : packet.authorityManifest) {
                    if (info == null || info.fileName == null || !info.isServerRequired
                            || forcedDeleteTargets.contains(info.fileName)
                            || isIgnored(info, ignorePatterns)) continue;
                    LocalModInfo current = localMap.get(info.fileName);
                    boolean alreadyCorrect = current != null && hashesEqual(info.hash, current.hash);
                    boolean willBeCorrect = hasPlannedArtifactAction(actionItems,
                            info.fileName, info.hash)
                            && !hasPlannedAction(actionItems, info.fileName,
                            "ILLEGAL", "SERVER_REMOVED", "FORCED_DELETE");
                    if (!alreadyCorrect && !willBeCorrect) integrityPassed = false;
                }
            }
            if (packet.forcedActions != null) {
                for (ServerSideManager.ForcedRule rule : packet.forcedActions) {
                    if (rule == null || rule.target == null || "IGNORE".equals(rule.action)) continue;
                    Path gameRoot = gameDir.toAbsolutePath().normalize();
                    Path target = gameRoot.resolve(rule.target).normalize();
                    if (!target.startsWith(gameRoot)) {
                        integrityPassed = false;
                        continue;
                    }
                    if ("ADD".equals(rule.action)) {
                        boolean correct = Files.isRegularFile(target)
                                && hashesEqual(rule.hash, calculateHash(target.toFile()));
                        if (!correct && !hasPlannedArtifactAction(actionItems, rule.target, rule.hash)) {
                            integrityPassed = false;
                        }
                    } else if ("DELETE".equals(rule.action) && Files.exists(target)
                            && !hasPlannedAction(actionItems, rule.target, "FORCED_DELETE")) {
                        integrityPassed = false;
                    }
                }
            }
            for (String removal : serverRemovalTargets) {
                if (localMap.containsKey(removal)
                        && !hasPlannedAction(actionItems, removal, "SERVER_REMOVED", "FORCED_DELETE")) {
                    integrityPassed = false;
                }
            }
            res.overallPassed = integrityPassed;
            res.status3 = !hasSnapshot
                    ? "§7[整体校验] 首次成功进服后建立基线"
                    : integrityPassed ? "§a[整体校验] 计划执行后合格" : "§c[整体校验] 仍有未覆盖异常";

            res.actions = actionItems;
            return res;

        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    private static boolean hashesEqual(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean isIgnored(LocalModInfo info, Set<String> patterns) {
        if (info == null || patterns == null) return false;
        return patterns.stream().filter(Objects::nonNull).anyMatch(pattern ->
                info.fileName != null && info.fileName.contains(pattern)
                        || info.modId != null && info.modId.contains(pattern)
                        || info.modIds != null && info.modIds.stream()
                        .anyMatch(id -> id != null && id.contains(pattern)));
    }

    private static boolean isIgnored(ServerSideManager.ModInfo info, Set<String> patterns) {
        if (info == null || patterns == null) return false;
        return patterns.stream().filter(Objects::nonNull).anyMatch(pattern ->
                info.fileName != null && info.fileName.contains(pattern)
                        || info.modId != null && info.modId.contains(pattern)
                        || info.modIds != null && info.modIds.stream()
                        .anyMatch(id -> id != null && id.contains(pattern)));
    }

    private static boolean hasPlannedAction(List<ModActionItem> actions, String target,
                                            String... acceptedTypes) {
        if (target == null || actions == null) return false;
        String fileName;
        try {
            Path name = Paths.get(target).getFileName();
            fileName = name == null ? target : name.toString();
        } catch (Exception ignored) {
            fileName = target;
        }
        Set<String> types = new HashSet<>(Arrays.asList(acceptedTypes));
        for (ModActionItem action : actions) {
            List<ModActionItem> candidates = action.subItems == null
                    ? Collections.singletonList(action) : action.subItems;
            for (ModActionItem candidate : candidates) {
                if (!types.contains(candidate.type) || candidate.name == null) continue;
                if (candidate.name.equals(target) || candidate.name.equals(fileName)
                        || candidate.name.endsWith("/" + fileName)
                        || candidate.path != null && candidate.path.getFileName() != null
                        && candidate.path.getFileName().toString().equals(fileName)) return true;
            }
        }
        return false;
    }

    private static boolean hasPlannedArtifactAction(List<ModActionItem> actions,
                                                    String target, String expectedHash) {
        if (target == null || expectedHash == null || actions == null) return false;
        String fileName;
        try {
            Path name = Paths.get(target).getFileName();
            fileName = name == null ? target : name.toString();
        } catch (Exception ignored) {
            fileName = target;
        }
        for (ModActionItem action : actions) {
            List<ModActionItem> candidates = action.subItems == null
                    ? Collections.singletonList(action) : action.subItems;
            for (ModActionItem candidate : candidates) {
                if (!("NEW".equals(candidate.type) || "UPDATE".equals(candidate.type)
                        || "RESTORE".equals(candidate.type) || "FORCED_OVERWRITE".equals(candidate.type))) continue;
                boolean sameTarget = candidate.name != null && (candidate.name.equals(target)
                        || candidate.name.equals(fileName) || candidate.name.endsWith("/" + fileName))
                        || candidate.path != null && candidate.path.getFileName() != null
                        && candidate.path.getFileName().toString().equals(fileName);
                if (sameTarget && hashesEqual(expectedHash, candidate.expectedHash)) return true;
            }
        }
        return false;
    }

    private static List<LocalModInfo> scanLocalMods() {
        List<LocalModInfo> list = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(FMLPaths.MODSDIR.get(), "*.jar")) {
            for (Path path : stream) {
                Set<String> ids = getModIds(path);
                String primary = ids.stream().findFirst().orElse(null);
                LocalModInfo info = new LocalModInfo(path.getFileName().toString(),
                        calculateHash(path.toFile()), primary, checkClientOnlyComprehensive(path));
                info.modIds = new ArrayList<>(ids);
                list.add(info);
            }
        } catch (IOException e) { e.printStackTrace(); }
        return list;
    }

    /** Snapshot source: only top-level JARs that Forge actually loaded in this successful game run. */
    private static List<LocalModInfo> scanLoadedMods() {
        Map<String, LocalModInfo> loaded = new LinkedHashMap<>();
        Path modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        for (IModInfo mod : ModList.get().getMods()) {
            try {
                Path path = mod.getOwningFile().getFile().getFilePath();
                if (!Files.isRegularFile(path)) continue;
                Path absolute = path.toAbsolutePath().normalize();
                if (!absolute.startsWith(modsDir) || modsDir.relativize(absolute).getNameCount() != 1) continue;
                String fileName = path.getFileName().toString();
                boolean clientOnly = !ModRequirementDetector.detect(mod, path).isClientRequired();
                LocalModInfo existing = loaded.get(fileName);
                if (existing == null) {
                    loaded.put(fileName, new LocalModInfo(fileName, calculateHash(path.toFile()),
                            mod.getModId(), clientOnly));
                } else {
                    if (!existing.modIds.contains(mod.getModId())) existing.modIds.add(mod.getModId());
                    if (!clientOnly) existing.isClientOnly = false;
                }
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(loaded.values());
    }

    /**
     * Finds the one compatibility case that must not silently advance a client
     * snapshot: the client has the same mod id as a server-targeted artifact,
     * but a different SHA-1. A client-only artifact is intentionally ignored;
     * successful Forge login is its compatibility proof.
     */
    private static boolean hasSameModIdDifferentHash(List<LocalModInfo> currentFiles, PatchPacket packet) {
        if (currentFiles == null || packet == null || packet.authorityManifest == null) return false;
        for (ServerSideManager.ModInfo expected : packet.authorityManifest) {
            if (expected == null || !expected.isServerRequired || expected.hash == null) continue;
            List<String> expectedIds = expected.modIds == null || expected.modIds.isEmpty()
                    ? Collections.singletonList(expected.modId) : expected.modIds;
            for (LocalModInfo actual : currentFiles) {
                if (actual == null || actual.hash == null) continue;
                List<String> actualIds = actual.modIds == null || actual.modIds.isEmpty()
                        ? Collections.singletonList(actual.modId) : actual.modIds;
                boolean sameModId = expectedIds.stream().filter(Objects::nonNull).anyMatch(actualIds::contains);
                if (sameModId && !expected.hash.equalsIgnoreCase(actual.hash)) return true;
            }
        }
        return false;
    }

    private static String getModId(Path jarPath) {
        Set<String> ids = getModIds(jarPath);
        return ids.stream().findFirst().orElse(null);
    }

    private static Set<String> getModIds(Path jarPath) {
        Set<String> result = new LinkedHashSet<>();
        String targetFileName = jarPath.getFileName().toString();
        for (IModInfo mod : ModList.get().getMods()) {
            try {
                if (mod.getOwningFile().getFile().getFilePath().getFileName().toString().equals(targetFileName)) {
                    result.add(mod.getModId());
                }
            } catch (Exception ignored) {}
        }
        if (!result.isEmpty()) return result;
        try (ZipFile z = new ZipFile(jarPath.toFile())) {
            ZipEntry t = z.getEntry("META-INF/mods.toml");
            if (t != null) {
                try (InputStream i = z.getInputStream(t)) {
                    String content = new String(i.readAllBytes(), StandardCharsets.UTF_8);
                    boolean inModsSection = false;
                    Pattern p = Pattern.compile("^\\s*modId\\s*=\\s*\"([a-z][a-z0-9_]{0,127})\"");
                    for (String line : content.split("\\R")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("[[")) {
                            inModsSection = trimmed.startsWith("[[mods]]");
                            continue;
                        }
                        if (!inModsSection) continue;
                        Matcher m = p.matcher(line);
                        if (m.find() && !m.group(1).equals("minecraft") && !m.group(1).equals("forge")) {
                            result.add(m.group(1));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static boolean checkClientOnlyComprehensive(Path jarPath) {
        String targetFileName = jarPath.getFileName().toString();
        for (IModInfo mod : ModList.get().getMods()) {
            try {
                if (mod.getOwningFile().getFile().getFilePath().getFileName().toString().equals(targetFileName)) {
                    return !ModRequirementDetector.detect(mod, jarPath).isClientRequired();
                }
            } catch (Exception ignored) {}
        }
        return !ModRequirementDetector.detectJar(jarPath).isClientRequired();
    }

    private static boolean loadedMatchesAuthority(List<LocalModInfo> loadedFiles, PatchPacket packet) {
        if (packet == null || packet.authorityManifest == null || packet.authorityManifest.isEmpty()) return false;

        Map<String, LocalModInfo> loaded = loadedFiles.stream()
                .filter(info -> info != null && info.fileName != null)
                .collect(Collectors.toMap(info -> info.fileName, info -> info, (first, ignored) -> first));
        Map<String, ServerSideManager.ModInfo> authority = packet.authorityManifest.stream()
                .filter(info -> info != null && info.fileName != null)
                .collect(Collectors.toMap(info -> info.fileName, info -> info, (first, ignored) -> first));
        Set<String> forcedDeletes = new HashSet<>();
        Map<String, String> forcedAdds = new HashMap<>();
        Set<String> ignorePatterns = new HashSet<>();
        if (packet.forcedActions != null) {
            for (ServerSideManager.ForcedRule rule : packet.forcedActions) {
                if (rule == null) continue;
                if ("IGNORE".equalsIgnoreCase(rule.action) && rule.target != null) {
                    ignorePatterns.add(rule.target);
                } else if (rule.target != null) {
                    Path target = Paths.get(rule.target);
                    Path name = target.getFileName();
                    if (name == null) continue;
                    if ("DELETE".equalsIgnoreCase(rule.action)) forcedDeletes.add(name.toString());
                    if ("ADD".equalsIgnoreCase(rule.action)) forcedAdds.put(name.toString(), rule.hash);
                }
            }
        }

        for (ServerSideManager.ModInfo expected : authority.values()) {
            if (!expected.isServerRequired || forcedDeletes.contains(expected.fileName)) continue;
            LocalModInfo actual = loaded.get(expected.fileName);
            if (actual == null || expected.hash == null || !expected.hash.equalsIgnoreCase(actual.hash)) return false;
        }

        for (LocalModInfo actual : loaded.values()) {
            ServerSideManager.ModInfo expected = authority.get(actual.fileName);
            if (expected != null && expected.hash != null && expected.hash.equalsIgnoreCase(actual.hash)) continue;
            String forcedHash = forcedAdds.get(actual.fileName);
            if (forcedHash != null && forcedHash.equalsIgnoreCase(actual.hash)) continue;
            boolean ignored = ignorePatterns.stream().anyMatch(pattern -> actual.fileName.contains(pattern)
                    || actual.modId != null && actual.modId.contains(pattern)
                    || actual.modIds != null && actual.modIds.stream().anyMatch(id -> id != null && id.contains(pattern)));
            if (!ignored) return false;
        }
        return true;
    }

    private static synchronized void saveClientSnapshot(List<LocalModInfo> files, PendingSyncState state) {
        ClientSnapshot snapshot = loadSnapshot();
        if (snapshot == null) snapshot = new ClientSnapshot();
        snapshot.autoModSyncVersion = currentAutoModSyncVersion();
        snapshot.verificationStatus = state.status == null
                ? ServerSideManager.VerificationStatus.UNVERIFIED : state.status;
        snapshot.lastSuccessfulLogin = System.currentTimeMillis();
        if (state.status == ServerSideManager.VerificationStatus.VERIFIED) {
            snapshot.files = normalizedSnapshotFiles(files);
            snapshot.version = state.version;
            snapshot.revision = state.revision;
            snapshot.provisionalVersion = 0;
            snapshot.provisionalRevision = 0;
        } else {
            snapshot.provisionalVersion = state.version;
            snapshot.provisionalRevision = state.revision;
        }
        Path part = CLIENT_SNAPSHOT_FILE.resolveSibling(CLIENT_SNAPSHOT_FILE.getFileName() + ".part");
        try {
            Files.createDirectories(CONFIG_ROOT_DIR);
            try (Writer writer = Files.newBufferedWriter(part)) {
                GSON.toJson(snapshot, writer);
            }
            try {
                Files.move(part, CLIENT_SNAPSHOT_FILE,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(part, CLIENT_SNAPSHOT_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("AutoModSync: Failed to save client snapshot", e);
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
        }
    }

    private static String currentAutoModSyncVersion() {
        return ModList.get().getModContainerById(ModSyncMain.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static List<LocalModInfo> normalizedSnapshotFiles(List<LocalModInfo> files) {
        List<LocalModInfo> normalized = files == null ? new ArrayList<>() : new ArrayList<>(files);
        for (LocalModInfo info : normalized) {
            if (info.modIds == null) info.modIds = new ArrayList<>();
            if (info.modId != null && !info.modIds.contains(info.modId)) info.modIds.add(0, info.modId);
            info.modIds.removeIf(Objects::isNull);
            info.modIds = info.modIds.stream().distinct().sorted().collect(Collectors.toList());
            if ((info.modId == null || info.modId.isBlank()) && !info.modIds.isEmpty()) info.modId = info.modIds.get(0);
        }
        normalized.sort(Comparator.comparing(info -> info.fileName == null ? "" : info.fileName,
                String.CASE_INSENSITIVE_ORDER));
        return normalized;
    }

    private static void backupLocalMods(List<LocalModInfo> files) {
        WarehouseManager.init(WAREHOUSE_ROOT_DIR);
        for (LocalModInfo mod : files) {
            WarehouseManager.backupToWarehouse(FMLPaths.MODSDIR.get().resolve(mod.fileName));
        }
    }

    private static int effectiveSnapshotVersion(ClientSnapshot snapshot) {
        if (snapshot == null) return 0;
        return snapshot.provisionalVersion > snapshot.version ? snapshot.provisionalVersion : snapshot.version;
    }

    private static int effectiveSnapshotRevision(ClientSnapshot snapshot) {
        if (snapshot == null) return 0;
        if (snapshot.provisionalVersion >= snapshot.version) return snapshot.provisionalRevision;
        return snapshot.revision;
    }

    private static void savePendingState(PatchPacket packet) throws IOException {
        PendingSyncState pending = new PendingSyncState();
        pending.version = packet.targetVersion;
        pending.revision = packet.targetRevision;
        pending.status = packet.verificationStatus;
        try (Writer writer = Files.newBufferedWriter(PENDING_VERSION_FILE)) { GSON.toJson(pending, writer); }
    }

    private static PendingSyncState loadPendingState() {
        try (Reader reader = Files.newBufferedReader(PENDING_VERSION_FILE)) {
            return GSON.fromJson(reader, PendingSyncState.class);
        } catch (Exception ignored) {
            // Legacy format contained only an integer version.
            try {
                PendingSyncState state = new PendingSyncState();
                state.version = Integer.parseInt(Files.readString(PENDING_VERSION_FILE).trim());
                state.status = ServerSideManager.VerificationStatus.VERIFIED;
                return state;
            } catch (Exception ignoredAgain) { return null; }
        }
    }
    private static ClientSnapshot loadSnapshot() { if(!Files.exists(CLIENT_SNAPSHOT_FILE)) return null; try(Reader r = Files.newBufferedReader(CLIENT_SNAPSHOT_FILE)) { return GSON.fromJson(r, ClientSnapshot.class); } catch(Exception e) { return null; } }
    private static String downloadString(URL url) { try(Scanner s = new Scanner(url.openStream(), StandardCharsets.UTF_8)) { s.useDelimiter("\\A"); return s.hasNext() ? s.next() : null; } catch(IOException e) { return null; } }

    private static String calculateHash(File file) { try(FileInputStream fis = new FileInputStream(file)) { MessageDigest d = MessageDigest.getInstance("SHA-1"); byte[] b = new byte[4096]; int n; while((n=fis.read(b))!=-1) d.update(b,0,n); StringBuilder s = new StringBuilder(); for(byte by:d.digest()) s.append(String.format("%02x",by)); return s.toString(); } catch(Exception e){return "";} }

    // ================== UI Classes ==================

    public static class GuideScreen extends Screen {
        private final Screen parent;
        private static final String GUIDE_TEXT = "第一次启动游戏未成功进入服务器不会启用本地校验以及仓库完整性校验，所以本模组建议在进入服务器前仅依靠服务器改动更新，同时询问管理员获取包含此模组的最新整合包以获取完整更新日志。";
        public GuideScreen(Screen parent) { super(Component.literal("Guide")); this.parent = parent; }
        @Override protected void init() {
            this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.minecraft.setScreen(parent)).bounds(this.width / 2 - 50, this.height - 50, 100, 20).build());
        }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) {
            this.renderBackground(g); super.render(g, mx, my, pt);
            int cx = this.width / 2;
            int cy = this.height / 2;
            g.drawCenteredString(font, "§l使用说明", cx, 40, 0xFFFFFF);
            g.drawWordWrap(font, Component.literal(GUIDE_TEXT), cx - 120, cy - 40, 240, 0xDDDDDD);
            g.drawString(font, "作者: 落叶归秋", 10, this.height - 20, 0xAAAAAA);
            g.drawString(font, "鸣谢: XiaoArin", 10, this.height - 10, 0xAAAAAA);
        }
    }

    public static class SyncInputScreen extends Screen {
        private final Screen parent; private EditBox urlInput; private Button nextBtn; private String statusText = "";
        public SyncInputScreen(Screen parent) { super(Component.literal("Sync Input")); this.parent = parent; }
        @Override protected void init() {
            int cx = this.width / 2; int cy = this.height / 2;
            this.addRenderableWidget(new StringWidget(cx - 100, cy - 60, 200, 10, Component.literal("请输入IP"), this.font));
            this.addRenderableWidget(new StringWidget(cx - 100, cy - 50, 200, 10, Component.literal("§7(联系服务器管理员获取同步IP)"), this.font));
            urlInput = new EditBox(this.font, cx - 100, cy - 35, 200, 20, Component.literal("Sync URL"));
            urlInput.setMaxLength(256); urlInput.setValue(CACHED_SYNC_URL.get());
            this.addRenderableWidget(urlInput);
            nextBtn = Button.builder(Component.literal("下一步: 校验"), b -> startCheck()).bounds(cx - 100, cy, 200, 20).build();
            this.addRenderableWidget(nextBtn);
            this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.minecraft.setScreen(parent)).bounds(cx - 50, this.height - 50, 100, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("?"), b -> { this.minecraft.setScreen(new GuideScreen(this)); }).bounds(this.width - 30, 10, 20, 20).build());
        }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) {
            renderBackground(g); super.render(g, mx, my, pt);
            if (!statusText.isEmpty()) g.drawCenteredString(font, statusText, this.width / 2, this.height / 2 + 30, 0xFF5555);
        }
        private void startCheck() {
            String val = urlInput.getValue(); if (val.isEmpty()) return;
            if (!val.equals(CACHED_SYNC_URL.get())) updateCachedUrl(val);
            nextBtn.active = false; nextBtn.setMessage(Component.literal("正在分析...")); statusText = "";
            CompletableFuture.runAsync(() -> analyzeDiff(val, this));
        }
        public void resetState(String errorMsg) {
            Minecraft.getInstance().execute(() -> { this.statusText = errorMsg; this.nextBtn.active = true; this.nextBtn.setMessage(Component.literal("下一步: 校验")); });
        }
    }

    public static class SyncResultScreen extends Screen {
        private final Screen parent; private final List<String> toDownload; private final List<ModActionItem> actionItems;
        private final String baseUrl; private final String s1, sF, s2, s3;
        private ModListWidget modList; private Button execBtn; private boolean isExecuting = false; private boolean isFinished = false;
        private boolean hasPartialCompletion = false;
        private final PatchPacket packet;
        private final boolean showWarning;
        private volatile int completedTasks = 0;
        private volatile int totalTasks = 0;
        private volatile long currentBytes = 0;
        private volatile long currentTotalBytes = 0;
        private volatile String currentFile = "";
        private volatile String currentSource = "";

        public SyncResultScreen(Screen p, PatchPacket pkt, List<ModActionItem> items, String url, String s1, String sF, String s2, String s3, boolean showWarning) {
            super(Component.literal("Results")); this.parent = p; this.packet = pkt; this.toDownload = new ArrayList<>(); this.actionItems = items; this.baseUrl = url;
            this.s1 = s1; this.sF = sF; this.s2 = s2; this.s3 = s3; this.showWarning = showWarning;
        }
        @Override protected void init() {
            int cx = this.width / 2;
            modList = new ModListWidget(this.minecraft, this.width, this.height, 65, this.height - 60, 24);
            actionItems.sort(Comparator.comparingInt(this::getTypePriority));
            String lastType = "";
            for (ModActionItem item : actionItems) {
                if (!item.type.equals(lastType)) { lastType = item.type; modList.add(new CategoryEntry(getCategoryTitle(lastType))); }
                modList.add(new ActionEntry(item));
            }
            this.addRenderableWidget(modList);
            if (!isFinished) {
                String executeText = hasPartialCompletion ? "继续同步剩余项目"
                        : packet.verificationStatus == ServerSideManager.VerificationStatus.UNVERIFIED
                        ? "[仍然同步 v" + packet.targetVersion + "]" : "执行同步 v" + packet.targetVersion;
                execBtn = Button.builder(Component.literal(executeText), b -> startExecution()).bounds(cx - 105, this.height - 30, 210, 20).build();
            }
            else { execBtn = Button.builder(Component.literal("同步完成 - 点击退出游戏"), b -> System.exit(0)).bounds(cx - 105, this.height - 30, 210, 20).build(); }
            if (isExecuting) execBtn.active = false;
            this.addRenderableWidget(execBtn);
            this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.minecraft.setScreen(parent)).bounds(cx - 50, this.height - 55, 100, 20).build());
        }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) {
            this.renderBackground(g); if (modList != null) modList.render(g, mx, my, pt); super.render(g, mx, my, pt);
            int cx = this.width / 2;
            int cy = 10;
            g.drawCenteredString(font, "§l同步清单确认", cx, cy, 0xFFFFFF);
            int colOffset = 140;
            int rowHeight = 12;
            int startY = cy + 16;
            g.drawCenteredString(font, s1, cx - colOffset/2, startY, 0xFFFFFF);
            g.drawCenteredString(font, sF, cx + colOffset/2, startY, 0xFFFFFF);
            g.drawCenteredString(font, s2, cx - colOffset/2, startY + rowHeight, 0xFFFFFF);
            g.drawCenteredString(font, s3, cx + colOffset/2, startY + rowHeight, 0xFFFFFF);

            if (showWarning) {
                g.drawString(font, "§e⚠ 检测到改动过多 (>5)", 6, this.height - 55, 0xFFFFFF);
                g.drawString(font, "§e建议查看有无更新整合包", 6, this.height - 44, 0xFFFFFF);
            }
            if (packet.verificationStatus == ServerSideManager.VerificationStatus.UNVERIFIED) {
                String warning1 = "§e服务器当前版本未经管理员验证";
                String warning2 = "§e请谨慎更新或联系管理员确认";
                g.drawString(font, warning1, this.width - font.width(warning1) - 6, this.height - 55, 0xFFFFFF);
                g.drawString(font, warning2, this.width - font.width(warning2) - 6, this.height - 44, 0xFFFFFF);
            }
            if (isExecuting) {
                int left = cx - 105;
                int top = this.height - 30;
                int width = 210;
                double fileRatio = currentTotalBytes > 0 ? Math.min(1.0, (double) currentBytes / currentTotalBytes) : 0.0;
                double taskRatio = totalTasks > 0 ? Math.min(1.0,
                        (completedTasks + fileRatio) / totalTasks) : 0.0;
                g.fill(left, top, left + width, top + 20, 0xFF555555);
                g.fill(left + 1, top + 1, left + 1 + (int) ((width - 2) * taskRatio), top + 19, 0xFF35A853);
                int shownTask = totalTasks > 0 ? Math.min(totalTasks, completedTasks + 1) : 0;
                String progress = shownTask + "/" + totalTasks + " · " + shortSource(currentSource);
                if (currentFile != null && !currentFile.isBlank()) progress += " · " + currentFile;
                g.drawCenteredString(font, fitToWidth(progress, width - 8), cx, top + 6, 0xFFFFFF);
            }
        }

        private String shortSource(String source) {
            if (source == null || source.isBlank()) return "处理中";
            return switch (source) {
                case "本地 SHA-1 仓库" -> "本地";
                case "CurseForge" -> "CF";
                case "Modrinth" -> "MR";
                case "服务器仓库" -> "服务器";
                case "服务器强制文件" -> "服务器";
                default -> source;
            };
        }

        private String fitToWidth(String value, int maxWidth) {
            if (value == null || value.isEmpty()) return "";
            if (font.width(value) <= maxWidth) return value;
            String suffix = "...";
            return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix))) + suffix;
        }
        private void startExecution() {
            if (flattenSelectedTasks().isEmpty()) {
                execBtn.setMessage(Component.literal("请先选择同步项目"));
                return;
            }
            final boolean fullSelection = actionItems.stream().allMatch(item -> item.selected);
            isExecuting = true;
            execBtn.active = false;
            execBtn.visible = false;
            CompletableFuture.runAsync(() -> {
                StringBuilder errorLog = new StringBuilder();
                try {
                    Files.deleteIfExists(PENDING_VERSION_FILE);
                    WarehouseManager.init(WAREHOUSE_ROOT_DIR);
                    if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
                    Path localModsDir = FMLPaths.MODSDIR.get();
                    String effectiveBaseUrl = normalizeBaseUrl(baseUrl);
                    List<ModActionItem> tasks = flattenSelectedTasks();
                    List<ModActionItem> preflight = tasks.stream().filter(this::requiresPreparedArtifact).collect(Collectors.toList());
                    totalTasks = tasks.size() + preflight.size();
                    completedTasks = 0;
                    Map<ModActionItem, Path> forcedStaging = new IdentityHashMap<>();

                    // Phase one: acquire and verify every artifact before mutating the live game directory.
                    for (ModActionItem task : preflight) {
                        currentFile = task.name;
                        if (task.type.equals("FORCED_OVERWRITE")) {
                            forcedStaging.put(task, prepareForcedFile(task, effectiveBaseUrl));
                        } else {
                            ensureModArtifactCached(task, effectiveBaseUrl);
                        }
                        completedTasks++;
                    }

                    // Phase two: apply the already verified transaction.
                    for (ModActionItem task : tasks) {
                        currentFile = task.name;
                        currentSource = "安装";
                        currentBytes = 0;
                        currentTotalBytes = 0;
                        Path target = task.path;
                        if (target == null && (task.type.equals("NEW") || task.type.equals("RESTORE"))) {
                            target = localModsDir.resolve(task.name);
                        }
                        if (target != null) target = requireGamePath(target);

                        if (isRemovalTask(task)) {
                            backupOrDelete(target, packet.allowFolderDelete);
                        }
                        if (task.type.equals("FORCED_OVERWRITE")) {
                            Path staged = forcedStaging.get(task);
                            if (target.getParent() != null) Files.createDirectories(target.getParent());
                            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
                            Files.deleteIfExists(staged);
                        } else if (task.type.equals("RESTORE") || task.type.equals("NEW") || task.type.equals("UPDATE")) {
                            if (!WarehouseManager.restoreByHash(task.expectedHash, target)) {
                                throw new IOException("Verified warehouse artifact missing: " + task.name);
                            }
                        }
                        completedTasks++;
                    }
                    if (fullSelection) {
                        savePendingState(packet);
                    }
                    Minecraft.getInstance().execute(() -> {
                        isExecuting = false;
                        if (fullSelection) {
                            isFinished = true;
                        } else {
                            actionItems.removeIf(item -> item.selected);
                            hasPartialCompletion = true;
                        }
                        this.clearWidgets();
                        this.init();
                    });
                } catch (Exception e) {
                    LOGGER.error("AutoModSync transaction failed before completion", e);
                    errorLog.append("同步失败: ").append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    Minecraft.getInstance().execute(() -> {
                        isExecuting = false;
                        execBtn.visible = true;
                        execBtn.active = true;
                        execBtn.setMessage(Component.literal("同步失败 - 点击重试"));
                    });
                }
            });
        }

        private List<ModActionItem> flattenSelectedTasks() {
            List<ModActionItem> result = new ArrayList<>();
            for (ModActionItem item : actionItems) {
                if (!item.selected) continue;
                if (item.subItems != null) result.addAll(item.subItems);
                else result.add(item);
            }
            return result;
        }

        private boolean requiresPreparedArtifact(ModActionItem task) {
            return task.type.equals("FORCED_OVERWRITE") || task.type.equals("RESTORE")
                    || task.type.equals("NEW") || task.type.equals("UPDATE");
        }

        private boolean isRemovalTask(ModActionItem task) {
            return task.type.equals("SERVER_REMOVED") || task.type.equals("ILLEGAL")
                    || task.type.equals("UPDATE") || task.type.equals("FORCED_DELETE");
        }

        private void ensureModArtifactCached(ModActionItem task, String effectiveBaseUrl) throws IOException {
            if (task.expectedHash != null && WarehouseManager.isHashInWarehouse(task.expectedHash)) {
                currentSource = "本地 SHA-1 仓库";
                currentBytes = currentTotalBytes = Math.max(0, task.expectedSize);
                return;
            }
            List<Map.Entry<String, String>> sources = new ArrayList<>();
            if (task.curseForgeUrl != null && !task.curseForgeUrl.isBlank())
                sources.add(new AbstractMap.SimpleEntry<>("CurseForge", task.curseForgeUrl));
            if (task.modrinthUrl != null && task.modrinthUrl.startsWith("https://cdn.modrinth.com/"))
                sources.add(new AbstractMap.SimpleEntry<>("Modrinth", task.modrinthUrl));
            String serverKey = task.expectedHash != null && task.expectedHash.matches("(?i)[0-9a-f]{40}")
                    ? task.expectedHash : URLEncoder.encode(task.name, StandardCharsets.UTF_8).replace("+", "%20");
            sources.add(new AbstractMap.SimpleEntry<>("服务器仓库", effectiveBaseUrl + "download/" + serverKey));

            Path stagingDir = WAREHOUSE_ROOT_DIR.resolve("downloads");
            Files.createDirectories(stagingDir);
            IOException last = null;
            for (Map.Entry<String, String> source : sources) {
                Path part = stagingDir.resolve(UUID.randomUUID() + ".part");
                try {
                    currentSource = source.getKey();
                    currentBytes = 0;
                    currentTotalBytes = Math.max(0, task.expectedSize);
                    downloadToFile(new URL(source.getValue()), part);
                    String actualHash = calculateHash(part.toFile());
                    if (task.expectedHash != null && !task.expectedHash.isBlank()
                            && !task.expectedHash.equalsIgnoreCase(actualHash)) {
                        throw new IOException("SHA-1 mismatch from " + source.getKey());
                    }
                    task.expectedHash = actualHash;
                    String stored = WarehouseManager.store(part, task.name);
                    if (!actualHash.equalsIgnoreCase(stored)) throw new IOException("warehouse store failed");
                    Files.deleteIfExists(part);
                    return;
                } catch (IOException e) {
                    last = e;
                    Files.deleteIfExists(part);
                }
            }
            throw last == null ? new IOException("No download source for " + task.name) : last;
        }

        private Path prepareForcedFile(ModActionItem task, String effectiveBaseUrl) throws IOException {
            if (task.expectedHash == null || !task.expectedHash.matches("(?i)[0-9a-f]{40}")) {
                throw new IOException("Forced file has no valid SHA-1: " + task.name);
            }
            Path stagingDir = WAREHOUSE_ROOT_DIR.resolve("downloads");
            Files.createDirectories(stagingDir);
            Path part = stagingDir.resolve(UUID.randomUUID() + ".forced.part");
            if (WarehouseManager.restoreByHash(task.expectedHash, part)) {
                currentSource = "本地 SHA-1 仓库";
                currentBytes = currentTotalBytes = Math.max(0, task.expectedSize);
                return part;
            }
            currentSource = "服务器 SHA-1 仓库";
            currentBytes = 0;
            currentTotalBytes = Math.max(0, task.expectedSize);
            downloadToFile(new URL(effectiveBaseUrl + "download/" + task.expectedHash), part);
            String actualHash = calculateHash(part.toFile());
            if (!task.expectedHash.equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(part);
                throw new IOException("Forced file SHA-1 mismatch: " + task.name);
            }
            String originalName;
            try {
                Path name = Paths.get(task.name).getFileName();
                originalName = name == null ? "custom.bin" : name.toString();
            } catch (Exception ignored) {
                originalName = "custom.bin";
            }
            String stored = WarehouseManager.store(part, originalName);
            if (!task.expectedHash.equalsIgnoreCase(stored)) {
                Files.deleteIfExists(part);
                throw new IOException("Forced warehouse store failed: " + task.name);
            }
            return part;
        }

        private void downloadToFile(URL url, Path target) throws IOException {
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "AutoModSync/3.3.0");
            if (connection instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(true);
                int status = http.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            }
            long declared = connection.getContentLengthLong();
            if (declared > 0) currentTotalBytes = declared;
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    currentBytes += read;
                }
            }
        }

        private void backupOrDelete(Path target, boolean allowFolderDelete) throws IOException {
            if (target == null || !Files.exists(target)) return;
            if (Files.isDirectory(target)) {
                if (!allowFolderDelete) throw new IOException("Directory deletion is disabled: " + target);
                deleteDirectoryRecursively(target);
                return;
            }
            try {
                Files.move(target, BACKUP_DIR.resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                Path old = target.resolveSibling(target.getFileName() + ".old");
                Files.move(target, old, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private Path requireGamePath(Path target) throws IOException {
            Path root = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Path normalized = target.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) throw new IOException("Target escapes game directory: " + target);
            return normalized;
        }

        private String normalizeBaseUrl(String value) {
            String effective = value.trim();
            if (effective.contains(":") && !effective.contains("[")
                    && effective.chars().filter(ch -> ch == ':').count() > 1) {
                if (effective.startsWith("http://")) effective = "http://[" + effective.substring(7) + "]";
                else if (effective.startsWith("https://")) effective = "https://[" + effective.substring(8) + "]";
                else effective = "http://[" + effective + "]";
            }
            if (!effective.startsWith("http")) effective = "http://" + effective;
            if (!effective.endsWith("/")) effective += "/";
            return effective;
        }

        private void deleteDirectoryRecursively(Path dir) throws IOException {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private int getTypePriority(ModActionItem item) {
            switch (item.type) {
                case "FORCED_DELETE": return 0; case "FORCED_OVERWRITE": return 0;
                case "RESTORE": return 1; case "NEW": return 2; case "UPDATE": return 2;
                case "SERVER_REMOVED": return 3; case "ILLEGAL": return 4; default: return 5;
            }
        }
        private String getCategoryTitle(String type) {
            switch (type) {
                case "FORCED_DELETE": return "§c[强制删除] (策略要求)";
                case "FORCED_OVERWRITE": return "§e[强制覆盖] (策略要求)";
                case "RESTORE": return "§b[本地自愈] (误删恢复)"; case "NEW": return "§a[新增模组] (下载)";
                case "UPDATE": return "§6[版本更新] (替换)"; case "SERVER_REMOVED": return "§c[服务端移除] (删除)";
                case "ILLEGAL": return "§4[仓库清理] (未授权/残留)"; default: return "§7[其他]";
            }
        }
    }
    public static class ModListWidget extends ObjectSelectionList<ModListWidget.BaseEntry> {
        public ModListWidget(Minecraft mc, int w, int h, int t, int b, int ih) { super(mc, w, h, t, b, ih); }
        @Override public int getRowWidth() { return 300; }
        @Override protected int getScrollbarPosition() { return this.width / 2 + 150; }
        public void add(BaseEntry e) { this.addEntry(e); }
        public abstract static class BaseEntry extends ObjectSelectionList.Entry<BaseEntry> { @Override public Component getNarration() { return Component.empty(); } }
    }
    public static class CategoryEntry extends ModListWidget.BaseEntry {
        private final String text; public CategoryEntry(String text) { this.text = text; }
        @Override public void render(GuiGraphics g, int idx, int t, int l, int w, int h, int mx, int my, boolean hov, float pt) { g.drawCenteredString(Minecraft.getInstance().font, text, l + w / 2, t + 12, 0xFFFFFF); }
    }
    public static class ActionEntry extends ModListWidget.BaseEntry {
        private final ModActionItem item; public ActionEntry(ModActionItem item) { this.item = item; }
        @Override public void render(GuiGraphics g, int idx, int t, int l, int w, int h, int mx, int my, boolean hov, float pt) {
            g.fill(l + 5, t + 2, l + 25, t + 22, 0xFF888888); g.fill(l + 6, t + 3, l + 24, t + 21, 0xFF000000);
            if (item.selected) { g.pose().pushPose(); g.pose().translate(l + 15, t + 7, 0); g.drawCenteredString(Minecraft.getInstance().font, "§aV", 0, 0, 0xFFFFFF); g.pose().popPose(); }
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String label = item.name;
            int available = Math.max(10, w - 38);
            if (font.width(label) > available) {
                label = font.plainSubstrByWidth(label, Math.max(0, available - font.width("..."))) + "...";
            }
            g.drawString(font, label, l + 30, t + 8, 0xFFFFFF);
        }
        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 0 && mx < 280) { item.selected = !item.selected; Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F)); return true; } return false;
        }
    }
}
