package com.lealex.alchyx.compat.patchouli;

import com.lealex.alchyx.AlchyX;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * "Unlock a chapter by reading another one", for any Patchouli book: when a player opens a book entry, the client
 * tells the server (BookReadingClient), and the server awards every advancement criterion named
 * {@code read:<entry id>} (e.g. {@code "read:mypack:basics/intro": {"trigger": "minecraft:impossible"}}) in every
 * advancement that has one. Give the locked entry {@code "advancement": "<that advancement>"} and Patchouli does
 * the rest. Plain server code, no Patchouli class here, so it loads with or without Patchouli.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class BookReading {
    public static final String PREFIX = "read:";

    private BookReading() {}

    public record Payload(Identifier entry) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(AlchyX.id("book_entry_read"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> STREAM_CODEC =
                StreamCodec.composite(Identifier.STREAM_CODEC, Payload::entry, Payload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToServer(Payload.TYPE, Payload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) markRead(player, payload.entry());
        });
    }

    /** Awards {@code read:<entry>} wherever an advancement asks for it. */
    public static void markRead(ServerPlayer player, Identifier entry) {
        String criterion = PREFIX + entry;
        for (AdvancementHolder holder : player.level().getServer().getAdvancements().getAllAdvancements()) {
            if (holder.value().criteria().containsKey(criterion)) player.getAdvancements().award(holder, criterion);
        }
    }
}
