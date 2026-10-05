package com.lealex.alchyx.machine;

import com.lealex.alchyx.AlchyX;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads {@code data/<namespace>/machine_recipe/*.json} (world load, {@code /reload}) and sends them to players
 * (for JEI). On the server the machines read {@link #forMachine}; on the client {@link #client()} holds what the
 * server sent.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class MachineRecipes extends SimpleJsonResourceReloadListener<MachineRecipe> {

    private static volatile Map<Identifier, MachineRecipe> recipes = Map.of();
    private static volatile Map<Identifier, List<Map.Entry<Identifier, MachineRecipe>>> byMachine = Map.of();
    private static volatile int generation = 0;
    private static volatile Map<Identifier, MachineRecipe> client = Map.of();

    private MachineRecipes() {
        super(MachineRecipe.CODEC, FileToIdConverter.json("machine_recipe"));
    }

    @Override
    protected void apply(Map<Identifier, MachineRecipe> loaded, ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, MachineRecipe> sorted = new TreeMap<>(loaded);
        Map<Identifier, List<Map.Entry<Identifier, MachineRecipe>>> grouped = new LinkedHashMap<>();
        for (Map.Entry<Identifier, MachineRecipe> entry : sorted.entrySet()) {
            grouped.computeIfAbsent(entry.getValue().machine(), id -> new ArrayList<>()).add(Map.entry(entry.getKey(), entry.getValue()));
        }
        recipes = Map.copyOf(sorted);
        byMachine = Map.copyOf(grouped);
        generation++;
        AlchyX.LOGGER.info("AlchyX: loaded {} machine recipe(s) for {} machine(s)", sorted.size(), grouped.size());
    }

    /** Server: the recipes of one machine (recipe type), by recipe id, in id order. */
    public static List<Map.Entry<Identifier, MachineRecipe>> forMachine(Identifier machine) {
        return byMachine.getOrDefault(machine, List.of());
    }

    /** Server: every loaded recipe, by recipe id. */
    public static Map<Identifier, MachineRecipe> all() {
        return recipes;
    }

    public static MachineRecipe get(Identifier id) {
        return recipes.get(id);
    }

    /** Bumps on every reload, so machines drop cached lookups. */
    public static int generation() {
        return generation;
    }

    /** Client: every recipe the server sent (recipe viewers). */
    public static Map<Identifier, MachineRecipe> client() {
        return client;
    }

    // ---- Loading and syncing ----

    public record Payload(Map<Identifier, MachineRecipe> recipes) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(AlchyX.id("machine_recipes"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistriesTrusted(com.mojang.serialization.Codec.unboundedMap(Identifier.CODEC, MachineRecipe.CODEC))
                        .map(Payload::new, Payload::recipes);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(AlchyX.id("machine_recipes"), new MachineRecipes());
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Payload.TYPE, Payload.STREAM_CODEC, (payload, context) -> client = Map.copyOf(payload.recipes()));
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Payload payload = new Payload(recipes);
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }
}
