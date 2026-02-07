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
        public final ForgeConfigSpec.ConfigValue<String> syncUrl;
        public final ForgeConfigSpec.BooleanValue allowDirectoryDeletion;

        public CommonConfig(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            serverPort = builder.comment("HTTP Server Port").defineInRange("serverPort", 8899, 1024, 65535);
            syncUrl = builder.comment("Public URL for clients").define("syncUrl", "");
            allowDirectoryDeletion = builder.comment("Allow rules.json to delete directories recursively. DANGEROUS!")
                    .define("allowDirectoryDeletion", false);
            builder.pop();
        }
    }

    private static final String PROTOCOL = "1";
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
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            String url = CONFIG.syncUrl.get();
            if (url != null && !url.isEmpty()) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new SyncUrlPacket(url));
            }
            // 🟢 发送服务端版本号
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new VersionPacket(ServerSideManager.getCurrentVersion()));
        }
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
        public VersionPacket(int ver) { this.ver = ver; }
        public static void encode(VersionPacket msg, FriendlyByteBuf buf) { buf.writeInt(msg.ver); }
        public static VersionPacket decode(FriendlyByteBuf buf) { return new VersionPacket(buf.readInt()); }
        public static void handle(VersionPacket msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                // 安全调用客户端逻辑
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSideManager.setServerVersion(msg.ver));
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
