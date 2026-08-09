package com.example.automodsync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Mod(ModSyncMain.MODID)
public class ModSyncMain {
    public static final String MODID = "automodsync";

    public static final CommonConfig CONFIG;
    public static final ForgeConfigSpec CONFIG_SPEC;
    static {
        Pair<CommonConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(CommonConfig::new);
        CONFIG_SPEC = pair.getRight();
        CONFIG = pair.getLeft();
    }
    public static class CommonConfig {
        public final ForgeConfigSpec.IntValue serverPort;
        public final ForgeConfigSpec.IntValue httpWorkerThreads;
        public final ForgeConfigSpec.ConfigValue<String> syncUrl;
        public final ForgeConfigSpec.BooleanValue allowDirectoryDeletion;

        public CommonConfig(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            serverPort = builder.comment("HTTP Server Port").defineInRange("serverPort", 8899, 1024, 65535);
            httpWorkerThreads = builder.comment(
                            "Number of HTTP worker threads used for manifests and file downloads.",
                            "Increase this when many clients synchronize at the same time. Recommended range: 4-16.")
                    .defineInRange("httpWorkerThreads", 8, 2, 64);
            syncUrl = builder.comment("Public URL for clients").define("syncUrl", "");
            allowDirectoryDeletion = builder.comment("Allow rules.json to delete directories recursively. DANGEROUS!")
                    .define("allowDirectoryDeletion", false);
            builder.pop();
        }
    }

