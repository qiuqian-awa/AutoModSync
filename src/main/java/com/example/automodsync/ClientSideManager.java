package com.example.automodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static volatile int liveServerVersion = 0;
    private static volatile boolean clientLoginEventSeen = false;
    private static final AtomicInteger CONNECTION_GENERATION = new AtomicInteger();

    // ================== 路径分离逻辑 (v3.1.3) ==================
    // 1. 配置文件路径：config/automodsync/
    private static final Path CONFIG_ROOT_DIR = FMLPaths.CONFIGDIR.get().resolve("automodsync");
    // 2. 仓库备份路径：游戏根目录/automodsync_client_storage/ (保持不动)
    private static final Path WAREHOUSE_ROOT_DIR = FMLPaths.GAMEDIR.get().resolve("automodsync_client_storage");

    // 具体文件指向 Config 目录
    private static final Path CLIENT_SNAPSHOT_FILE = CONFIG_ROOT_DIR.resolve("client_snapshot.json");
    private static final Path PENDING_SYNC_FILE = CONFIG_ROOT_DIR.resolve("pending_sync.json");
    private static final Path LEGACY_PENDING_VERSION_FILE = CONFIG_ROOT_DIR.resolve("pending_version.dat");
    private static final Path SYNC_URL_FILE = CONFIG_ROOT_DIR.resolve("last_sync_url.txt");

    // 备份目录指向 Warehouse 目录
    private static final Path BACKUP_DIR = WAREHOUSE_ROOT_DIR.resolve("backup");

    public static class LocalModInfo {
        public String fileName;
        /** Legacy SHA-1 field. */
        public String hash;
        public String sha256;
        public long size = -1L;
        public String modId;
        public boolean isClientOnly;
        public List<String> officialUrls = new ArrayList<>();
        public LocalModInfo(String fileName, String hash, String sha256, long size, String modId, boolean isClientOnly) {
            this.fileName = fileName; this.hash = hash; this.sha256 = sha256; this.size = size;
            this.modId = modId; this.isClientOnly = isClientOnly;
        }
    }
    public static class ClientSnapshot {
        public int version;
        public String syncUrl;
        public List<LocalModInfo> files = new ArrayList<>();
    }
    public static class PendingSync {
        public int targetVersion;
        public String syncUrl;
        public long completedAt;
        public List<ServerSideManager.ModInfo> authorityManifest = new ArrayList<>();
        public List<ServerSideManager.ForcedRule> forcedActions = new ArrayList<>();
    }
    public static class PatchPacket {
        public int targetVersion;
        public boolean allowFolderDelete;
        public List<ServerSideManager.ModInfo> toDownload;
        public List<String> toRemove;
        public List<ServerSideManager.ForcedRule> forcedActions;
        public List<ServerSideManager.ModInfo> authorityManifest;
    }

    public static class ModActionItem {
        String name; Path path; String type; boolean selected; String extraInfo;
        List<ModActionItem> subItems;
        ServerSideManager.ModInfo modInfo;
        ServerSideManager.ForcedRule forcedRule;
        String expectedSha1;
        String expectedSha256;
        long expectedSize = -1L;
        public ModActionItem(String name, Path path, String type, boolean defaultSelected) {
            this.name = name; this.path = path; this.type = type; this.selected = defaultSelected;
        }
        public ModActionItem setExtra(String info) { this.extraInfo = info; return this; }
        public ModActionItem setSubItems(List<ModActionItem> subs) { this.subItems = subs; return this; }
        public ModActionItem setModInfo(ServerSideManager.ModInfo info) { this.modInfo = info; return this; }
        public ModActionItem setForcedRule(ServerSideManager.ForcedRule rule) { this.forcedRule = rule; return this; }
        public ModActionItem expect(String sha1, String sha256, long size) {
            this.expectedSha1 = sha1; this.expectedSha256 = sha256; this.expectedSize = size; return this;
        }
    }

    private static class SyncCalculationResult {
        List<ModActionItem> actions;
        PatchPacket packet;
        String status1, statusF, status2, status3;
        boolean hasSnapshot;
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

    public static void setServerVersion(int ver) {
        liveServerVersion = ver;
        int generation = CONNECTION_GENERATION.incrementAndGet();
        LOGGER.info("AutoModSync: Received live server version: v" + ver);
        scheduleConnectionSnapshotRefresh(ver, generation);
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
        clientLoginEventSeen = true;
        Thread snapshotThread = new Thread(() -> {
            try {
                for (int attempt = 0; attempt < 120 && liveServerVersion <= 0; attempt++) Thread.sleep(1000);
                boolean stableConnection = waitForStableServerConnection();
                WarehouseManager.init(WAREHOUSE_ROOT_DIR);

                if (Files.deleteIfExists(LEGACY_PENDING_VERSION_FILE)) {
                    LOGGER.warn("AutoModSync: Removed an unverifiable legacy pending_version.dat; a new verified sync is required");
                }

                PendingSync pending = loadPendingSync();
                if (stableConnection && pending != null && pending.targetVersion > 0
                        && liveServerVersion == pending.targetVersion
                        && sameServerUrl(CACHED_SYNC_URL.get(), pending.syncUrl)) {
                    List<LocalModInfo> currentFiles = scanLocalMods();
                    String validationError = validateExpectedEnvironment(currentFiles,
                            pending.authorityManifest, pending.forcedActions, true);
                    if (validationError == null) {
                        mergeOfficialSources(currentFiles, pending.authorityManifest);
                        backupVerifiedMods(currentFiles);
                        saveClientSnapshot(currentFiles, pending.targetVersion);
                        Files.deleteIfExists(PENDING_SYNC_FILE);
                        LOGGER.info("AutoModSync: Verified and committed snapshot V{}", pending.targetVersion);
                        return;
                    }
                    LOGGER.error("AutoModSync: Pending sync was not committed: {}", validationError);
                }

                ClientSnapshot old = loadSnapshotForServer(CACHED_SYNC_URL.get());
                int localVer = (old != null) ? old.version : 1;

                if (liveServerVersion > localVer || old == null) {
                    String url = CACHED_SYNC_URL.get();
                    if (!url.isEmpty()) {
                        SyncCalculationResult result = calculateSyncPlan(url, localVer);
                        if (result != null) {
                            if (result.actions.isEmpty()) {
                                List<LocalModInfo> currentFiles = scanLocalMods();
                                String validationError = validateExpectedEnvironment(currentFiles,
                                        result.packet.authorityManifest, result.packet.forcedActions, stableConnection);
                                if (validationError == null) {
                                    mergeOfficialSources(currentFiles, result.packet.authorityManifest);
                                    backupVerifiedMods(currentFiles);
                                    saveClientSnapshot(currentFiles, result.packet.targetVersion);
                                    LOGGER.info("AutoModSync: Verified silent update to V{}", result.packet.targetVersion);
                                } else {
                                    LOGGER.error("AutoModSync: Refused silent snapshot: {}", validationError);
                                }
                            } else {
                                // v3.1.3: 可点击的退出更新提示
                                Component clickToUpdate = Component.literal("§a§l[点击此处退出服务器并更新]")
                                        .withStyle(net.minecraft.network.chat.Style.EMPTY
                                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/automodsync_disconnect_update"))
                                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                                        Component.literal("点击后指令将填入下方聊天框，请手动按回车执行")
                                                )));
                                Minecraft.getInstance().execute(() -> {
                                    if (Minecraft.getInstance().player != null) {
                                        Minecraft.getInstance().player.displayClientMessage(
                                                Component.literal("§b[AutoModSync] §f发现需要同步 (v" + localVer + " -> v" + liveServerVersion + ")"), false);
                                        Minecraft.getInstance().player.displayClientMessage(clickToUpdate, false);
                                    }
                                });
                            }
                        }
                    }
                }

            } catch (Exception e) { e.printStackTrace(); }
        }, "ModSync-Snapshot-Thread");
        snapshotThread.setDaemon(true);
        snapshotThread.start();
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clientLoginEventSeen = false;
        liveServerVersion = 0;
        CONNECTION_GENERATION.incrementAndGet();
    }

    private static void scheduleConnectionSnapshotRefresh(int expectedVersion, int generation) {
        Thread refreshThread = new Thread(() -> {
            try {
                if (!waitForStableServerConnection()) {
                    LOGGER.warn("AutoModSync: Compatible snapshot was not refreshed because the connection did not remain stable");
                    return;
                }
                if (generation != CONNECTION_GENERATION.get() || liveServerVersion != expectedVersion) return;

                String syncUrl = CACHED_SYNC_URL.get();
                if (syncUrl == null || syncUrl.isBlank()) {
                    LOGGER.warn("AutoModSync: Compatible snapshot was not refreshed because no sync URL was received");
                    return;
                }
                ClientSnapshot previous = loadSnapshotForServer(syncUrl);
                int localVersion = previous == null ? 1 : previous.version;
                SyncCalculationResult result = calculateSyncPlan(syncUrl, localVersion);
                if (result == null || result.packet == null || result.packet.targetVersion != expectedVersion) {
                    LOGGER.warn("AutoModSync: Compatible snapshot was not refreshed because the authority manifest was unavailable");
                    return;
                }

                List<LocalModInfo> currentFiles = scanLocalMods();
                String validationError = validateExpectedEnvironment(currentFiles,
                        result.packet.authorityManifest, result.packet.forcedActions, true);
                if (validationError != null) {
                    LOGGER.error("AutoModSync: Refused compatible environment refresh: {}", validationError);
                    return;
                }
                if (generation != CONNECTION_GENERATION.get() || !isConnectionProofFor(syncUrl, expectedVersion)) return;

                mergeOfficialSources(currentFiles, result.packet.authorityManifest);
                backupVerifiedMods(currentFiles);
                saveClientSnapshot(currentFiles, expectedVersion);
                LOGGER.info("AutoModSync: Refreshed compatible local environment for V{} with {} files",
                        expectedVersion, currentFiles.size());
            } catch (Exception e) {
                LOGGER.error("AutoModSync: Compatible snapshot refresh failed", e);
            }
        }, "ModSync-Compatible-Snapshot");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private static boolean waitForStableServerConnection() throws InterruptedException {
        int stableChecks = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            if (hasActiveServerConnection()) {
                stableChecks++;
                if (stableChecks >= 30) return true;
            } else {
                stableChecks = 0;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static boolean hasActiveServerConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return clientLoginEventSeen && minecraft.getConnection() != null;
    }

    private static boolean isConnectionProofFor(String syncUrl, int targetVersion) {
        return hasActiveServerConnection()
                && liveServerVersion == targetVersion
                && sameServerUrl(CACHED_SYNC_URL.get(), syncUrl);
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
            ClientSnapshot snapshot = loadSnapshotForServer(rawUrl);
            int localVersion = (snapshot != null) ? snapshot.version : 1;
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
            String baseUrl = normalizeBaseUrl(rawUrl);

            ClientSnapshot snapshot = loadSnapshotForServer(baseUrl);
            Map<String, LocalModInfo> snapshotMap = new HashMap<>();
            if (snapshot != null && snapshot.files != null) {
                for (LocalModInfo info : snapshot.files) snapshotMap.put(info.fileName, info);
            }
            boolean hasSnapshot = !snapshotMap.isEmpty();
            boolean isPendingState = Files.exists(PENDING_SYNC_FILE);

            String json = downloadString(new URL(baseUrl + "diff?ver=" + localVersion));
            if (json == null) return null;

            PatchPacket packet = GSON.fromJson(json, PatchPacket.class);
            validatePatchPacket(packet);
            boolean provenByCurrentConnection = isConnectionProofFor(baseUrl, packet.targetVersion);
            List<LocalModInfo> localScanList = scanLocalMods();
            Map<String, LocalModInfo> localMap = new HashMap<>();
            for (LocalModInfo info : localScanList) localMap.put(info.fileName, info);
            Path localModsDir = FMLPaths.MODSDIR.get();
            Path gameDir = FMLPaths.GAMEDIR.get();

            List<ModActionItem> actionItems = new ArrayList<>();
            Set<String> processedFiles = new HashSet<>();
            Set<String> forcedAddTargets = new HashSet<>();
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
                    String normalizedTarget = rule.target.replace('\\', '/');
                    if (normalizedTarget.startsWith("mods/") && "ADD".equals(rule.action)) forcedAddTargets.add(fname);
                    if (normalizedTarget.startsWith("mods/") && "DELETE".equals(rule.action)) forcedDeleteTargets.add(fname);
                }
            }

            SyncCalculationResult res = new SyncCalculationResult();
            res.packet = packet;
            res.hasSnapshot = hasSnapshot;

            // Step 1: Snapshot vs Local
            if (!hasSnapshot) { res.status1 = "§7[本地校验] 暂未开启"; }
            else if (isPendingState) { res.status1 = "§e[本地校验] 等待进服"; }
            else {
                boolean isConsistent = true;
                for (Map.Entry<String, LocalModInfo> snapshotEntry : snapshotMap.entrySet()) {
                    String snapFile = snapshotEntry.getKey();
                    LocalModInfo expected = snapshotEntry.getValue();
                    if (ModSyncMain.MODID.equals(expected.modId)) continue;
                    LocalModInfo actual = localMap.get(snapFile);
                    boolean matches = actual != null && sameFile(expected.hash, expected.sha256, expected.size,
                            actual.hash, actual.sha256, actual.size);
                    if (!matches) {
                        isConsistent = false;
                        actionItems.add(new ModActionItem(snapFile,
                                actual == null ? null : localModsDir.resolve(snapFile), "RESTORE", true)
                                .expect(expected.hash, expected.sha256, expected.size));
                        processedFiles.add(snapFile);
                    }
                }
                res.status1 = isConsistent ? "§a[本地校验] 状态正常" : "§c[本地校验] 状态异常";
            }

            // Step 2.5: Forced Actions
            int forcedCount = 0;
            Map<String, List<ModActionItem>> folderGroups = new HashMap<>();
            if (packet.forcedActions != null) {
                for (ServerSideManager.ForcedRule rule : packet.forcedActions) {
                    if ("IGNORE".equals(rule.action)) continue;
                    if (rule.target == null) continue;
                    Path targetPath = IntegrityUtil.safeResolve(gameDir, rule.target, false);
                    String displayName = rule.target;

                    if ("DELETE".equals(rule.action)) {
                        String targetFileName = targetPath.getFileName().toString();
                        actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(targetFileName));
                        if (displayName.replace('\\', '/').startsWith("mods/")) processedFiles.add(targetFileName);
                        if (Files.exists(targetPath)) {
                            if (Files.isDirectory(targetPath) && !packet.allowFolderDelete) {
                                throw new IOException("服务端请求删除目录，但客户端安全标志未授权: " + displayName);
                            }
                            actionItems.add(new ModActionItem(displayName, targetPath, "FORCED_DELETE", true).setForcedRule(rule));
                            forcedCount++;
                        }
                    } else if ("ADD".equals(rule.action)) {
                        if (displayName.replace('\\', '/').startsWith("mods/")) {
                            String targetFileName = targetPath.getFileName().toString();
                            actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(targetFileName));
                            processedFiles.add(targetFileName);
                        }
                        boolean needUpdate = true;
                        if (Files.exists(targetPath)) {
                            if (IntegrityUtil.matches(targetPath, rule.hash, rule.sha256, rule.size)) needUpdate = false;
                        }
                        if (needUpdate) {
                            ModActionItem item = new ModActionItem(displayName, targetPath, "FORCED_OVERWRITE", true)
                                    .setExtra(rule.source).setForcedRule(rule).expect(rule.hash, rule.sha256, rule.size);
                            Path parent = Paths.get(rule.target).getParent();
                            if (parent != null) {
                                String parentKey = parent.toString().replace("\\", "/");
                                folderGroups.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(item);
                            } else {
                                actionItems.add(item);
                            }
                            forcedCount++;
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
            boolean serverHasUpdates = (packet.toDownload != null && !packet.toDownload.isEmpty()) || (packet.toRemove != null && !packet.toRemove.isEmpty());
            if (!serverHasUpdates) { res.status2 = "§a[版本同步] 版本一致"; }
            else {
                res.status2 = "§c[版本同步] 有新版本";
                if (packet.toRemove != null) {
                    for (String fileToRemove : packet.toRemove) {
                        boolean restorePending = actionItems.stream().anyMatch(
                                i -> i.type.equals("RESTORE") && i.name.equals(fileToRemove));
                        if (processedFiles.contains(fileToRemove) && !restorePending) continue;
                        actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(fileToRemove));
                        processedFiles.add(fileToRemove);
                        if (localMap.containsKey(fileToRemove)) {
                            actionItems.add(new ModActionItem(fileToRemove,
                                    IntegrityUtil.safeResolve(localModsDir, fileToRemove, false), "SERVER_REMOVED", true));
                        }
                    }
                }
                if (packet.toDownload != null) {
                    for (ServerSideManager.ModInfo info : packet.toDownload) {
                        actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(info.fileName));
                        if (forcedAddTargets.contains(info.fileName)) continue;

                        if (localMap.containsKey(info.fileName)) {
                            LocalModInfo localInfo = localMap.get(info.fileName);
                            if (!sameFile(info.hash, info.sha256, info.size,
                                    localInfo.hash, localInfo.sha256, localInfo.size)) {
                                actionItems.add(new ModActionItem(info.fileName,
                                        IntegrityUtil.safeResolve(localModsDir, info.fileName, false), "UPDATE", true)
                                        .setModInfo(info).expect(info.hash, info.sha256, info.size));
                                processedFiles.add(info.fileName);
                            } else { processedFiles.add(info.fileName); }
                        } else {
                            actionItems.add(new ModActionItem(info.fileName, null, "NEW", true)
                                    .setModInfo(info).expect(info.hash, info.sha256, info.size));
                            processedFiles.add(info.fileName);
                            for (LocalModInfo local : localScanList) {
                                if (local.modId != null && local.modId.equals(info.modId) && !local.fileName.equals(info.fileName)) {
                                    actionItems.add(new ModActionItem(local.fileName,
                                            IntegrityUtil.safeResolve(localModsDir, local.fileName, false), "SERVER_REMOVED", true));
                                    processedFiles.add(local.fileName);
                                }
                            }
                        }
                    }
                }
            }

            // Step 3: authoritative filename + size + SHA-1/SHA-256 validation.
            Set<String> authoritySet = new HashSet<>();
            for (ServerSideManager.ModInfo m : packet.authorityManifest) authoritySet.add(m.fileName);
            boolean integrityPassed = true;
            for (ServerSideManager.ModInfo info : packet.authorityManifest) {
                if (!info.isServerRequired || forcedAddTargets.contains(info.fileName)
                        || forcedDeleteTargets.contains(info.fileName)
                        || matchesIgnore(ignorePatterns, info.fileName, info.modId)) continue;
                LocalModInfo actual = localMap.get(info.fileName);
                boolean exact = actual != null && sameFile(info.hash, info.sha256, info.size,
                        actual.hash, actual.sha256, actual.size);
                if (!exact) {
                    boolean isCovered = hasPlannedAuthorityResult(actionItems, info);
                    if (!isCovered) {
                        String type = actual == null ? "NEW" : "UPDATE";
                        actionItems.add(new ModActionItem(info.fileName,
                                actual == null ? null : IntegrityUtil.safeResolve(localModsDir, info.fileName, false), type, true)
                                .setModInfo(info).expect(info.hash, info.sha256, info.size));
                    }
                    if (!hasPlannedAuthorityResult(actionItems, info)) integrityPassed = false;
                }
            }
            for (LocalModInfo localFile : localScanList) {
                String fileName = localFile.fileName;
                if (processedFiles.contains(fileName) || actionItems.stream().anyMatch(i -> i.name.equals(fileName))) continue;
                if (ModSyncMain.MODID.equals(localFile.modId)) continue;
                if (forcedAddTargets.contains(fileName) || authoritySet.contains(fileName)) continue;
                if (matchesIgnore(ignorePatterns, fileName, localFile.modId)) continue;
                LocalModInfo approved = snapshotMap.get(fileName);
                if (approved != null && sameFile(approved.hash, approved.sha256, approved.size,
                        localFile.hash, localFile.sha256, localFile.size)) continue;
                if (provenByCurrentConnection) continue;
                if (!hasSnapshot && localFile.isClientOnly) continue;
                actionItems.add(new ModActionItem(fileName,
                        IntegrityUtil.safeResolve(localModsDir, fileName, false), "LOCAL_UNVERIFIED", true));
            }
            res.status3 = integrityPassed ? "§a[整体校验] 计划校验通过" : "§c[整体校验] 计划存在遗漏";

            res.actions = actionItems;
            return res;

        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    private static boolean hasPlannedAuthorityResult(List<ModActionItem> actions, ServerSideManager.ModInfo expected) {
        for (ModActionItem item : actions) {
            List<ModActionItem> candidates = item.subItems == null ? Collections.singletonList(item) : item.subItems;
            for (ModActionItem candidate : candidates) {
                if (!expected.fileName.equals(candidate.name)) continue;
                if (!(candidate.type.equals("NEW") || candidate.type.equals("UPDATE") || candidate.type.equals("RESTORE"))) continue;
                if (sameFile(expected.hash, expected.sha256, expected.size,
                        candidate.expectedSha1, candidate.expectedSha256, candidate.expectedSize)) return true;
            }
        }
        return false;
    }

    private static List<LocalModInfo> scanLocalMods() {
        List<LocalModInfo> list = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(FMLPaths.MODSDIR.get(), "*.jar")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    IntegrityUtil.Hashes hashes = IntegrityUtil.hash(path);
                    list.add(new LocalModInfo(path.getFileName().toString(), hashes.sha1(), hashes.sha256(), hashes.size(),
                            getModId(path), checkClientOnlyComprehensive(path)));
                } catch (IOException e) {
                    LOGGER.error("AutoModSync: Could not hash local mod {}", path, e);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
        return list;
    }

    private static String getModId(Path jarPath) {
        String targetFileName = jarPath.getFileName().toString();
        for (IModInfo mod : ModList.get().getMods()) {
            try {
                if (mod.getOwningFile().getFile().getFilePath().getFileName().toString().equals(targetFileName)) {
                    return mod.getModId();
                }
            } catch (Exception ignored) {}
        }
        try (ZipFile z = new ZipFile(jarPath.toFile())) {
            ZipEntry t = z.getEntry("META-INF/mods.toml");
            if (t != null) {
                try (InputStream i = z.getInputStream(t)) {
                    String content = new Scanner(i).useDelimiter("\\A").next();
                    Pattern p = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
                    Matcher m = p.matcher(content);
                    if (m.find()) return m.group(1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean checkClientOnlyComprehensive(Path jarPath) {
        String targetFileName = jarPath.getFileName().toString();
        for (IModInfo mod : ModList.get().getMods()) {
            try {
                if (mod.getOwningFile().getFile().getFilePath().getFileName().toString().equals(targetFileName)) {
                    Optional<? extends ModContainer> container = ModList.get().getModContainerById(mod.getModId());
                    if (container.isPresent()) {
                        Optional<IExtensionPoint.DisplayTest> testOpt = container.get().getCustomExtension(IExtensionPoint.DisplayTest.class);
                        if (testOpt.isPresent() && testOpt.get().remoteVersionTest().test(NetworkConstants.IGNORESERVERONLY, false)) return true;
                    }
                    if (mod.getOwningFile().getFile().getModFileInfo().getConfig().getConfigElement("displayTest").map(o -> o.equals("IGNORE_SERVER_VERSION")).orElse(false)) return true;
                    break;
                }
            } catch (Exception ignored) {}
        }
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

    private static synchronized void saveClientSnapshot(List<LocalModInfo> files, int version) throws IOException {
        if (version <= 0 || files == null) throw new IOException("Invalid snapshot");
        ClientSnapshot snapshot = new ClientSnapshot();
        snapshot.version = version;
        String currentUrl = CACHED_SYNC_URL.get();
        if (currentUrl != null && !currentUrl.isBlank()) snapshot.syncUrl = normalizeBaseUrl(currentUrl);
        snapshot.files = files.stream()
                .filter(info -> !ModSyncMain.MODID.equals(info.modId))
                .collect(Collectors.toCollection(ArrayList::new));
        snapshot.files.sort(Comparator.comparing(info -> info.fileName));
        Path temp = CLIENT_SNAPSHOT_FILE.resolveSibling(CLIENT_SNAPSHOT_FILE.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        moveAtomically(temp, CLIENT_SNAPSHOT_FILE);
    }

    private static ClientSnapshot loadSnapshot() {
        if (!Files.isRegularFile(CLIENT_SNAPSHOT_FILE)) return null;
        try (Reader reader = Files.newBufferedReader(CLIENT_SNAPSHOT_FILE, StandardCharsets.UTF_8)) {
            ClientSnapshot snapshot = GSON.fromJson(reader, ClientSnapshot.class);
            if (snapshot == null || snapshot.version <= 0 || snapshot.files == null || snapshot.files.size() > 10000) return null;
            Set<String> names = new HashSet<>();
            for (LocalModInfo info : snapshot.files) {
                IntegrityUtil.normalizeFileName(info.fileName);
                if (!names.add(info.fileName) || !IntegrityUtil.isHash(info.hash, 40)) return null;
                if (info.sha256 != null && !info.sha256.isBlank() && !IntegrityUtil.isHash(info.sha256, 64)) return null;
                if (info.size == 0 && (info.sha256 == null || info.sha256.isBlank())) info.size = -1L;
                if (info.officialUrls == null) info.officialUrls = new ArrayList<>();
            }
            return snapshot;
        } catch (Exception e) {
            LOGGER.error("AutoModSync: Ignored malformed client snapshot", e);
            return null;
        }
    }

    private static ClientSnapshot loadSnapshotForServer(String syncUrl) {
        ClientSnapshot snapshot = loadSnapshot();
        if (snapshot == null) return null;
        // Legacy snapshots had no URL. Keep them usable until the next successful connection rewrites the file.
        if (snapshot.syncUrl == null || snapshot.syncUrl.isBlank()) return snapshot;
        return sameServerUrl(snapshot.syncUrl, syncUrl) ? snapshot : null;
    }

    private static void savePendingSync(PatchPacket packet, String syncUrl) throws IOException {
        PendingSync pending = new PendingSync();
        pending.targetVersion = packet.targetVersion;
        pending.syncUrl = normalizeBaseUrl(syncUrl);
        pending.completedAt = System.currentTimeMillis();
        pending.authorityManifest = packet.authorityManifest;
        pending.forcedActions = packet.forcedActions;
        Path temp = PENDING_SYNC_FILE.resolveSibling(PENDING_SYNC_FILE.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(pending), StandardCharsets.UTF_8);
        moveAtomically(temp, PENDING_SYNC_FILE);
    }

    private static PendingSync loadPendingSync() {
        if (!Files.isRegularFile(PENDING_SYNC_FILE)) return null;
        try (Reader reader = Files.newBufferedReader(PENDING_SYNC_FILE, StandardCharsets.UTF_8)) {
            PendingSync pending = GSON.fromJson(reader, PendingSync.class);
            PatchPacket packet = new PatchPacket();
            packet.targetVersion = pending.targetVersion;
            packet.authorityManifest = pending.authorityManifest;
            packet.forcedActions = pending.forcedActions;
            packet.toDownload = new ArrayList<>();
            packet.toRemove = new ArrayList<>();
            validatePatchPacket(packet);
            normalizeBaseUrl(pending.syncUrl);
            return pending;
        } catch (Exception e) {
            LOGGER.error("AutoModSync: Ignored malformed pending sync transaction", e);
            return null;
        }
    }

    private static String downloadString(URL url) {
        HttpURLConnection connection = null;
        try {
            if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))
                    || url.getUserInfo() != null) return null;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AutoModSync/3.2");
            if (connection.getResponseCode() != 200) return null;
            long length = connection.getContentLengthLong();
            if (length > 4 * 1024 * 1024) return null;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > 4 * 1024 * 1024) return null;
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("AutoModSync: Failed to download diff: {}", e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void validatePatchPacket(PatchPacket packet) throws IOException {
        if (packet == null || packet.targetVersion <= 0) throw new IOException("清单版本无效");
        if (packet.toDownload == null) packet.toDownload = new ArrayList<>();
        if (packet.toRemove == null) packet.toRemove = new ArrayList<>();
        if (packet.forcedActions == null) packet.forcedActions = new ArrayList<>();
        if (packet.authorityManifest == null) packet.authorityManifest = new ArrayList<>();
        if (packet.toDownload.size() > 10000 || packet.toRemove.size() > 10000
                || packet.forcedActions.size() > 20000 || packet.authorityManifest.size() > 10000) {
            throw new IOException("清单条目数量超出安全限制");
        }
        Set<String> authorityNames = new HashSet<>();
        Map<String, ServerSideManager.ModInfo> authority = new HashMap<>();
        for (ServerSideManager.ModInfo info : packet.authorityManifest) {
            validateModInfo(info);
            if (!authorityNames.add(info.fileName)) throw new IOException("权威清单存在重复文件: " + info.fileName);
            authority.put(info.fileName, info);
        }
        Set<String> removals = new HashSet<>();
        for (String name : packet.toRemove) {
            IntegrityUtil.normalizeFileName(name);
            if (!removals.add(name)) throw new IOException("删除清单存在重复文件: " + name);
        }
        Set<String> downloads = new HashSet<>();
        for (ServerSideManager.ModInfo info : packet.toDownload) {
            validateModInfo(info);
            ServerSideManager.ModInfo expected = authority.get(info.fileName);
            if (expected == null || !sameFile(info.hash, info.sha256, info.size,
                    expected.hash, expected.sha256, expected.size)) {
                throw new IOException("下载条目不属于权威清单: " + info.fileName);
            }
            if (!downloads.add(info.fileName)) throw new IOException("下载清单存在重复文件: " + info.fileName);
        }
        Set<String> addTargets = new HashSet<>();
        Set<String> deleteTargets = new HashSet<>();
        for (ServerSideManager.ForcedRule rule : packet.forcedActions) {
            if (rule == null || rule.action == null) throw new IOException("空的强制规则");
            String action = rule.action.toUpperCase(Locale.ROOT);
            if (action.equals("IGNORE")) {
                if (rule.target == null || rule.target.isBlank() || rule.target.length() > 200
                        || rule.target.contains("..") || rule.target.indexOf('\0') >= 0) {
                    throw new IOException("非法 IGNORE 规则");
                }
                continue;
            }
            IntegrityUtil.safeResolve(FMLPaths.GAMEDIR.get(), rule.target, false);
            if (action.equals("ADD")) {
                IntegrityUtil.safeResolve(Path.of("forced-root"), rule.source, false);
                if (rule.size == 0 && (rule.sha256 == null || rule.sha256.isBlank())) rule.size = -1L;
                validateHashes(rule.hash, rule.sha256, rule.size, rule.target);
                if (!addTargets.add(rule.target) || deleteTargets.contains(rule.target)) {
                    throw new IOException("冲突的强制目标: " + rule.target);
                }
            } else if (action.equals("DELETE")) {
                if (!deleteTargets.add(rule.target) || addTargets.contains(rule.target)) {
                    throw new IOException("冲突的强制目标: " + rule.target);
                }
            } else {
                throw new IOException("未知强制动作: " + rule.action);
            }
        }
        Path gameRoot = FMLPaths.GAMEDIR.get();
        for (String add : addTargets) {
            Path addPath = IntegrityUtil.safeResolve(gameRoot, add, false);
            for (String delete : deleteTargets) {
                Path deletePath = IntegrityUtil.safeResolve(gameRoot, delete, false);
                if (addPath.startsWith(deletePath) || deletePath.startsWith(addPath)) {
                    throw new IOException("强制新增与删除路径重叠: " + add + " / " + delete);
                }
            }
        }
    }

    private static void validateModInfo(ServerSideManager.ModInfo info) throws IOException {
        if (info == null) throw new IOException("空模组记录");
        IntegrityUtil.normalizeFileName(info.fileName);
        if (info.modId == null || info.modId.isBlank() || info.modId.length() > 256) throw new IOException("模组 ID 无效");
        if (info.size == 0 && (info.sha256 == null || info.sha256.isBlank())) info.size = -1L;
        validateHashes(info.hash, info.sha256, info.size, info.fileName);
        if (info.officialUrls == null) info.officialUrls = new ArrayList<>();
        if (info.officialUrls.size() > 8) throw new IOException("官方源数量过多: " + info.fileName);
        for (String url : info.officialUrls) {
            if (!OfficialSourceResolver.isAllowedOfficialUrl(url)) throw new IOException("非法官方源: " + info.fileName);
        }
    }

    private static void validateHashes(String sha1, String sha256, long size, String name) throws IOException {
        if (!IntegrityUtil.isHash(sha1, 40)) throw new IOException("SHA-1 无效: " + name);
        if (sha256 != null && !sha256.isBlank() && !IntegrityUtil.isHash(sha256, 64)) {
            throw new IOException("SHA-256 无效: " + name);
        }
        if (size == 0 || size > IntegrityUtil.MAX_DOWNLOAD_BYTES || size < -1) throw new IOException("文件大小无效: " + name);
    }

    private static boolean sameFile(String expectedSha1, String expectedSha256, long expectedSize,
                                    String actualSha1, String actualSha256, long actualSize) {
        if (!IntegrityUtil.isHash(expectedSha1, 40) || !expectedSha1.equalsIgnoreCase(actualSha1)) return false;
        if (IntegrityUtil.isHash(expectedSha256, 64)
                && !expectedSha256.equalsIgnoreCase(Objects.toString(actualSha256, ""))) return false;
        return expectedSize < 0 || expectedSize == actualSize;
    }

    private static boolean matchesIgnore(Set<String> patterns, String fileName, String modId) {
        if (patterns == null) return false;
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty()
                    && ((fileName != null && fileName.contains(pattern)) || (modId != null && modId.contains(pattern)))) return true;
        }
        return false;
    }

    private static String validateExpectedEnvironment(List<LocalModInfo> localFiles,
                                                      List<ServerSideManager.ModInfo> authority,
                                                      List<ServerSideManager.ForcedRule> forced) {
        return validateExpectedEnvironment(localFiles, authority, forced, false);
    }

    private static String validateExpectedEnvironment(List<LocalModInfo> localFiles,
                                                      List<ServerSideManager.ModInfo> authority,
                                                      List<ServerSideManager.ForcedRule> forced,
                                                      boolean allowConnectionProvenLocalMods) {
        try {
            Map<String, LocalModInfo> local = localFiles.stream().collect(Collectors.toMap(
                    info -> info.fileName, info -> info, (left, right) -> left));
            Set<String> ignores = new HashSet<>();
            Set<String> forcedModFiles = new HashSet<>();
            if (forced != null) {
                for (ServerSideManager.ForcedRule rule : forced) {
                    if ("IGNORE".equalsIgnoreCase(rule.action)) ignores.add(rule.target);
                    if ("ADD".equalsIgnoreCase(rule.action)) {
                        Path target = IntegrityUtil.safeResolve(FMLPaths.GAMEDIR.get(), rule.target, false);
                        if (!IntegrityUtil.matches(target, rule.hash, rule.sha256, rule.size)) {
                            return "强制文件校验失败: " + rule.target;
                        }
                        String normalized = rule.target.replace('\\', '/');
                        if (normalized.startsWith("mods/")) forcedModFiles.add(target.getFileName().toString());
                    } else if ("DELETE".equalsIgnoreCase(rule.action)) {
                        Path target = IntegrityUtil.safeResolve(FMLPaths.GAMEDIR.get(), rule.target, false);
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return "强制删除未生效: " + rule.target;
                    }
                }
            }
            Set<String> authorityNames = new HashSet<>();
            if (authority != null) {
                for (ServerSideManager.ModInfo expected : authority) {
                    authorityNames.add(expected.fileName);
                    if (!expected.isServerRequired || matchesIgnore(ignores, expected.fileName, expected.modId)) continue;
                    LocalModInfo actual = local.get(expected.fileName);
                    if (actual == null || !sameFile(expected.hash, expected.sha256, expected.size,
                            actual.hash, actual.sha256, actual.size)) return "模组哈希不一致: " + expected.fileName;
                }
            }
            if (allowConnectionProvenLocalMods) return null;

            ClientSnapshot baseline = loadSnapshotForServer(CACHED_SYNC_URL.get());
            Map<String, LocalModInfo> baselineFiles = new HashMap<>();
            if (baseline != null) for (LocalModInfo info : baseline.files) baselineFiles.put(info.fileName, info);
            for (LocalModInfo actual : localFiles) {
                if (authorityNames.contains(actual.fileName) || forcedModFiles.contains(actual.fileName)
                        || ModSyncMain.MODID.equals(actual.modId)
                        || matchesIgnore(ignores, actual.fileName, actual.modId)) continue;
                LocalModInfo old = baselineFiles.get(actual.fileName);
                if (old == null && baselineFiles.isEmpty() && actual.isClientOnly) continue;
                if (old == null) return "存在未授权模组: " + actual.fileName;
                if (!sameFile(old.hash, old.sha256, old.size, actual.hash, actual.sha256, actual.size)) {
                    return "基准模组被修改: " + actual.fileName;
                }
            }
            for (LocalModInfo old : baselineFiles.values()) {
                if (ModSyncMain.MODID.equals(old.modId) || authorityNames.contains(old.fileName) || !old.isClientOnly) continue;
                LocalModInfo actual = local.get(old.fileName);
                if (actual == null || !sameFile(old.hash, old.sha256, old.size, actual.hash, actual.sha256, actual.size)) {
                    return "客户端模组偏离基准: " + old.fileName;
                }
            }
            return null;
        } catch (Exception e) {
            return "环境验证异常: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName());
        }
    }

    private static void mergeOfficialSources(List<LocalModInfo> localFiles, List<ServerSideManager.ModInfo> authority) {
        if (authority == null) return;
        Map<String, ServerSideManager.ModInfo> byName = authority.stream().collect(Collectors.toMap(
                info -> info.fileName, info -> info, (left, right) -> left));
        for (LocalModInfo local : localFiles) {
            ServerSideManager.ModInfo remote = byName.get(local.fileName);
            if (remote != null && sameFile(remote.hash, remote.sha256, remote.size, local.hash, local.sha256, local.size)) {
                local.officialUrls = remote.officialUrls == null ? new ArrayList<>() : new ArrayList<>(remote.officialUrls);
            }
        }
    }

    private static void backupVerifiedMods(List<LocalModInfo> files) {
        for (LocalModInfo mod : files) {
            if (ModSyncMain.MODID.equals(mod.modId)) continue;
            Path path = FMLPaths.MODSDIR.get().resolve(mod.fileName);
            if (IntegrityUtil.matches(path, mod.hash, mod.sha256, mod.size)) {
                WarehouseManager.backupToWarehouse(path, mod.hash, mod.sha256, mod.size);
            }
        }
    }

    private static String normalizeBaseUrl(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.length() > 2048) throw new IOException("同步地址为空或过长");
        String value = raw.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("同步地址格式无效");
            }
            String normalized = uri.normalize().toString();
            return normalized.endsWith("/") ? normalized : normalized + "/";
        } catch (IllegalArgumentException e) {
            throw new IOException("同步地址格式无效", e);
        }
    }

    private static boolean sameServerUrl(String left, String right) {
        try { return normalizeBaseUrl(left).equalsIgnoreCase(normalizeBaseUrl(right)); }
        catch (IOException e) { return false; }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

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
        private final Screen parent; private final List<ModActionItem> actionItems;
        private final String baseUrl; private final String s1, sF, s2, s3;
        private ModListWidget modList; private SyncProgressButton execBtn; private Button cancelBtn;
        private boolean isExecuting = false; private boolean isFinished = false;
        private final PatchPacket packet;
        private final boolean showWarning;

        public SyncResultScreen(Screen p, PatchPacket pkt, List<ModActionItem> items, String url, String s1, String sF, String s2, String s3, boolean showWarning) {
            super(Component.literal("Results")); this.parent = p; this.packet = pkt; this.actionItems = items; this.baseUrl = url;
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
            if (!isFinished) execBtn = new SyncProgressButton(cx - 150, this.height - 30, 300, 20,
                    Component.literal("执行同步"), this::startExecution);
            else execBtn = new SyncProgressButton(cx - 150, this.height - 30, 300, 20,
                    Component.literal("同步完成 - 点击安全退出游戏"), () -> Minecraft.getInstance().stop());
            if (isFinished) execBtn.setProgress(1.0, "同步完成 - 点击安全退出游戏");
            if (isExecuting) execBtn.active = false;
            this.addRenderableWidget(execBtn);
            cancelBtn = Button.builder(Component.literal("取消"), b -> this.minecraft.setScreen(parent)).bounds(cx - 50, this.height - 55, 100, 20).build();
            cancelBtn.active = !isExecuting && !isFinished;
            this.addRenderableWidget(cancelBtn);
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
                g.drawString(font, "§e⚠ 检测到改动过多 (>5)", 5, this.height - 25, 0xFFFFFF);
                g.drawString(font, "§e建议查看有无更新整合包", 5, this.height - 15, 0xFFFFFF);
            }
        }
        @Override public boolean shouldCloseOnEsc() { return !isExecuting; }

        private void startExecution() {
            if (isExecuting) return;
            if (actionItems.stream().anyMatch(item -> !item.selected)) {
                execBtn.setMessage(Component.literal("严格同步要求勾选全部项目"));
                return;
            }
            isExecuting = true;
            execBtn.active = false;
            cancelBtn.active = false;
            execBtn.setProgress(0.0, "准备同步事务...");
            CompletableFuture.runAsync(() -> {
                Path stagingRoot = null;
                Path transactionBackup = null;
                Map<Path, Path> backups = new LinkedHashMap<>();
                List<Path> createdTargets = new ArrayList<>();
                try {
                    WarehouseManager.init(WAREHOUSE_ROOT_DIR);
                    validatePatchPacket(packet);
                    List<ModActionItem> tasks = flattenTasks();
                    if (tasks.isEmpty()) throw new IOException("同步计划为空");
                    Set<Path> plannedTargets = new HashSet<>();
                    for (ModActionItem task : tasks) {
                        Path target = resolveTaskTarget(task);
                        if (!plannedTargets.add(target)) throw new IOException("同步计划重复操作同一路径: " + task.name);
                    }
                    Files.createDirectories(BACKUP_DIR);
                    String transactionId = Long.toUnsignedString(System.currentTimeMillis()) + "-" + UUID.randomUUID();
                    stagingRoot = IntegrityUtil.safeResolve(WAREHOUSE_ROOT_DIR, "staging/" + transactionId, false);
                    transactionBackup = IntegrityUtil.safeResolve(BACKUP_DIR, "transactions/" + transactionId, false);
                    Files.createDirectories(stagingRoot);
                    Files.createDirectories(transactionBackup);
                    String effectiveBaseUrl = normalizeBaseUrl(baseUrl);

                    Map<ModActionItem, Path> staged = new IdentityHashMap<>();
                    int acquisitionCount = (int) tasks.stream().filter(this::needsAcquisition).count();
                    int totalSteps = Math.max(1, acquisitionCount + tasks.size() + 1);
                    int completedSteps = 0;
                    int stageIndex = 0;
                    for (ModActionItem task : tasks) {
                        if (!needsAcquisition(task)) continue;
                        Path stagedFile = IntegrityUtil.safeResolve(stagingRoot, String.format(Locale.ROOT, "%05d.bin", stageIndex++), false);
                        final int baseStep = completedSteps;
                        stageTask(task, stagedFile, effectiveBaseUrl, (source, bytes, total) -> {
                            double filePart = total > 0 ? Math.min(1.0, (double) bytes / total) : 0.25;
                            execBtn.setProgress((baseStep + filePart) / totalSteps,
                                    source + " · " + task.name + (total > 0 ? " · " + (bytes * 100 / total) + "%" : ""));
                        });
                        staged.put(task, stagedFile);
                        completedSteps++;
                        execBtn.setProgress((double) completedSteps / totalSteps, "已校验 · " + task.name);
                    }

                    int applyIndex = 0;
                    for (ModActionItem task : tasks) {
                        execBtn.setProgress((double) completedSteps / totalSteps,
                                "正在应用 " + (++applyIndex) + "/" + tasks.size() + " · " + task.name);
                        applyTask(task, staged.get(task), transactionBackup, backups, createdTargets);
                        completedSteps++;
                    }

                    List<LocalModInfo> currentFiles = scanLocalMods();
                    String validationError = validateExpectedEnvironment(currentFiles,
                            packet.authorityManifest, packet.forcedActions,
                            isConnectionProofFor(effectiveBaseUrl, packet.targetVersion));
                    if (validationError != null) throw new IOException(validationError);
                    mergeOfficialSources(currentFiles, packet.authorityManifest);
                    backupVerifiedMods(currentFiles);
                    if (stagingRoot != null) deleteTree(stagingRoot);
                    stagingRoot = null;
                    savePendingSync(packet, effectiveBaseUrl);
                    execBtn.setProgress(1.0, "同步与哈希复核完成");
                    Minecraft.getInstance().execute(() -> {
                        isExecuting = false;
                        isFinished = true;
                        this.clearWidgets();
                        this.init();
                    });
                } catch (Exception e) {
                    LOGGER.error("AutoModSync: Sync transaction failed; rolling back", e);
                    rollback(createdTargets, backups);
                    if (stagingRoot != null) {
                        try { deleteTree(stagingRoot); } catch (IOException ignored) {}
                    }
                    String reason = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
                    if (reason.length() > 80) reason = reason.substring(0, 80);
                    String finalReason = reason;
                    Minecraft.getInstance().execute(() -> {
                        isExecuting = false;
                        execBtn.active = true;
                        cancelBtn.active = true;
                        execBtn.setProgress(0.0, "失败并已回滚 · " + finalReason);
                    });
                }
            });
        }

        private List<ModActionItem> flattenTasks() {
            List<ModActionItem> tasks = new ArrayList<>();
            for (ModActionItem item : actionItems) {
                if (!item.selected) continue;
                if (item.subItems == null) tasks.add(item);
                else tasks.addAll(item.subItems);
            }
            return tasks;
        }

        private boolean needsAcquisition(ModActionItem task) {
            return task.type.equals("FORCED_OVERWRITE") || task.type.equals("RESTORE")
                    || task.type.equals("NEW") || task.type.equals("UPDATE");
        }

        private void stageTask(ModActionItem task, Path stagedFile, String effectiveBaseUrl,
                               SecureDownloader.ProgressListener progress) throws IOException {
            ServerSideManager.ModInfo info = task.modInfo;
            if (info == null && packet.authorityManifest != null) {
                info = packet.authorityManifest.stream().filter(candidate -> task.name.equals(candidate.fileName)).findFirst().orElse(null);
            }
            if (info != null) task.expect(info.hash, info.sha256, info.size);

            if (task.type.equals("RESTORE")) {
                Path stored = WarehouseManager.getFileInWarehouse(task.name,
                        task.expectedSha1, task.expectedSha256, task.expectedSize);
                if (stored != null) {
                    Files.copy(stored, stagedFile, StandardCopyOption.REPLACE_EXISTING);
                    if (!IntegrityUtil.matches(stagedFile, task.expectedSha1, task.expectedSha256, task.expectedSize)) {
                        Files.deleteIfExists(stagedFile);
                        throw new IOException("本地仓库文件校验失败: " + task.name);
                    }
                    progress.update("本地仓库", task.expectedSize, task.expectedSize);
                    return;
                }
            }

            if (task.type.equals("FORCED_OVERWRITE")) {
                if (task.forcedRule == null || task.forcedRule.source == null) throw new IOException("强制规则缺少来源: " + task.name);
                String encoded = URLEncoder.encode(task.forcedRule.source, StandardCharsets.UTF_8).replace("+", "%20");
                SecureDownloader.download(Collections.emptyList(), new URL(effectiveBaseUrl + "forced/" + encoded), stagedFile,
                        task.forcedRule.hash, task.forcedRule.sha256, task.forcedRule.size, progress);
                return;
            }

            if (info == null) throw new IOException("没有可恢复的仓库文件或权威下载记录: " + task.name);
            String encoded = URLEncoder.encode(info.fileName, StandardCharsets.UTF_8).replace("+", "%20");
            SecureDownloader.download(info.officialUrls, new URL(effectiveBaseUrl + "download/" + encoded), stagedFile,
                    info.hash, info.sha256, info.size, progress);
        }

        private void applyTask(ModActionItem task, Path staged, Path transactionBackup,
                               Map<Path, Path> backups, List<Path> createdTargets) throws IOException {
            Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Path target = resolveTaskTarget(task);
            if (!target.startsWith(gameDir) || target.equals(gameDir)) throw new IOException("目标越界: " + task.name);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        && task.type.equals("FORCED_DELETE") && !packet.allowFolderDelete) {
                    throw new IOException("目录删除未获授权: " + task.name);
                }
                Path relative = gameDir.relativize(target);
                Path backup = IntegrityUtil.safeResolve(transactionBackup, relative.toString(), false);
                Files.createDirectories(backup.getParent());
                moveAtomically(target, backup);
                backups.put(target, backup);
            }

            if (needsAcquisition(task)) {
                if (staged == null || !IntegrityUtil.matches(staged,
                        task.expectedSha1, task.expectedSha256, task.expectedSize)) {
                    throw new IOException("暂存文件复核失败: " + task.name);
                }
                Files.createDirectories(target.getParent());
                moveAtomically(staged, target);
                createdTargets.add(target);
            }
        }

        private Path resolveTaskTarget(ModActionItem task) throws IOException {
            if (task.type.equals("NEW") || task.type.equals("UPDATE") || task.type.equals("RESTORE")
                    || task.type.equals("SERVER_REMOVED") || task.type.equals("LOCAL_UNVERIFIED")) {
                return IntegrityUtil.safeResolve(FMLPaths.MODSDIR.get(), task.name, false).toAbsolutePath().normalize();
            }
            if (task.forcedRule == null || task.forcedRule.target == null) throw new IOException("强制操作缺少目标: " + task.name);
            return IntegrityUtil.safeResolve(FMLPaths.GAMEDIR.get(), task.forcedRule.target, false).toAbsolutePath().normalize();
        }

        private void rollback(List<Path> createdTargets, Map<Path, Path> backups) {
            ListIterator<Path> created = createdTargets.listIterator(createdTargets.size());
            while (created.hasPrevious()) {
                try { deleteTree(created.previous()); } catch (IOException e) { LOGGER.error("Rollback delete failed", e); }
            }
            List<Map.Entry<Path, Path>> entries = new ArrayList<>(backups.entrySet());
            Collections.reverse(entries);
            for (Map.Entry<Path, Path> entry : entries) {
                try {
                    Files.createDirectories(entry.getKey().getParent());
                    moveAtomically(entry.getValue(), entry.getKey());
                } catch (IOException e) {
                    LOGGER.error("Rollback restore failed: {}", entry.getKey(), e);
                }
            }
        }

        private void deleteTree(Path path) throws IOException {
            if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
                return;
            }
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir); return FileVisitResult.CONTINUE;
                }
            });
        }

        private int getTypePriority(ModActionItem item) {
            switch (item.type) {
                case "FORCED_DELETE": return 0; case "FORCED_OVERWRITE": return 0;
                case "RESTORE": return 1; case "NEW": return 2; case "UPDATE": return 2;
                case "SERVER_REMOVED": return 3; case "LOCAL_UNVERIFIED": return 4; default: return 5;
            }
        }
        private String getCategoryTitle(String type) {
            switch (type) {
                case "FORCED_DELETE": return "§c[强制删除] (策略要求)";
                case "FORCED_OVERWRITE": return "§e[强制覆盖] (策略要求)";
                case "RESTORE": return "§b[本地自愈] (误删恢复)"; case "NEW": return "§a[新增模组] (下载)";
                case "UPDATE": return "§6[版本更新] (替换)"; case "SERVER_REMOVED": return "§c[服务移除] (删除)";
                case "LOCAL_UNVERIFIED": return "§4[本地待验] (未通过进服验证)"; default: return "§7[其他]";
            }
        }
    }

    /** Button that turns into an in-place progress bar and embeds the current filename. */
    public static class SyncProgressButton extends AbstractButton {
        private final Runnable onPress;
        private volatile double progress;
        private volatile String progressText;

        public SyncProgressButton(int x, int y, int width, int height, Component message, Runnable onPress) {
            super(x, y, width, height, message);
            this.onPress = onPress;
            this.progressText = message.getString();
        }

        public void setProgress(double progress, String text) {
            this.progress = Math.max(0.0, Math.min(1.0, progress));
            this.progressText = text == null ? "" : text;
        }

        @Override public void onPress() { if (active) onPress.run(); }

        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int border = active && isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF9A9A9A;
            int background = active ? 0xFF202020 : 0xFF303030;
            graphics.fill(x, y, x + width, y + height, border);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
            int fillWidth = (int) Math.round((width - 2) * progress);
            if (fillWidth > 0) graphics.fill(x + 1, y + 1, x + 1 + fillWidth, y + height - 1, 0xFF238636);
            String label = progressText == null || progressText.isBlank() ? getMessage().getString() : progressText;
            label = Minecraft.getInstance().font.plainSubstrByWidth(label, width - 12);
            graphics.drawCenteredString(Minecraft.getInstance().font, label, x + width / 2, y + (height - 8) / 2, 0xFFFFFF);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
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
        private int rowLeft, rowTop, rowWidth, rowHeight;
        @Override public void render(GuiGraphics g, int idx, int t, int l, int w, int h, int mx, int my, boolean hov, float pt) {
            rowLeft = l; rowTop = t; rowWidth = w; rowHeight = h;
            g.fill(l + 5, t + 2, l + 25, t + 22, 0xFF888888); g.fill(l + 6, t + 3, l + 24, t + 21, 0xFF000000);
            if (item.selected) { g.pose().pushPose(); g.pose().translate(l + 15, t + 7, 0); g.drawCenteredString(Minecraft.getInstance().font, "§aV", 0, 0, 0xFFFFFF); g.pose().popPose(); }
            g.drawString(Minecraft.getInstance().font, item.name, l + 30, t + 8, 0xFFFFFF);
        }
        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 0 && mx >= rowLeft && mx <= rowLeft + rowWidth && my >= rowTop && my <= rowTop + rowHeight) {
                item.selected = !item.selected;
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
            return false;
        }
    }
}
