package com.example.automodsync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

@Mod(ModSyncMain.MODID)
public class ModSyncMain {
    public static final String MODID = "automodsync";
    private static final String PROTOCOL = "8";

    public static final CommonConfig CONFIG;
    public static final ModConfigSpec CONFIG_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CONFIG = new CommonConfig(builder);
        CONFIG_SPEC = builder.build();
    }

    public static class CommonConfig {
        public final ModConfigSpec.ConfigValue<Integer> serverPort;
        public final ModConfigSpec.ConfigValue<Integer> httpWorkerThreads;
        public final ModConfigSpec.ConfigValue<String> syncUrl;
        public final ModConfigSpec.ConfigValue<Boolean> allowDirectoryDeletion;

        public CommonConfig(ModConfigSpec.Builder builder) {
            builder.push("general");
            serverPort = builder.comment("HTTP Server Port")
                    .defineInRange("serverPort", 8899, 1024, 65535, Integer.class);
            httpWorkerThreads = builder.comment(
                            "Number of HTTP worker threads used for manifests and file downloads.",
                            "Increase this when many clients synchronize at the same time. Recommended range: 4-16.")
                    .defineInRange("httpWorkerThreads", 8, 2, 64, Integer.class);
            syncUrl = builder.comment("Public URL for clients").define("syncUrl", "");
            allowDirectoryDeletion = builder.comment("Allow rules.json to delete directories recursively. DANGEROUS!")
                    .define("allowDirectoryDeletion", false);
            builder.pop();
        }
    }

    public ModSyncMain(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
        modEventBus.addListener(ModSyncMain::registerPayloads);
        NeoForge.EVENT_BUS.register(ModSyncMain.class);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // Server -> client
        registrar.playToClient(SyncUrlPacket.TYPE, SyncUrlPacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> ClientPayloadHandler.onSyncUrl(msg.url)));
        registrar.playToClient(VersionPacket.TYPE, VersionPacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> ClientPayloadHandler.onServerVersion(
                        msg.ver, msg.revision, msg.status)));
        registrar.playToClient(VerificationRequestPacket.TYPE, VerificationRequestPacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> ClientPayloadHandler.onVerificationRequest(
                        msg.candidateVersion, msg.changedModIds, msg.changedServerHashes)));
        registrar.playToClient(VerificationCompletePacket.TYPE, VerificationCompletePacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> ClientPayloadHandler.onVerificationComplete(
                        msg.version, msg.revision)));

        // Client -> server
        registrar.playToServer(AdminArtifactStartPacket.TYPE, AdminArtifactStartPacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sender) {
                        ServerSideManager.beginAdminArtifactUpload(sender, msg.candidateVersion,
                                msg.fileName, msg.hash, msg.size, msg.modIds);
                    }
                }));
        registrar.playToServer(AdminArtifactChunkPacket.TYPE, AdminArtifactChunkPacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sender) {
                        ServerSideManager.appendAdminArtifactUpload(sender, msg.candidateVersion,
                                msg.hash, msg.offset, msg.data);
                    }
                }));
        registrar.playToServer(AdminArtifactCompletePacket.TYPE, AdminArtifactCompletePacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sender) {
                        ServerSideManager.finishAdminArtifactUpload(sender, msg.candidateVersion, msg.hash);
                    }
                }));
        registrar.playToServer(VerificationResponsePacket.TYPE, VerificationResponsePacket.STREAM_CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sender) {
                        handleVerificationResponse(sender, msg);
                    }
                }));
    }

    private static void handleVerificationResponse(ServerPlayer sender, VerificationResponsePacket msg) {
        if (!ServerSideManager.submitAdminVerification(sender, msg.candidateVersion, msg.states)) return;
        boolean clientChangesPublished = ServerSideManager.getCurrentVersion() > msg.candidateVersion;
        VersionPacket published = new VersionPacket(ServerSideManager.getCurrentVersion(),
                ServerSideManager.getCurrentRevision(), ServerSideManager.getCurrentVerificationStatus());
        PacketDistributor.sendToAllPlayers(published);
        PacketDistributor.sendToPlayer(sender, new VerificationCompletePacket(
                ServerSideManager.getCurrentVersion(), ServerSideManager.getCurrentRevision()));
        if (clientChangesPublished) {
            sender.sendSystemMessage(Component.literal(
                    "§a[AutoModSync] 检测到客户端改动，已自动推送 v"
                            + ServerSideManager.getCurrentVersion() + "（候选版本 +1）"));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            String url = CONFIG.syncUrl.get();
            if (url != null && !url.isEmpty()) {
                PacketDistributor.sendToPlayer(serverPlayer, new SyncUrlPacket(url));
            }
            PacketDistributor.sendToPlayer(serverPlayer, new VersionPacket(
                    ServerSideManager.getCurrentVersion(), ServerSideManager.getCurrentRevision(),
                    ServerSideManager.getCurrentVerificationStatus()));
            if (ServerSideManager.isVerificationAdministrator(serverPlayer)
                    && ServerSideManager.hasPendingVerification()) {
                scheduleAdministratorVerification(serverPlayer);
            }
        }
    }

    private static void scheduleAdministratorVerification(ServerPlayer loginPlayer) {
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
                PacketDistributor.sendToPlayer(activePlayer,
                        new VerificationRequestPacket(ServerSideManager.getCurrentVersion(),
                                ServerSideManager.getChangedModIdsForVerification(),
                                ServerSideManager.getChangedModHashesForVerification()));
            });
        }, "AutoModSync-Verification-Stability");
        stabilityCheck.setDaemon(true);
        stabilityCheck.start();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (event.getServer().isDedicatedServer()) ServerSideManager.startServerLogic();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerSideManager.stopServer();
    }

    // ---------------------------------------------------------------------
    // Payloads
    // ---------------------------------------------------------------------

    public static class SyncUrlPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncUrlPacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "sync_url"));
        public static final StreamCodec<FriendlyByteBuf, SyncUrlPacket> STREAM_CODEC =
                StreamCodec.of((buf, msg) -> buf.writeUtf(msg.url), buf -> new SyncUrlPacket(buf.readUtf()));

        private final String url;

        public SyncUrlPacket(String url) {
            this.url = url == null ? "" : url;
        }

        @Override
        public CustomPacketPayload.Type<SyncUrlPacket> type() {
            return TYPE;
        }
    }

    public static class VersionPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VersionPacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "version"));
        public static final StreamCodec<FriendlyByteBuf, VersionPacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeInt(msg.ver);
                    buf.writeInt(msg.revision);
                    buf.writeEnum(msg.status);
                },
                buf -> new VersionPacket(buf.readInt(), buf.readInt(),
                        buf.readEnum(ServerSideManager.VerificationStatus.class)));

        private final int ver;
        private final int revision;
        private final ServerSideManager.VerificationStatus status;

        public VersionPacket(int ver, int revision, ServerSideManager.VerificationStatus status) {
            this.ver = ver;
            this.revision = revision;
            this.status = status;
        }

        @Override
        public CustomPacketPayload.Type<VersionPacket> type() {
            return TYPE;
        }
    }

    public static class VerificationRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VerificationRequestPacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "verification_request"));
        public static final StreamCodec<FriendlyByteBuf, VerificationRequestPacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeInt(msg.candidateVersion);
                    writeStrings(buf, msg.changedModIds, 128);
                    writeStrings(buf, msg.changedServerHashes, 40);
                },
                buf -> new VerificationRequestPacket(buf.readInt(),
                        readStrings(buf, 128), readStrings(buf, 40)));

        private final int candidateVersion;
        private final List<String> changedModIds;
        private final List<String> changedServerHashes;

        public VerificationRequestPacket(int candidateVersion, List<String> changedModIds,
                                         List<String> changedServerHashes) {
            this.candidateVersion = candidateVersion;
            this.changedModIds = changedModIds == null ? new ArrayList<>() : changedModIds;
            this.changedServerHashes = changedServerHashes == null ? new ArrayList<>() : changedServerHashes;
        }

        @Override
        public CustomPacketPayload.Type<VerificationRequestPacket> type() {
            return TYPE;
        }
    }

    public static class AdminArtifactStartPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AdminArtifactStartPacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "admin_artifact_start"));
        public static final StreamCodec<FriendlyByteBuf, AdminArtifactStartPacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeInt(msg.candidateVersion);
                    buf.writeUtf(msg.fileName, 512);
                    buf.writeUtf(msg.hash, 40);
                    buf.writeLong(msg.size);
                    writeStrings(buf, msg.modIds, 128);
                },
                buf -> new AdminArtifactStartPacket(buf.readInt(), buf.readUtf(512), buf.readUtf(40),
                        buf.readLong(), readStrings(buf, 128)));

        private final int candidateVersion;
        private final String fileName;
        private final String hash;
        private final long size;
        private final List<String> modIds;

        public AdminArtifactStartPacket(int candidateVersion, String fileName, String hash,
                                        long size, List<String> modIds) {
            this.candidateVersion = candidateVersion;
            this.fileName = fileName;
            this.hash = hash;
            this.size = size;
            this.modIds = modIds == null ? new ArrayList<>() : modIds;
        }

        @Override
        public CustomPacketPayload.Type<AdminArtifactStartPacket> type() {
            return TYPE;
        }
    }

    public static class AdminArtifactChunkPacket implements CustomPacketPayload {
        public static final int MAX_CHUNK_BYTES = 30 * 1024;
        public static final CustomPacketPayload.Type<AdminArtifactChunkPacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "admin_artifact_chunk"));
        public static final StreamCodec<FriendlyByteBuf, AdminArtifactChunkPacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeInt(msg.candidateVersion);
                    buf.writeUtf(msg.hash, 40);
                    buf.writeLong(msg.offset);
                    buf.writeByteArray(msg.data);
                },
                buf -> new AdminArtifactChunkPacket(buf.readInt(), buf.readUtf(40), buf.readLong(),
                        buf.readByteArray(MAX_CHUNK_BYTES)));

        private final int candidateVersion;
        private final String hash;
        private final long offset;
        private final byte[] data;

        public AdminArtifactChunkPacket(int candidateVersion, String hash, long offset, byte[] data) {
            this.candidateVersion = candidateVersion;
            this.hash = hash;
            this.offset = offset;
            this.data = data;
        }

        @Override
        public CustomPacketPayload.Type<AdminArtifactChunkPacket> type() {
            return TYPE;
        }
    }

    public static class AdminArtifactCompletePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AdminArtifactCompletePacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "admin_artifact_complete"));
        public static final StreamCodec<FriendlyByteBuf, AdminArtifactCompletePacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeInt(msg.candidateVersion);
                    buf.writeUtf(msg.hash, 40);
                },
                buf -> new AdminArtifactCompletePacket(buf.readInt(), buf.readUtf(40)));

        private final int candidateVersion;
        private final String hash;

        public AdminArtifactCompletePacket(int candidateVersion, String hash) {
            this.candidateVersion = candidateVersion;
            this.hash = hash;
        }

        @Override
        public CustomPacketPayload.Type<AdminArtifactCompletePacket> type() {
            return TYPE;
        }
    }

    public static class VerificationResponsePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VerificationResponsePacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "verification_response"));
        public static final StreamCodec<FriendlyByteBuf, VerificationResponsePacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
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
                },
                buf -> {
                    int candidate = buf.readInt();
                    int count = Math.min(buf.readVarInt(), 2048);
                    List<ServerSideManager.AdminModState> result = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        result.add(new ServerSideManager.AdminModState(
                                buf.readUtf(128), buf.readUtf(512), buf.readUtf(40),
                                buf.readUtf(40), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
                    }
                    return new VerificationResponsePacket(candidate, result);
                });

        private final int candidateVersion;
        private final List<ServerSideManager.AdminModState> states;

        public VerificationResponsePacket(int candidateVersion, List<ServerSideManager.AdminModState> states) {
            this.candidateVersion = candidateVersion;
            this.states = states == null ? new ArrayList<>() : states;
        }

        @Override
        public CustomPacketPayload.Type<VerificationResponsePacket> type() {
            return TYPE;
        }
    }

    public static class VerificationCompletePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VerificationCompletePacket> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MODID, "verification_complete"));
        public static final StreamCodec<FriendlyByteBuf, VerificationCompletePacket> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> {
                    buf.writeInt(msg.version);
                    buf.writeInt(msg.revision);
                },
                buf -> new VerificationCompletePacket(buf.readInt(), buf.readInt()));

        private final int version;
        private final int revision;

        public VerificationCompletePacket(int version, int revision) {
            this.version = version;
            this.revision = revision;
        }

        @Override
        public CustomPacketPayload.Type<VerificationCompletePacket> type() {
            return TYPE;
        }
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> values, int maxLength) {
        int count = values == null ? 0 : Math.min(values.size(), 2048);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            String value = values.get(i);
            buf.writeUtf(value == null ? "" : value, maxLength);
        }
    }

    private static List<String> readStrings(FriendlyByteBuf buf, int maxLength) {
        int count = Math.min(buf.readVarInt(), 2048);
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(buf.readUtf(maxLength));
        return result;
    }
}