    private static final String PROTOCOL = "8";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals
    );

    public ModSyncMain() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (r, i) -> true));

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        int id = 0;
        CHANNEL.registerMessage(id++, SyncUrlPacket.class, SyncUrlPacket::encode, SyncUrlPacket::decode, SyncUrlPacket::handle);
        // 🟢 恢复版本包注册
        CHANNEL.registerMessage(id++, VersionPacket.class, VersionPacket::encode, VersionPacket::decode, VersionPacket::handle);
        CHANNEL.registerMessage(id++, VerificationRequestPacket.class, VerificationRequestPacket::encode,
                VerificationRequestPacket::decode, VerificationRequestPacket::handle);
        CHANNEL.registerMessage(id++, AdminArtifactStartPacket.class, AdminArtifactStartPacket::encode,
                AdminArtifactStartPacket::decode, AdminArtifactStartPacket::handle);
        CHANNEL.registerMessage(id++, AdminArtifactChunkPacket.class, AdminArtifactChunkPacket::encode,
                AdminArtifactChunkPacket::decode, AdminArtifactChunkPacket::handle);
        CHANNEL.registerMessage(id++, AdminArtifactCompletePacket.class, AdminArtifactCompletePacket::encode,
                AdminArtifactCompletePacket::decode, AdminArtifactCompletePacket::handle);
        CHANNEL.registerMessage(id++, VerificationResponsePacket.class, VerificationResponsePacket::encode,
                VerificationResponsePacket::decode, VerificationResponsePacket::handle);
        CHANNEL.registerMessage(id++, VerificationCompletePacket.class, VerificationCompletePacket::encode,
                VerificationCompletePacket::decode, VerificationCompletePacket::handle);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            String url = CONFIG.syncUrl.get();
            if (url != null && !url.isEmpty()) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new SyncUrlPacket(url));
            }
            // 🟢 发送服务端版本号
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new VersionPacket(
                    ServerSideManager.getCurrentVersion(), ServerSideManager.getCurrentRevision(),
                    ServerSideManager.getCurrentVerificationStatus()));
            if (ServerSideManager.isVerificationAdministrator(serverPlayer)
                    && ServerSideManager.hasPendingVerification()) {
                scheduleAdministratorVerification(serverPlayer);
            }
        }
    }

    private void scheduleAdministratorVerification(ServerPlayer loginPlayer) {
        Thread stabilityCheck = new Thread(() -> {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (loginPlayer.getServer() == null) return;
            loginPlayer.getServer().execute(() -> {
                ServerPlayer activePlayer = loginPlayer.getServer().getPlayerList()
                        .getPlayer(loginPlayer.getUUID());
                if (activePlayer != loginPlayer
                        || activePlayer.hasDisconnected()
                        || !ServerSideManager.isVerificationAdministrator(activePlayer)
                        || !ServerSideManager.hasPendingVerification()) return;
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> activePlayer),
                        new VerificationRequestPacket(ServerSideManager.getCurrentVersion(),
                                ServerSideManager.getChangedModIdsForVerification(),
                                ServerSideManager.getChangedModHashesForVerification()));
            });
        }, "AutoModSync-Verification-Stability");
        stabilityCheck.setDaemon(true);
        stabilityCheck.start();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (event.getServer().isDedicatedServer()) ServerSideManager.startServerLogic();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ServerSideManager.stopServer();
    }

    public static class SyncUrlPacket {
        private final String url;
        public SyncUrlPacket(String url) { this.url = url; }
        public static void encode(SyncUrlPacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.url); }
        public static SyncUrlPacket decode(FriendlyByteBuf buf) { return new SyncUrlPacket(buf.readUtf()); }
        public static void handle(SyncUrlPacket msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSideManager.updateCachedUrl(msg.url));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    // 🟢 恢复版本包定义
    public static class VersionPacket {
        private final int ver;
        private final int revision;
        private final ServerSideManager.VerificationStatus status;
        public VersionPacket(int ver, int revision, ServerSideManager.VerificationStatus status) {
            this.ver = ver; this.revision = revision; this.status = status;
        }
        public static void encode(VersionPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.ver); buf.writeInt(msg.revision); buf.writeEnum(msg.status);
        }
        public static VersionPacket decode(FriendlyByteBuf buf) {
            return new VersionPacket(buf.readInt(), buf.readInt(),
                    buf.readEnum(ServerSideManager.VerificationStatus.class));
        }
        public static void handle(VersionPacket msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                // 安全调用客户端逻辑
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ClientSideManager.setServerVersion(msg.ver, msg.revision, msg.status));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class VerificationRequestPacket {
        private final int candidateVersion;
        private final List<String> changedModIds;
        private final List<String> changedServerHashes;
        public VerificationRequestPacket(int candidateVersion, List<String> changedModIds,
                                         List<String> changedServerHashes) {
            this.candidateVersion = candidateVersion;
            this.changedModIds = changedModIds == null ? new ArrayList<>() : changedModIds;
            this.changedServerHashes = changedServerHashes == null ? new ArrayList<>() : changedServerHashes;
        }
        public static void encode(VerificationRequestPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.candidateVersion);
            buf.writeCollection(msg.changedModIds, (target, value) -> target.writeUtf(value, 128));
            buf.writeCollection(msg.changedServerHashes, (target, value) -> target.writeUtf(value, 40));
        }
        public static VerificationRequestPacket decode(FriendlyByteBuf buf) {
            return new VerificationRequestPacket(buf.readInt(), buf.readList(source -> source.readUtf(128)),
                    buf.readList(source -> source.readUtf(40)));
        }
        public static void handle(VerificationRequestPacket msg,
                                  Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientSideManager.respondToVerification(msg.candidateVersion,
                            msg.changedModIds, msg.changedServerHashes)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class AdminArtifactStartPacket {
        private final int candidateVersion;
        private final String fileName;
        private final String hash;
        private final long size;
        private final List<String> modIds;
        public AdminArtifactStartPacket(int candidateVersion, String fileName, String hash,
                                        long size, List<String> modIds) {
            this.candidateVersion = candidateVersion; this.fileName = fileName; this.hash = hash;
            this.size = size; this.modIds = modIds == null ? new ArrayList<>() : modIds;
        }
        public static void encode(AdminArtifactStartPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.candidateVersion); buf.writeUtf(msg.fileName, 512);
            buf.writeUtf(msg.hash, 40); buf.writeLong(msg.size);
            buf.writeCollection(msg.modIds, (target, value) -> target.writeUtf(value, 128));
        }
        public static AdminArtifactStartPacket decode(FriendlyByteBuf buf) {
            return new AdminArtifactStartPacket(buf.readInt(), buf.readUtf(512), buf.readUtf(40),
                    buf.readLong(), buf.readList(source -> source.readUtf(128)));
        }
        public static void handle(AdminArtifactStartPacket msg,
                                  Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            ctx.get().enqueueWork(() -> ServerSideManager.beginAdminArtifactUpload(sender,
                    msg.candidateVersion, msg.fileName, msg.hash, msg.size, msg.modIds));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class AdminArtifactChunkPacket {
        private final int candidateVersion;
        private final String hash;
        private final long offset;
        private final byte[] data;
        public AdminArtifactChunkPacket(int candidateVersion, String hash, long offset, byte[] data) {
            this.candidateVersion = candidateVersion; this.hash = hash; this.offset = offset; this.data = data;
        }
        public static void encode(AdminArtifactChunkPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.candidateVersion); buf.writeUtf(msg.hash, 40); buf.writeLong(msg.offset);
            buf.writeByteArray(msg.data);
        }
        public static AdminArtifactChunkPacket decode(FriendlyByteBuf buf) {
            return new AdminArtifactChunkPacket(buf.readInt(), buf.readUtf(40), buf.readLong(),
                    buf.readByteArray(30 * 1024));
        }
        public static void handle(AdminArtifactChunkPacket msg,
                                  Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            ctx.get().enqueueWork(() -> ServerSideManager.appendAdminArtifactUpload(sender,
                    msg.candidateVersion, msg.hash, msg.offset, msg.data));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class AdminArtifactCompletePacket {
        private final int candidateVersion;
        private final String hash;
        public AdminArtifactCompletePacket(int candidateVersion, String hash) {
            this.candidateVersion = candidateVersion; this.hash = hash;
        }
        public static void encode(AdminArtifactCompletePacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.candidateVersion); buf.writeUtf(msg.hash, 40);
        }
        public static AdminArtifactCompletePacket decode(FriendlyByteBuf buf) {
            return new AdminArtifactCompletePacket(buf.readInt(), buf.readUtf(40));
        }
        public static void handle(AdminArtifactCompletePacket msg,
                                  Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            ctx.get().enqueueWork(() -> ServerSideManager.finishAdminArtifactUpload(sender,
                    msg.candidateVersion, msg.hash));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class VerificationResponsePacket {
        private final int candidateVersion;
        private final List<ServerSideManager.AdminModState> states;
        public VerificationResponsePacket(int candidateVersion, List<ServerSideManager.AdminModState> states) {
            this.candidateVersion = candidateVersion;
            this.states = states == null ? new ArrayList<>() : states;
        }
        public static void encode(VerificationResponsePacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.candidateVersion);
            int count = Math.min(msg.states.size(), 2048);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                ServerSideManager.AdminModState state = msg.states.get(i);
                buf.writeUtf(state.modId == null ? "" : state.modId, 128);
                buf.writeUtf(state.fileName == null ? "" : state.fileName, 512);
                buf.writeUtf(state.hash == null ? "" : state.hash, 40);
                buf.writeUtf(state.previousHash == null ? "" : state.previousHash, 40);
                buf.writeBoolean(state.baselineKnown);
                buf.writeBoolean(state.administratorAddedClientArtifact);
                buf.writeBoolean(state.administratorRemovedClientArtifact);
            }
        }
        public static VerificationResponsePacket decode(FriendlyByteBuf buf) {
            int candidate = buf.readInt();
            int count = Math.min(buf.readVarInt(), 2048);
            List<ServerSideManager.AdminModState> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                result.add(new ServerSideManager.AdminModState(
                        buf.readUtf(128), buf.readUtf(512), buf.readUtf(40),
                        buf.readUtf(40), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
            }
            return new VerificationResponsePacket(candidate, result);
        }
        public static void handle(VerificationResponsePacket msg,
                                  Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (ServerSideManager.submitAdminVerification(sender, msg.candidateVersion, msg.states)
                        && sender != null) {
                    boolean clientChangesPublished = ServerSideManager.getCurrentVersion() > msg.candidateVersion;
                    VersionPacket published = new VersionPacket(ServerSideManager.getCurrentVersion(),
                            ServerSideManager.getCurrentRevision(), ServerSideManager.getCurrentVerificationStatus());
                    for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), published);
                    }
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                            new VerificationCompletePacket(ServerSideManager.getCurrentVersion(),
                                    ServerSideManager.getCurrentRevision()));
                    if (clientChangesPublished) {
                        sender.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§a[AutoModSync] 检测到客户端改动，已自动推送 v"
                                        + ServerSideManager.getCurrentVersion() + "（候选版本 +1）"));
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class VerificationCompletePacket {
        private final int version;
        private final int revision;
        public VerificationCompletePacket(int version, int revision) {
            this.version = version; this.revision = revision;
        }
        public static void encode(VerificationCompletePacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.version); buf.writeInt(msg.revision);
        }
        public static VerificationCompletePacket decode(FriendlyByteBuf buf) {
            return new VerificationCompletePacket(buf.readInt(), buf.readInt());
        }
        public static void handle(VerificationCompletePacket msg,
                                  Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientSideManager.commitAdministratorVerification(
                            msg.version, msg.revision)));
            ctx.get().setPacketHandled(true);
        }
    }
}
