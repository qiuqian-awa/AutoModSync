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

    private static final Path CLIENT_STORAGE_DIR = FMLPaths.GAMEDIR.get().resolve("automodsync_client_storage");
    private static final Path CLIENT_SNAPSHOT_FILE = CLIENT_STORAGE_DIR.resolve("client_snapshot.json");
    private static final Path PENDING_VERSION_FILE = CLIENT_STORAGE_DIR.resolve("pending_version.dat");
    private static final Path SYNC_URL_FILE = CLIENT_STORAGE_DIR.resolve("last_sync_url.txt");
    private static final Path BACKUP_DIR = CLIENT_STORAGE_DIR.resolve("backup");

    public static class LocalModInfo { public String fileName; public String hash; public String modId; public boolean isClientOnly; public LocalModInfo(String fileName, String hash, String modId, boolean isClientOnly) { this.fileName = fileName; this.hash = hash; this.modId = modId; this.isClientOnly = isClientOnly; } }
    public static class ClientSnapshot { public int version; public List<LocalModInfo> files = new ArrayList<>(); }
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
        public ModActionItem(String name, Path path, String type, boolean defaultSelected) {
            this.name = name; this.path = path; this.type = type; this.selected = defaultSelected;
        }
        public ModActionItem setExtra(String info) { this.extraInfo = info; return this; }
        public ModActionItem setSubItems(List<ModActionItem> subs) { this.subItems = subs; return this; }
    }

    private static class SyncCalculationResult {
        List<ModActionItem> actions;
        PatchPacket packet;
        String status1, statusF, status2, status3;
        boolean hasSnapshot;
    }

    static {
        try { if (!Files.exists(CLIENT_STORAGE_DIR)) Files.createDirectories(CLIENT_STORAGE_DIR); } catch (IOException e) {}
        WarehouseManager.init(CLIENT_STORAGE_DIR);
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
        LOGGER.info("AutoModSync: Received live server version: v" + ver);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("automodsync_open_gui")
                        .executes(context -> {
                            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new SyncInputScreen(null)));
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
        );
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("automodsync_disconnect_update")
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
                Thread.sleep(20000);
                WarehouseManager.init(CLIENT_STORAGE_DIR);

                if (Files.exists(PENDING_VERSION_FILE)) {
                    int verToSave = 0;
                    try (BufferedReader br = Files.newBufferedReader(PENDING_VERSION_FILE)) {
                        String line = br.readLine();
                        if (line != null) verToSave = Integer.parseInt(line.trim());
                    } catch (Exception ignored) {}
                    Files.deleteIfExists(PENDING_VERSION_FILE);

                    if (verToSave > 0) {
                        List<LocalModInfo> currentFiles = scanLocalMods();
                        for (LocalModInfo mod : currentFiles) {
                            WarehouseManager.backupToWarehouse(FMLPaths.MODSDIR.get().resolve(mod.fileName));
                        }
                        saveClientSnapshot(currentFiles, verToSave);
                        LOGGER.info("AutoModSync: Snapshot updated to Pending V" + verToSave);
                        return;
                    }
                }

                ClientSnapshot old = loadSnapshot();
                int localVer = (old != null) ? old.version : 1;

                if (liveServerVersion > localVer) {
                    String url = CACHED_SYNC_URL.get();
                    if (!url.isEmpty()) {
                        SyncCalculationResult result = calculateSyncPlan(url, localVer);
                        if (result != null) {
                            if (result.actions.isEmpty()) {
                                List<LocalModInfo> currentFiles = scanLocalMods();
                                for (LocalModInfo mod : currentFiles) {
                                    WarehouseManager.backupToWarehouse(FMLPaths.MODSDIR.get().resolve(mod.fileName));
                                }
                                saveClientSnapshot(currentFiles, liveServerVersion);
                                LOGGER.info("AutoModSync: Silent update to V" + liveServerVersion + " (Environment consistent)");
                            } else {
                                Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("§b[AutoModSync] §f发现非必要版本更新 (v" + localVer + " -> v" + liveServerVersion + ")，但不影响进入服务器。"),
                                        false
                                );
                            }
                        }
                    }
                } else if (old == null) {
                    List<LocalModInfo> currentFiles = scanLocalMods();
                    for (LocalModInfo mod : currentFiles) {
                        WarehouseManager.backupToWarehouse(FMLPaths.MODSDIR.get().resolve(mod.fileName));
                    }
                    saveClientSnapshot(currentFiles, (liveServerVersion > 0) ? liveServerVersion : 1);
                }

            } catch (Exception e) { e.printStackTrace(); }
        }, "ModSync-Snapshot-Thread").start();
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
            int localVersion = (snapshot != null) ? snapshot.version : 1;
            SyncCalculationResult result = calculateSyncPlan(rawUrl, localVersion);

            if (result == null) {
                inputScreen.resetState("连接失败: 无法获取清单");
                return;
            }

            try (BufferedWriter bw = Files.newBufferedWriter(PENDING_VERSION_FILE)) { bw.write(String.valueOf(result.packet.targetVersion)); } catch (IOException ignored) {}

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
                    if ("ADD".equals(rule.action)) forcedAddTargets.add(fname);
                    if ("DELETE".equals(rule.action)) forcedDeleteTargets.add(fname);
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
                for (String snapFile : snapshotMap.keySet()) {
                    if (!localMap.containsKey(snapFile)) {
                        isConsistent = false;
                        if (WarehouseManager.isInWarehouse(snapFile)) {
                            actionItems.add(new ModActionItem(snapFile, null, "RESTORE", true));
                            processedFiles.add(snapFile);
                        }
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
                    if (rule.target == null || rule.target.contains("..")) continue;
                    Path targetPath = gameDir.resolve(rule.target);
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
            boolean serverHasUpdates = (packet.toDownload != null && !packet.toDownload.isEmpty()) || (packet.toRemove != null && !packet.toRemove.isEmpty());
            if (!serverHasUpdates) { res.status2 = "§a[版本同步] 版本一致"; }
            else {
                res.status2 = "§c[版本同步] 有新版本";
                if (packet.toRemove != null) {
                    for (String fileToRemove : packet.toRemove) {
                        if (localMap.containsKey(fileToRemove)) {
                            actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(fileToRemove));
                            actionItems.add(new ModActionItem(fileToRemove, localModsDir.resolve(fileToRemove), "SERVER_REMOVED", true));
                            processedFiles.add(fileToRemove);
                        }
                    }
                }
                if (packet.toDownload != null) {
                    for (ServerSideManager.ModInfo info : packet.toDownload) {
                        actionItems.removeIf(i -> i.type.equals("RESTORE") && i.name.equals(info.fileName));
                        if (forcedAddTargets.contains(info.fileName)) continue;

                        if (localMap.containsKey(info.fileName)) {
                            if (!localMap.get(info.fileName).hash.equals(info.hash)) {
                                actionItems.add(new ModActionItem(info.fileName, localModsDir.resolve(info.fileName), "UPDATE", true));
                                processedFiles.add(info.fileName);
                            } else { processedFiles.add(info.fileName); }
                        } else {
                            actionItems.add(new ModActionItem(info.fileName, null, "NEW", true));
                            processedFiles.add(info.fileName);
                            for (LocalModInfo local : localScanList) {
                                if (local.modId != null && local.modId.equals(info.modId) && !local.fileName.equals(info.fileName)) {
                                    actionItems.add(new ModActionItem(local.fileName, localModsDir.resolve(local.fileName), "UPDATE", true));
                                    processedFiles.add(local.fileName);
                                }
                            }
                        }
                    }
                }
            }

            // Step 3: Authority Check
            if (!hasSnapshot) {
                res.status3 = "§7[整体校验] 暂未开启";
            } else {
                Set<String> authoritySet = new HashSet<>();
                Set<String> serverModIds = new HashSet<>();
                if (packet.authorityManifest != null) {
                    for (ServerSideManager.ModInfo m : packet.authorityManifest) {
                        authoritySet.add(m.fileName);
                        if (m.modId != null) serverModIds.add(m.modId);
                    }
                }
                boolean integrityPassed = true;

                if (!authoritySet.isEmpty()) {
                    for (ServerSideManager.ModInfo info : packet.authorityManifest) {
                        if (forcedAddTargets.contains(info.fileName)) continue;
                        if (forcedDeleteTargets.contains(info.fileName)) continue;
                        if (ignorePatterns.stream().anyMatch(p -> info.fileName.contains(p) || info.modId.contains(p))) continue;

                        if (info.isServerRequired && !localMap.containsKey(info.fileName)) {
                            boolean isPending = actionItems.stream().anyMatch(i -> i.name.equals(info.fileName));
                            if (!isPending) {
                                actionItems.add(new ModActionItem(info.fileName, null, "NEW", true));
                                integrityPassed = false;
                            }
                        }
                    }
                    for (LocalModInfo localFile : localScanList) {
                        String fileName = localFile.fileName;

                        if (processedFiles.contains(fileName)) continue;
                        if (actionItems.stream().anyMatch(i -> i.name.equals(fileName))) continue;
                        if (fileName.contains("automodsync")) continue;
                        if (forcedAddTargets.contains(fileName)) continue;
                        if (authoritySet.contains(fileName)) continue;
                        if (ignorePatterns.stream().anyMatch(p -> fileName.contains(p) || (localFile.modId != null && localFile.modId.contains(p)))) continue;
                        if (localFile.isClientOnly) continue;

                        // 🟢 [新增] 快照回溯：如果上一次成功进服时有这个文件，则视为合法
                        if (snapshotMap.containsKey(fileName)) {
                            continue;
                        }

                        actionItems.add(new ModActionItem(fileName, localModsDir.resolve(fileName), "ILLEGAL", true));
                        integrityPassed = false;
                    }
                }
                res.status3 = integrityPassed ? "§a[整体校验] 校验通过" : "§c[整体校验] 校验异常";
            }

            res.actions = actionItems;
            return res;

        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    private static List<LocalModInfo> scanLocalMods() {
        List<LocalModInfo> list = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(FMLPaths.MODSDIR.get(), "*.jar")) {
            for (Path path : stream) {
                list.add(new LocalModInfo(path.getFileName().toString(), calculateHash(path.toFile()), getModId(path), checkClientOnlyComprehensive(path)));
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

    private static void saveClientSnapshot(List<LocalModInfo> files, int version) { try (Writer w = Files.newBufferedWriter(CLIENT_SNAPSHOT_FILE)) { ClientSnapshot s = new ClientSnapshot(); s.version = version; s.files = files; GSON.toJson(s, w); } catch (IOException e) {} }
    private static ClientSnapshot loadSnapshot() { if(!Files.exists(CLIENT_SNAPSHOT_FILE)) return null; try(Reader r = Files.newBufferedReader(CLIENT_SNAPSHOT_FILE)) { return GSON.fromJson(r, ClientSnapshot.class); } catch(Exception e) { return null; } }
    private static String downloadString(URL url) { try(Scanner s = new Scanner(url.openStream(), StandardCharsets.UTF_8)) { s.useDelimiter("\\A"); return s.hasNext() ? s.next() : null; } catch(IOException e) { return null; } }

    private static void downloadFile(URL url, Path target) throws IOException {
        try(InputStream in = url.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[AutoModSync] Critical Error: Failed to access URL -> " + url.toString());
            LOGGER.error("AutoModSync: Failed to download from URL: " + url.toString());
            throw e;
        }
    }

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
        private final PatchPacket packet;
        private final boolean showWarning;

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
            if (!isFinished) { execBtn = Button.builder(Component.literal("执行同步"), b -> startExecution()).bounds(cx - 105, this.height - 30, 210, 20).build(); }
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
                g.drawString(font, "§e⚠ 检测到改动过多 (>5)", 5, this.height - 25, 0xFFFFFF);
                g.drawString(font, "§e建议查看有无更新整合包", 5, this.height - 15, 0xFFFFFF);
            }
        }
        private void startExecution() {
            isExecuting = true; execBtn.active = false; execBtn.setMessage(Component.literal("正在处理..."));
            CompletableFuture.runAsync(() -> {
                StringBuilder errorLog = new StringBuilder();
                try {
                    WarehouseManager.init(CLIENT_STORAGE_DIR);
                    if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
                    for (ModActionItem item : actionItems) {
                        if (item.selected && (item.type.equals("SERVER_REMOVED") || item.type.equals("ILLEGAL") || item.type.equals("UPDATE") || item.type.equals("FORCED_DELETE"))) {
                            if (item.path != null && Files.exists(item.path)) {
                                if (Files.isDirectory(item.path)) {
                                    deleteDirectoryRecursively(item.path);
                                } else {
                                    try { Files.move(item.path, BACKUP_DIR.resolve(item.path.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
                                    catch (IOException e) { item.path.toFile().renameTo(new File(item.path.toFile().getAbsolutePath() + ".old")); }
                                }
                            }
                        }
                    }

                    // 🟢 补上漏掉的变量定义
                    Path localModsDir = FMLPaths.MODSDIR.get();

                    String effectiveBaseUrl = baseUrl;
                    if (effectiveBaseUrl.contains(":") && !effectiveBaseUrl.contains("[")) {
                        if (effectiveBaseUrl.chars().filter(ch -> ch == ':').count() > 1) {
                            if (effectiveBaseUrl.startsWith("http://")) effectiveBaseUrl = "http://[" + effectiveBaseUrl.substring(7) + "]";
                            else effectiveBaseUrl = "http://[" + effectiveBaseUrl + "]";
                        }
                    }
                    if (!effectiveBaseUrl.startsWith("http")) effectiveBaseUrl = "http://" + effectiveBaseUrl;
                    if (!effectiveBaseUrl.endsWith("/")) effectiveBaseUrl += "/";


                    for (ModActionItem item : actionItems) {
                        if (!item.selected) continue;

                        List<ModActionItem> tasks = new ArrayList<>();
                        if (item.subItems != null) tasks.addAll(item.subItems);
                        else tasks.add(item);

                        for (ModActionItem task : tasks) {
                            if (task.type.equals("FORCED_OVERWRITE")) {
                                try {
                                    Path target = task.path;
                                    if (target.getParent() != null && !Files.exists(target.getParent())) Files.createDirectories(target.getParent());
                                    String encoded = URLEncoder.encode(task.extraInfo, StandardCharsets.UTF_8.toString()).replace("+", "%20");
                                    downloadFile(new URL(effectiveBaseUrl + "forced/" + encoded), target);
                                } catch (Exception e) {
                                    errorLog.append("强制文件下载失败: ").append(task.name).append(" (").append(e.getClass().getSimpleName()).append(")\n");
                                }
                            } else if (task.type.equals("RESTORE") || task.type.equals("NEW") || task.type.equals("UPDATE")) {
                                boolean restored = false;
                                if (task.type.equals("RESTORE") || task.type.equals("NEW")) { restored = WarehouseManager.restoreFromWarehouse(task.name, localModsDir); }
                                if (!restored) {
                                    try {
                                        String encoded = URLEncoder.encode(task.name, StandardCharsets.UTF_8.toString()).replace("+", "%20");
                                        downloadFile(new URL(effectiveBaseUrl + "download/" + encoded), localModsDir.resolve(task.name));
                                        WarehouseManager.backupToWarehouse(localModsDir.resolve(task.name));
                                    } catch (Exception e) {
                                        errorLog.append("下载失败: ").append(task.name).append(" (").append(e.getClass().getSimpleName()).append(")\n");
                                    }
                                }
                            }
                        }
                    }
                    Minecraft.getInstance().execute(() -> {
                        isExecuting = false;
                        if (errorLog.length() > 0) { execBtn.active = true; execBtn.setMessage(Component.literal("部分失败 (见日志)")); System.out.println(errorLog); }
                        else { isFinished = true; this.clearWidgets(); this.init(); }
                    });
                } catch (Exception e) { e.printStackTrace(); }
            });
        }

        private void deleteDirectoryRecursively(Path dir) {
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) { e.printStackTrace(); }
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
            g.drawString(Minecraft.getInstance().font, item.name, l + 30, t + 8, 0xFFFFFF);
        }
        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 0 && mx < 280) { item.selected = !item.selected; Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F)); return true; } return false;
        }
    }
}
