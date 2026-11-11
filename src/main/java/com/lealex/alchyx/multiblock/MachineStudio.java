package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Machine Studio: a screen that shows a formed free-build machine in 3D (client/studio/StudioScreen), where its
 * player picks how each block moves and saves it all at once. The screen opens on the client when the Structure
 * Recorder is used on such a core with no corners marked; this side only takes the save.
 */
@EventBusSubscriber(modid = AlchyX.MODID)
public final class MachineStudio {
    private MachineStudio() {}

    /** How far from the core a save is still taken (squared distance, blocks). */
    private static final double REACH_SQUARED = 16 * 16;
    /** The most motions one save may carry (a 32 x 32 x 32 box is the hard limit of a free build). */
    private static final int MAX_MOTIONS = 32 * 32 * 32;

    /** Opens the studio for the core at this position (set by the client). */
    public static Consumer<BlockPos> clientOpen = pos -> {};

    /** Whether the studio can show this core: a formed free-build machine with its shape recorded. */
    public static boolean opens(MultiblockCoreBlockEntity core) {
        return core.freeBuild() != null && core.recordedShape() != null && core.isFormed();
    }

    /** The studio's save (client to server): every motion of the machine, by offset from the core. */
    public record Save(BlockPos core, Map<BlockPos, Motion> motions) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(AlchyX.id("studio_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> STREAM_CODEC = StreamCodec.of(
                (buf, save) -> {
                    BlockPos.STREAM_CODEC.encode(buf, save.core());
                    buf.writeVarInt(save.motions().size());
                    save.motions().forEach((offset, motion) -> {
                        BlockPos.STREAM_CODEC.encode(buf, offset);
                        buf.writeVarInt(motion.ordinal());
                    });
                },
                buf -> {
                    BlockPos core = BlockPos.STREAM_CODEC.decode(buf);
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_MOTIONS) throw new IllegalArgumentException("Too many motions: " + count);
                    Map<BlockPos, Motion> motions = new LinkedHashMap<>();
                    Motion[] all = Motion.values();
                    for (int i = 0; i < count; i++) {
                        BlockPos offset = BlockPos.STREAM_CODEC.decode(buf);
                        int index = buf.readVarInt();
                        if (index >= 0 && index < all.length) motions.put(offset, all[index]);
                    }
                    return new Save(core, motions);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Save.TYPE, Save.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) save(player, payload);
        });
    }

    private static void save(ServerPlayer player, Save save) {
        var level = player.level();
        BlockPos pos = save.core();
        if (!level.isLoaded(pos) || player.distanceToSqr(pos.getCenter()) > REACH_SQUARED) return;
        if (!(level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core) || !opens(core)) return;
        if (!level.mayInteract(player, pos)) {
            player.sendSystemMessage(Component.literal("You can't change this machine here"), true);
            return;
        }
        int kept = core.setMotions(save.motions());
        if (kept < 0) return;
        int dropped = save.motions().size() - kept;
        player.sendSystemMessage(Component.literal("Machine saved: " + kept + (kept == 1 ? " block moves" : " blocks move")
                + (dropped > 0 ? " (" + dropped + " left still: this machine doesn't allow them)" : "")), true);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.2f);
    }
}
