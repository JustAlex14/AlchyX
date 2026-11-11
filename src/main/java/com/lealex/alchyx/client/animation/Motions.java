package com.lealex.alchyx.client.animation;

import com.lealex.alchyx.animation.Effect;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.animation.ResolvedAnimation;
import com.lealex.alchyx.animation.StudioMark;
import com.lealex.alchyx.block.ChamberShellBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.FreeBuild;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The animation a free-build machine plays (client): its file's effects, measured in the box its player recorded
 * (written as it stands in the world, so no turning), plus a looping block move for each motion its player gave a
 * part, plus the particles and figures at the anchors its player put. Built again only when the file, the shape or the motions change, so the engine keeps its runtime meanwhile.
 */
final class Motions {
    private Motions() {}

    private record Cached(@Nullable ResolvedAnimation file, MultiblockPattern shape, int version, ResolvedAnimation resolved) {}

    private static final Map<MultiblockCoreBlockEntity, Cached> CACHE = new WeakHashMap<>();

    /** What this core plays: {@code file} for a machine with a fixed shape (or none recorded yet). */
    static @Nullable ResolvedAnimation resolve(MultiblockCoreBlockEntity core, @Nullable ResolvedAnimation file) {
        MultiblockPattern shape = core.recordedShape();
        if (shape == null) return file;
        Cached cached = CACHE.get(core);
        if (cached != null && cached.file() == file && cached.shape() == shape && cached.version() == core.motionsVersion()) {
            return cached.resolved();
        }
        List<Effect> effects = new ArrayList<>(file == null ? List.of() : file.effects());
        Map<String, BlockPos> anchors = new HashMap<>();
        FreeBuild rules = core.freeBuild();
        var when = rules == null ? com.lealex.alchyx.animation.Trigger.WORKING : rules.motionWhen();
        int n = 0;
        for (Map.Entry<BlockPos, Motion> entry : core.motions().entrySet()) {
            String anchor = "alchyx_motion_" + n++;
            BlockState look = core.getLevel() == null ? null
                    : ChamberShellBlock.lookAt(core.getLevel(), core.getBlockPos().offset(entry.getKey()));
            Effect effect = look == null ? null : entry.getValue().effect(anchor, when, look,
                    core.setups().getOrDefault(entry.getKey(), com.lealex.alchyx.animation.MotionSetup.ALWAYS));
            if (effect == null) continue;
            anchors.put(anchor, entry.getKey());
            effects.add(effect);
        }
        n = 0;
        for (StudioMark mark : core.marks()) {
            String anchor = "alchyx_mark_" + n++;
            String toAnchor = mark.to().isPresent() ? anchor + "_to" : null;
            Effect effect = mark.effect(anchor, toAnchor, when);
            if (effect == null) continue;
            anchors.put(anchor, mark.offset());
            if (toAnchor != null) anchors.put(toAnchor, mark.to().get());
            effects.add(effect);
        }
        ResolvedAnimation resolved = effects.isEmpty() ? null : new ResolvedAnimation(core.patternId(), Optional.empty(), anchors,
                shape.minOffset(), shape.maxOffset(), List.copyOf(effects));
        if (resolved == null) {
            CACHE.remove(core);
            return null;
        }
        CACHE.put(core, new Cached(file, shape, core.motionsVersion(), resolved));
        return resolved;
    }
}
