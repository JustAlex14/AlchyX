package com.lealex.alchyx.growth;

import com.lealex.alchyx.AlchyX;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/**
 * A growing machine's parts, for the players watching (client/growth/GrowthParts draws them; block/entity/
 * TieredCoreBlockEntity sends them): EMERGE, a grown part flies from `source` on an arc down to `pos` (its shell is
 * veiled: hidden on the clients until it lands); SINK, a cleared part flies up into `source` and is gone; RISE, a part
 * slides up out of the ground into `pos` by `distance` blocks (veiled too, shown once it stops). `style` picks the
 * mod's particles (GrowthParts.registerStyle).
 */
public record GrowthPayload(int kind, Identifier style, BlockPos source, BlockPos pos, BlockState look, int distance) implements CustomPacketPayload {
    public static final int EMERGE = 0, SINK = 1, RISE = 2;
    /** How long a part flies from its source to its place, and up into it. */
    public static final int FLY_TICKS = 20, SINK_TICKS = 18;

    /** How long a RISE part takes to come up `blocks` blocks: 60 ticks, 30 more a block. */
    public static int riseTicks(int blocks) {
        return 60 + (Math.max(1, blocks) - 1) * 30;
    }

    public static final Type<GrowthPayload> TYPE = new Type<>(AlchyX.id("growth_part"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GrowthPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GrowthPayload::kind,
            Identifier.STREAM_CODEC, GrowthPayload::style,
            BlockPos.STREAM_CODEC, GrowthPayload::source,
            BlockPos.STREAM_CODEC, GrowthPayload::pos,
            ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), GrowthPayload::look,
            ByteBufCodecs.VAR_INT, GrowthPayload::distance,
            GrowthPayload::new);

    /** Set by AlchyX's client: receives them. */
    public static Consumer<GrowthPayload> clientReceive = payload -> {};

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** To every player that has the part's chunk loaded. */
    public static void send(ServerLevel level, int kind, Identifier style, BlockPos source, BlockPos pos, BlockState look, int distance) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4),
                new GrowthPayload(kind, style, source.immutable(), pos.immutable(), look, distance));
    }

    @EventBusSubscriber(modid = AlchyX.MODID)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToClient(TYPE, STREAM_CODEC, (payload, context) -> clientReceive.accept(payload));
        }
    }
}
