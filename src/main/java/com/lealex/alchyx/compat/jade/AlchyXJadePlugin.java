package com.lealex.alchyx.compat.jade;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;

/**
 * Jade integration (only loaded when Jade is installed: Jade finds this class by its @WailaPlugin annotation, so
 * nothing else in the mod refers to it and the mod still runs without Jade).
 *
 * Names the parts of every formed machine after their machine ("Rift Gate", "Destructuration Chamber") instead of
 * the generic "Machine Shell", for every mod built on AlchyX.
 */
@WailaPlugin
public class AlchyXJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(MachineNameProvider.INSTANCE, ChamberShellBlock.class);
    }

    /**
     * Client: replaces a shell part's name with its machine's: lang key multiblock.<namespace>.<pattern>, or the
     * pattern's file name ("rendering_cauldron" -> "Rendering Cauldron"), so KubeJS machines get a name too.
     * Finds the machine among the cores loaded on the client (formed is synced with them).
     */
    public enum MachineNameProvider implements IBlockComponentProvider {
        INSTANCE;

        private static final Identifier UID = AlchyX.id("machine_name");
        private static final double SEARCH = 16 * 16; // squared: the biggest machines are 7 wide

        @Override
        public Identifier getUid() {
            return UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BlockPos pos = accessor.getPosition();
            for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
                if (core.getLevel() != accessor.getLevel() || core.isRemoved() || !core.isFormed()) continue;
                if (core.getBlockPos().distSqr(pos) > SEARCH || !core.structureContains(pos)) continue;
                tooltip.replace(JadeIds.CORE_OBJECT_NAME, IThemeHelper.get().title(machineName(core.patternId())));
                return;
            }
        }

        public static Component machineName(Identifier pattern) {
            String[] words = pattern.getPath().split("_");
            StringBuilder fallback = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                if (word.isEmpty() || (i == words.length - 1 && i > 0 && word.chars().allMatch(Character::isDigit))) continue; // a tier number is not part of the name
                if (!fallback.isEmpty()) fallback.append(' ');
                fallback.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return Component.translatableWithFallback("multiblock." + pattern.getNamespace() + "." + pattern.getPath(), fallback.toString());
        }
    }
}
