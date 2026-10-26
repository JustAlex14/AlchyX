package com.lealex.alchyx.config;

import com.lealex.alchyx.AlchyX;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Makes a mod's COMMON config the server's on its players' clients, so screens and HUDs show the numbers the server
 * plays by (costs, cooldowns, tiers). The game only does this for SERVER configs, which live per world; a mod keeps its
 * one config file and calls {@link #share(ModContainer)} after registering it.
 *
 * Sent on login and again when the server's file is reloaded; the client holds it in memory (its own file is never
 * written) and reads its own files back when it leaves the server. Not sent to the integrated server's own player.
 */
public final class CommonConfigSync {
    private CommonConfigSync() {}

    /** The file names of the shared configs: the only ones a client accepts. */
    private static final Set<String> SHARED = ConcurrentHashMap.newKeySet();

    /** Shares every COMMON config this mod registered so far. Call from the mod's constructor, after registerConfig. */
    public static void share(ModContainer mod) {
        SHARED.addAll(ModConfigs.getConfigFileNames(mod.getModId(), ModConfig.Type.COMMON));
        mod.getEventBus().addListener(ModConfigEvent.Reloading.class, CommonConfigSync::onReloading);
    }

    public record Payload(String file, byte[] contents) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyX.MODID, "common_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Payload::file,
                ByteBufCodecs.BYTE_ARRAY, Payload::contents,
                Payload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static boolean remote(ServerPlayer player) {
        return !player.connection.getConnection().isMemoryConnection();
    }

    private static void send(ServerPlayer player, ModConfig config) {
        try {
            PacketDistributor.sendToPlayer(player, new Payload(config.getFileName(), Files.readAllBytes(config.getFullPath())));
        } catch (IOException | RuntimeException unreadable) {
            AlchyX.LOGGER.warn("Could not send config {} to {}", config.getFileName(), player.getName().getString(), unreadable);
        }
    }

    private static void onReloading(ModConfigEvent.Reloading event) {
        ModConfig config = event.getConfig();
        if (config.getType() != ModConfig.Type.COMMON || !SHARED.contains(config.getFileName())) return;
        if (config.getLoadedConfig() == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return; // a client that received it from a server: nothing to pass on
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) if (remote(player)) send(player, config);
        });
    }

    @EventBusSubscriber(modid = AlchyX.MODID)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToClient(Payload.TYPE, Payload.STREAM_CODEC, (payload, context) -> Client.receive(payload));
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !remote(player)) return;
            for (String file : SHARED) {
                ModConfig config = ModConfigs.getFileMap().get(file);
                if (config != null && config.getLoadedConfig() != null) send(player, config);
            }
        }
    }

    /** The client side: takes the server's configs, and its own back on leaving. */
    public static final class Client {
        private Client() {}

        private static volatile boolean borrowed;

        static void receive(Payload payload) {
            if (!SHARED.contains(payload.file())) return; // only configs a mod chose to share
            ModConfig config = ModConfigs.getFileMap().get(payload.file());
            if (config == null || config.getType() != ModConfig.Type.COMMON) return;
            ConfigTracker.acceptSyncedConfig(config, payload.contents());
            borrowed = true;
        }

        @EventBusSubscriber(modid = AlchyX.MODID, value = Dist.CLIENT)
        public static final class Events {
            private Events() {}

            @SubscribeEvent
            public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
                if (!borrowed) return;
                borrowed = false;
                ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get()); // our own files again
            }
        }
    }
}
