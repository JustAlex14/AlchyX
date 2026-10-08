package com.lealex.alchyx.miniature;

import com.lealex.alchyx.AlchyX;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * Live miniatures, for mods that want them: a client asks for "the box behind this thing" and keeps asking while
 * it wants the picture. The server answers with a {@link BlockBox}, then watches that part of the world for that
 * player: when a block in it changes, it is read again and sent if it really differs. Who is inside (mobs, players)
 * can travel along, a few times a second, as {@link Figure}s.
 *
 * A mod takes part by registering a {@link Resolver} under a kind id: given a player and a key (a position that
 * means something to the mod: a gate, a machine), it says which box of which dimension that is, or nothing. The
 * client asks with the same kind and key (the client's Miniatures.request) and reads the answers from
 * {@link #clientBox} and {@link #clientFigures}.
 *
 * A mod that only draws a fixed miniature needs none of this: it builds a {@link BlockBox} and a BoxMesh itself.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class BoxWatch {
    private BoxWatch() {}

    /** A box of the world to show: corners included. {@code trimmed}: see {@link BlockBox}. {@code figures}: send who is inside. */
    public record Source(ResourceKey<Level> dimension, BlockPos min, BlockPos max, boolean trimmed, boolean figures) {
        private boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    /** Says what a key shows to a player: a box, or null for nothing (not allowed, too far, gone). Server thread. */
    @FunctionalInterface
    public interface Resolver {
        @Nullable Source resolve(ServerPlayer player, GlobalPos key);
    }

    private static final Map<Identifier, Resolver> RESOLVERS = new ConcurrentHashMap<>();

    /** Registers what a kind of key shows. Call once, from the mod's constructor or setup. */
    public static void register(Identifier kind, Resolver resolver) {
        RESOLVERS.put(kind, resolver);
    }

    /** Ticks between two rounds of sending changed boxes, of sending figures, and of reading every watched box whole. */
    private static final int CHECK_EVERY = 10, FULL_CHECK_EVERY = 200;
    public static final int FIGURES_EVERY = 4;
    /** How long a watch lasts without the client asking again, and the most figures per box. */
    private static final int WATCH_TICKS = 300, MAX_FIGURES = 48;

    private record Watch(Source source, int sentHash, long asked) {}

    /** What is known of a watched box: its last picture, kept for when the place isn't loaded. */
    private static final class Entry {
        private BlockBox box;
        private int hash;
        private boolean changed;

        private Entry(BlockBox box) {
            this.box = box;
            this.hash = box.contentHash();
        }
    }

    // Server thread only
    private static final Map<UUID, Map<GlobalPos, Watch>> WATCHES = new HashMap<>();
    private static final Map<Source, Entry> ENTRIES = new HashMap<>();
    private static final Map<UUID, Set<GlobalPos>> SHOWN_FIGURES = new HashMap<>();

    // Client: the latest answers per key
    private static final Map<GlobalPos, BlockBox> CLIENT_BOXES = new ConcurrentHashMap<>();
    private static final Map<GlobalPos, List<Figure>> CLIENT_FIGURES = new ConcurrentHashMap<>();

    /** Client: the latest box told for a key, or null. */
    public static @Nullable BlockBox clientBox(GlobalPos key) {
        return CLIENT_BOXES.get(key);
    }

    /** Client: who is in the box of a key, as last told (a new list object each time). */
    public static List<Figure> clientFigures(GlobalPos key) {
        return CLIENT_FIGURES.getOrDefault(key, List.of());
    }

    public static void clientForget(GlobalPos key) {
        CLIENT_BOXES.remove(key);
        CLIENT_FIGURES.remove(key);
    }

    public static void clientClear() {
        CLIENT_BOXES.clear();
        CLIENT_FIGURES.clear();
    }

    // ---- What travels ----

    /** Client to server: "show me what this key shows". {@code have} = the client still holds a picture of it. */
    public record Request(Identifier kind, GlobalPos key, boolean have) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(AlchyX.id("box_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Request::kind, GlobalPos.STREAM_CODEC, Request::key, ByteBufCodecs.BOOL, Request::have, Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: the box a key shows. */
    public record Box(GlobalPos key, BlockBox box) implements CustomPacketPayload {
        public static final Type<Box> TYPE = new Type<>(AlchyX.id("box"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Box> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    GlobalPos.STREAM_CODEC.encode(buf, data.key);
                    data.box.write(buf);
                },
                buf -> new Box(GlobalPos.STREAM_CODEC.decode(buf), BlockBox.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: who is in the box a key shows, right now. */
    public record Figures(GlobalPos key, List<Figure> figures) implements CustomPacketPayload {
        public static final Type<Figures> TYPE = new Type<>(AlchyX.id("box_figures"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Figures> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    GlobalPos.STREAM_CODEC.encode(buf, data.key);
                    buf.writeVarInt(data.figures.size());
                    for (Figure figure : data.figures) figure.write(buf);
                },
                buf -> {
                    GlobalPos key = GlobalPos.STREAM_CODEC.decode(buf);
                    int count = Math.min(buf.readVarInt(), MAX_FIGURES);
                    List<Figure> figures = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) figures.add(Figure.read(buf));
                    return new Figures(key, figures);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) answer(player, request);
        });
        registrar.playToClient(Box.TYPE, Box.STREAM_CODEC, (data, context) -> {
            CLIENT_BOXES.put(data.key(), data.box());
            if (data.box().isEmpty()) CLIENT_FIGURES.remove(data.key());
        });
        registrar.playToClient(Figures.TYPE, Figures.STREAM_CODEC, (data, context) -> CLIENT_FIGURES.put(data.key(), data.figures()));
    }

    // ---- Server ----

    private static void answer(ServerPlayer player, Request request) {
        Resolver resolver = RESOLVERS.get(request.kind());
        Source source = resolver == null ? null : resolver.resolve(player, request.key());
        Map<GlobalPos, Watch> watches = WATCHES.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        if (source == null) {
            // Nothing (any more): a client still showing something is wiped
            if (watches.remove(request.key()) != null || request.have()) {
                PacketDistributor.sendToPlayer(player, new Box(request.key(), BlockBox.empty()));
                PacketDistributor.sendToPlayer(player, new Figures(request.key(), List.of()));
            }
            return;
        }
        MinecraftServer server = player.level().getServer();
        ServerLevel level = server.getLevel(source.dimension());
        if (level == null) return;
        // The first look at a place reads it (loading it if need be); afterwards it is only read again while loaded
        Entry entry = ENTRIES.get(source);
        if (entry == null) {
            entry = new Entry(BlockBox.capture(level, source.min(), source.max(), source.trimmed()));
            ENTRIES.put(source, entry);
        }
        Watch watch = watches.get(request.key());
        boolean same = request.have() && watch != null && watch.source.equals(source) && watch.sentHash == entry.hash;
        if (watch != null && !watch.source.equals(source) && !source.figures()) {
            PacketDistributor.sendToPlayer(player, new Figures(request.key(), List.of())); // the old box's figures are gone
        }
        watches.put(request.key(), new Watch(source, entry.hash, server.getTickCount()));
        if (!same) PacketDistributor.sendToPlayer(player, new Box(request.key(), entry.box));
    }

    /** A block changed somewhere: every watched box that holds it is read again on the next round. */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (ENTRIES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        for (Map.Entry<Source, Entry> entry : ENTRIES.entrySet()) {
            if (entry.getKey().dimension() == level.dimension() && entry.getKey().contains(event.getPos())) entry.getValue().changed = true;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        if (WATCHES.isEmpty()) return;
        if (now % FIGURES_EVERY == 0) sendFigures(server);
        if (now % CHECK_EVERY != 0) return;
        boolean all = now % FULL_CHECK_EVERY < CHECK_EVERY;

        // Who watches what; watches nobody renewed are dropped
        Set<Source> watched = new HashSet<>();
        Iterator<Map.Entry<UUID, Map<GlobalPos, Watch>>> players = WATCHES.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<UUID, Map<GlobalPos, Watch>> entry = players.next();
            if (server.getPlayerList().getPlayer(entry.getKey()) == null) {
                players.remove();
                continue;
            }
            entry.getValue().values().removeIf(watch -> now - watch.asked > WATCH_TICKS);
            for (Watch watch : entry.getValue().values()) watched.add(watch.source);
            if (entry.getValue().isEmpty()) players.remove();
        }
        // Watched boxes that changed (or all of them, now and then: a few changes notify nobody) are read again, while loaded
        for (Source source : watched) {
            Entry entry = ENTRIES.get(source);
            if (entry == null || !(all || entry.changed)) continue;
            ServerLevel level = server.getLevel(source.dimension());
            if (level == null || !loaded(level, source)) continue;
            entry.changed = false;
            entry.box = BlockBox.capture(level, source.min(), source.max(), source.trimmed());
            entry.hash = entry.box.contentHash();
        }
        // Whoever holds an older picture gets the new one
        for (Map.Entry<UUID, Map<GlobalPos, Watch>> entry : WATCHES.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            for (Map.Entry<GlobalPos, Watch> watching : entry.getValue().entrySet()) {
                Watch watch = watching.getValue();
                Entry known = ENTRIES.get(watch.source);
                if (known == null || known.hash == watch.sentHash) continue;
                watching.setValue(new Watch(watch.source, known.hash, watch.asked));
                PacketDistributor.sendToPlayer(player, new Box(watching.getKey(), known.box));
            }
        }
    }

    private static void sendFigures(MinecraftServer server) {
        Map<Source, List<Figure>> gathered = new HashMap<>();
        for (Map.Entry<UUID, Map<GlobalPos, Watch>> entry : WATCHES.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            Set<GlobalPos> shown = SHOWN_FIGURES.computeIfAbsent(entry.getKey(), id -> new HashSet<>());
            for (Map.Entry<GlobalPos, Watch> watching : entry.getValue().entrySet()) {
                Source source = watching.getValue().source;
                if (!source.figures()) continue;
                ServerLevel level = server.getLevel(source.dimension());
                if (level == null || !loaded(level, source)) continue;
                List<Figure> figures = gathered.computeIfAbsent(source, key -> Figure.gather(level, source.min(), source.max(), MAX_FIGURES));
                // An empty box is sent once, to clear the figures the client still shows
                if (figures.isEmpty()) {
                    if (!shown.remove(watching.getKey())) continue;
                } else {
                    shown.add(watching.getKey());
                }
                PacketDistributor.sendToPlayer(player, new Figures(watching.getKey(), figures));
            }
        }
        SHOWN_FIGURES.keySet().retainAll(WATCHES.keySet());
    }

    private static boolean loaded(ServerLevel level, Source source) {
        for (int chunkX = source.min().getX() >> 4; chunkX <= source.max().getX() >> 4; chunkX++) {
            for (int chunkZ = source.min().getZ() >> 4; chunkZ <= source.max().getZ() >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WATCHES.clear();
        ENTRIES.clear();
        SHOWN_FIGURES.clear();
    }
}
