package com.lealex.alchyx.multiblock;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.animation.MotionSetup;
import com.lealex.alchyx.animation.StudioMark;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Machine Studio: a screen that shows a formed machine (a free build, or one with a fixed shape) in 3D (client/studio/StudioScreen), where its
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
    /** The most blocks whose data one save changes. */
    private static final int MAX_DATA = 64;

    /** Opens the studio for the core at this position (set by the client). */
    public static Consumer<BlockPos> clientOpen = pos -> {};

    /**
     * Whether the studio can show this core: a formed free-build machine with its shape recorded, or a formed machine
     * with a fixed shape that allows it (see {@link MultiblockCoreBlockEntity#studioRules}).
     */
    public static boolean opens(MultiblockCoreBlockEntity core) {
        return core.studioOpens();
    }

    /** The studio's save (client to server): every motion of the machine (and when it plays), by offset from the core. */
    public record Save(BlockPos core, Map<BlockPos, Motion> motions, Map<BlockPos, MotionSetup> setups, List<StudioMark> marks,
                       Map<BlockPos, net.minecraft.nbt.CompoundTag> data, boolean hideFile,
                       Map<BlockPos, com.lealex.alchyx.animation.BlockPose> poses) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(AlchyX.id("studio_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> STREAM_CODEC = StreamCodec.of(
                (buf, save) -> {
                    BlockPos.STREAM_CODEC.encode(buf, save.core());
                    buf.writeVarInt(save.motions().size());
                    save.motions().forEach((offset, motion) -> {
                        BlockPos.STREAM_CODEC.encode(buf, offset);
                        buf.writeVarInt(motion.ordinal());
                        MotionSetup.STREAM_CODEC.encode(buf, save.setups().getOrDefault(offset, MotionSetup.ALWAYS));
                    });
                    buf.writeVarInt(save.marks().size());
                    for (StudioMark mark : save.marks()) StudioMark.STREAM_CODEC.encode(buf, mark);
                    buf.writeVarInt(save.data().size());
                    save.data().forEach((offset, tag) -> {
                        BlockPos.STREAM_CODEC.encode(buf, offset);
                        net.minecraft.network.codec.ByteBufCodecs.COMPOUND_TAG.encode(buf, tag);
                    });
                    buf.writeBoolean(save.hideFile());
                    buf.writeVarInt(save.poses().size());
                    save.poses().forEach((offset, pose) -> {
                        BlockPos.STREAM_CODEC.encode(buf, offset);
                        com.lealex.alchyx.animation.BlockPose.STREAM_CODEC.encode(buf, pose);
                    });
                },
                buf -> {
                    BlockPos core = BlockPos.STREAM_CODEC.decode(buf);
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_MOTIONS) throw new IllegalArgumentException("Too many motions: " + count);
                    Map<BlockPos, Motion> motions = new LinkedHashMap<>();
                    Map<BlockPos, MotionSetup> setups = new LinkedHashMap<>();
                    Motion[] all = Motion.values();
                    for (int i = 0; i < count; i++) {
                        BlockPos offset = BlockPos.STREAM_CODEC.decode(buf);
                        int index = buf.readVarInt();
                        MotionSetup setup = MotionSetup.STREAM_CODEC.decode(buf);
                        if (index < 0 || index >= all.length) continue;
                        motions.put(offset, all[index]);
                        if (!setup.equals(MotionSetup.ALWAYS)) setups.put(offset, setup);
                    }
                    int markCount = buf.readVarInt();
                    if (markCount < 0 || markCount > StudioMark.MAX * 4) throw new IllegalArgumentException("Too many anchors: " + markCount);
                    List<StudioMark> marks = new ArrayList<>();
                    for (int i = 0; i < markCount; i++) marks.add(StudioMark.STREAM_CODEC.decode(buf));
                    int dataCount = buf.readVarInt();
                    if (dataCount < 0 || dataCount > MAX_DATA) throw new IllegalArgumentException("Too much block data: " + dataCount);
                    Map<BlockPos, net.minecraft.nbt.CompoundTag> data = new LinkedHashMap<>();
                    for (int i = 0; i < dataCount; i++) {
                        BlockPos offset = BlockPos.STREAM_CODEC.decode(buf);
                        data.put(offset, net.minecraft.network.codec.ByteBufCodecs.COMPOUND_TAG.decode(buf));
                    }
                    boolean hideFile = buf.readBoolean();
                    int poseCount = buf.readVarInt();
                    if (poseCount < 0 || poseCount > MAX_MOTIONS) throw new IllegalArgumentException("Too many poses: " + poseCount);
                    Map<BlockPos, com.lealex.alchyx.animation.BlockPose> poses = new LinkedHashMap<>();
                    for (int i = 0; i < poseCount; i++) {
                        BlockPos offset = BlockPos.STREAM_CODEC.decode(buf);
                        poses.put(offset, com.lealex.alchyx.animation.BlockPose.STREAM_CODEC.decode(buf));
                    }
                    return new Save(core, motions, setups, marks, data, hideFile, poses);
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
        int kept = core.setStudio(save.motions(), save.setups(), save.marks(), save.hideFile(), save.poses());
        if (kept < 0) return;
        int dropped = save.motions().size() + save.marks().size() + save.poses().size() - kept;
        // Block data (a spawner's mob...): only on this machine's parts that have a block entity, kept by the shell
        // and only drawn: it never goes back into a real block, so it can't make anything that wasn't there
        for (Map.Entry<BlockPos, net.minecraft.nbt.CompoundTag> entry : save.data().entrySet()) {
            BlockPos at = pos.offset(entry.getKey());
            if (!core.structureContains(at) || !(level.getBlockEntity(at) instanceof com.lealex.alchyx.block.entity.ChamberShellBlockEntity shell)) {
                dropped++;
                continue;
            }
            net.minecraft.nbt.CompoundTag tag = entry.getValue();
            if (!shell.getDisguise().hasBlockEntity() || tag.sizeInBytes() > com.lealex.alchyx.block.ChamberShellBlock.MAX_DATA_BYTES) {
                dropped++;
                continue;
            }
            shell.setData(tag);
        }
        player.sendSystemMessage(Component.literal("Machine saved: " + core.motions().size() + " moving blocks, " + core.marks().size() + " anchors"
                + (dropped > 0 ? " (" + dropped + " left out: this machine doesn't allow them)" : "")), true);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.2f);
    }
}
