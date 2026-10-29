package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.AlchyX;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Loads every {@code data/<namespace>/multiblock/<name>.json} when the world loads and on {@code /reload}.
 *
 * A reload listener is Minecraft's hook for datapack content: "prepare" reads and parses the files off-thread,
 * then "apply" swaps the result in on the server thread. Broken files are logged and skipped.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class MultiblockPatterns extends SimpleJsonResourceReloadListener<MultiblockPattern> {

    private static volatile Map<Identifier, MultiblockPattern> patterns = Map.of();
    private static volatile Map<Identifier, MultiblockPattern> client = Map.of(); // what the server sent

    private MultiblockPatterns() {
        super(MultiblockPattern.CODEC, FileToIdConverter.json("multiblock"));
    }

    /** The loaded pattern with this id, or null if its file is missing or invalid (or on the client). */
    public static @Nullable MultiblockPattern get(Identifier id) {
        return patterns.get(id);
    }

    /** Every loaded pattern's id (server side). */
    public static java.util.Set<Identifier> ids() {
        return patterns.keySet();
    }

    /** The pattern whose core is this block, or null if the block isn't the core of any machine. */
    public static @Nullable MultiblockPattern forCore(Block block) {
        for (MultiblockPattern pattern : patterns.values()) {
            if (pattern.coreBlock() == block) return pattern;
        }
        return null;
    }

    /**
     * How many chunks away from a block a core can be and still have that block in its structure (any rotation),
     * for every loaded pattern: the core tracker looks that far around each block change.
     */
    public static int maxReachChunks() {
        return maxReachChunks;
    }

    private static volatile int maxReachChunks = 1;

    @Override
    protected void apply(Map<Identifier, MultiblockPattern> loaded, ResourceManager manager, ProfilerFiller profiler) {
        patterns = Map.copyOf(loaded);
        int reach = 0;
        for (MultiblockPattern pattern : loaded.values()) {
            BlockPos min = pattern.minOffset(), max = pattern.maxOffset();
            reach = Math.max(reach, Math.max(Math.max(-min.getX(), max.getX()), Math.max(-min.getZ(), max.getZ())));
        }
        maxReachChunks = (reach + 15) >> 4;
        AlchyX.LOGGER.info("AlchyX: loaded {} multiblock pattern(s): {}", loaded.size(), loaded.keySet());
    }

    /** Client: the patterns the server sent (guide book previews). */
    public static Map<Identifier, MultiblockPattern> client() {
        return client;
    }

    // ---- Sending the patterns to players (join and /reload), for guide book previews ----

    public record Payload(Map<Identifier, MultiblockPattern> patterns) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(AlchyX.id("multiblock_patterns"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistriesTrusted(com.mojang.serialization.Codec.unboundedMap(Identifier.CODEC, MultiblockPattern.CODEC))
                        .map(Payload::new, Payload::patterns);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Payload.TYPE, Payload.STREAM_CODEC, (payload, context) -> {
            client = Map.copyOf(payload.patterns());
            // Patchouli (optional): every pattern becomes a multiblock usable as "multiblock_id" in book pages
            if (ModList.get().isLoaded("patchouli")) com.lealex.alchyx.compat.patchouli.PatchouliMultiblocks.register(client);
        });
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Payload payload = new Payload(patterns);
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(AlchyX.id("multiblock_patterns"), new MultiblockPatterns());
    }
}
