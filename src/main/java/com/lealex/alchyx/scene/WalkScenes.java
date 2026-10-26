package com.lealex.alchyx.scene;

import com.lealex.alchyx.AlchyX;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Scripted walks, server side: a machine sends a player walking to a spot ({@link #walk}); their client takes the keys,
 * finds a path (AlchyX StepPath: round obstacles, up and down steps, through the machine's own blocks only when there
 * is no way round, inside the box given), follows it at the player's own speed with the camera in third person
 * (client/scene/SceneWalk), and tells the server when it is there; the scene's {@link ArriveHandler} then runs (lay
 * them down, open a screen...). While walking, and while a scene {@link #hold}s them after, the server lets them pass
 * through blocks (no "moved wrongly" pull back) and spares them suffocation.
 * <p>
 * The client keeps the keys and the camera until the scene's client code lets go (SceneWalk.release), so the scene
 * decides when it ends.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class WalkScenes {
    private WalkScenes() {}

    /** Called when a walk reaches its spot (or, with no way found, once the client has put the player there). */
    public interface ArriveHandler {
        void arrived(ServerPlayer player, BlockPos anchor);
    }

    private record Walk(Identifier scene, BlockPos anchor, long until) {}

    private static final int WALK_TICKS = 400;
    private static final Map<Identifier, ArriveHandler> HANDLERS = new HashMap<>();
    private static final Map<UUID, Walk> WALKS = new HashMap<>();
    private static final Set<UUID> HELD = new HashSet<>();
    /** Set by AlchyX's client: runs a walk order on the player's side. */
    public static Consumer<WalkPayload> clientStart = payload -> {};

    public static void onArrive(Identifier scene, ArriveHandler handler) {
        HANDLERS.put(scene, handler);
    }

    /**
     * Sends the player walking to `spot` (feet), facing `faceYaw` once there; `anchor` names what the scene is about
     * (a core); blocks inside min..max may be walked through when there is no way round; `extra` goes to the scene's
     * client code as it is (SceneWalk.extra()).
     */
    public static void walk(ServerPlayer player, Identifier scene, BlockPos anchor, Vec3 spot, float faceYaw, BlockPos min, BlockPos max, int... extra) {
        WALKS.put(player.getUUID(), new Walk(scene, anchor, player.level().getGameTime() + WALK_TICKS));
        List<Integer> data = new java.util.ArrayList<>();
        for (int value : extra) data.add(value);
        PacketDistributor.sendToPlayer(player, new WalkPayload(scene, anchor, spot.x, spot.y, spot.z, faceYaw, min, max, data));
    }

    public static boolean walking(ServerPlayer player) {
        return WALKS.containsKey(player.getUUID());
    }

    /** Keeps (or stops keeping) the player free of block collisions and suffocation while a scene goes on after the walk. */
    public static void hold(ServerPlayer player, boolean on) {
        if (on) HELD.add(player.getUUID());
        else HELD.remove(player.getUUID());
    }

    private static void arrived(ServerPlayer player, ArrivePayload payload) {
        Walk walk = WALKS.get(player.getUUID());
        if (walk == null || !walk.scene().equals(payload.scene()) || !walk.anchor().equals(payload.anchor())) return;
        WALKS.remove(player.getUUID());
        ArriveHandler handler = HANDLERS.get(walk.scene());
        if (handler != null) handler.arrived(player, walk.anchor());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Walk walk = WALKS.get(player.getUUID());
        if (walk != null && player.level().getGameTime() > walk.until()) {
            WALKS.remove(player.getUUID());
            walk = null;
        }
        // the flag is set again at each tick's start, and checked while the movement packets come in between ticks
        if (walk != null || HELD.contains(player.getUUID())) player.noPhysics = true;
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getSource().is(DamageTypes.IN_WALL)
                && (WALKS.containsKey(player.getUUID()) || HELD.contains(player.getUUID()))) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WALKS.remove(event.getEntity().getUUID());
        HELD.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(WalkPayload.TYPE, WalkPayload.STREAM_CODEC, (payload, context) -> clientStart.accept(payload));
        registrar.playToServer(ArrivePayload.TYPE, ArrivePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) arrived(player, payload);
        });
    }

    /** The walk order (server to client). */
    public record WalkPayload(Identifier scene, BlockPos anchor, double x, double y, double z, float faceYaw, BlockPos min, BlockPos max,
                              List<Integer> extra) implements CustomPacketPayload {
        public static final Type<WalkPayload> TYPE = new Type<>(AlchyX.id("scene_walk"));
        private static final StreamCodec<ByteBuf, List<Integer>> INTS = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
        public static final StreamCodec<RegistryFriendlyByteBuf, WalkPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    Identifier.STREAM_CODEC.encode(buf, p.scene());
                    BlockPos.STREAM_CODEC.encode(buf, p.anchor());
                    buf.writeDouble(p.x());
                    buf.writeDouble(p.y());
                    buf.writeDouble(p.z());
                    buf.writeFloat(p.faceYaw());
                    BlockPos.STREAM_CODEC.encode(buf, p.min());
                    BlockPos.STREAM_CODEC.encode(buf, p.max());
                    INTS.encode(buf, p.extra());
                },
                buf -> new WalkPayload(Identifier.STREAM_CODEC.decode(buf), BlockPos.STREAM_CODEC.decode(buf), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readFloat(), BlockPos.STREAM_CODEC.decode(buf), BlockPos.STREAM_CODEC.decode(buf), INTS.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The client is there (client to server). */
    public record ArrivePayload(Identifier scene, BlockPos anchor) implements CustomPacketPayload {
        public static final Type<ArrivePayload> TYPE = new Type<>(AlchyX.id("scene_arrive"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ArrivePayload> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, ArrivePayload::scene, BlockPos.STREAM_CODEC, ArrivePayload::anchor, ArrivePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
