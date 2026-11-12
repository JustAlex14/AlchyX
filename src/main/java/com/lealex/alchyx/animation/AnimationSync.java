package com.lealex.alchyx.animation;

import com.lealex.alchyx.AlchyX;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/**
 * Sends the animation definitions to players: when they join and after every {@code /reload}
 * ({@link OnDatapackSyncEvent} covers both). The client keeps them in {@link ClientAnimations}.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class AnimationSync {
    private AnimationSync() {}

    public record Payload(List<ResolvedAnimation> animations) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(AlchyX.id("machine_animations"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistriesTrusted(ResolvedAnimation.CODEC.listOf()).map(Payload::new, Payload::animations);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The mini structure files ({@link MachineMinis}), by id. */
    public record Minis(java.util.Map<net.minecraft.resources.Identifier, MiniStructure.File> files) implements CustomPacketPayload {
        public static final Type<Minis> TYPE = new Type<>(AlchyX.id("machine_minis"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Minis> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistriesTrusted(com.mojang.serialization.Codec.unboundedMap(net.minecraft.resources.Identifier.CODEC,
                        MiniStructure.File.CODEC)).map(Minis::new, Minis::files);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(AlchyX.id("machine_animations"), new MachineAnimations());
        event.addListener(AlchyX.id("machine_minis"), new MachineMinis());
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Payload.TYPE, Payload.STREAM_CODEC,
                (payload, context) -> ClientAnimations.set(payload.animations()));
        event.registrar("1").playToClient(Minis.TYPE, Minis.STREAM_CODEC,
                (payload, context) -> ClientAnimations.setMinis(payload.files()));
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Payload payload = new Payload(MachineAnimations.resolved());
        Minis minis = new Minis(MachineMinis.all());
        event.getRelevantPlayers().forEach(player -> {
            PacketDistributor.sendToPlayer(player, payload);
            PacketDistributor.sendToPlayer(player, minis);
        });
    }
}
