package com.lealex.alchyx.compat.patchouli;

import com.lealex.alchyx.multiblock.MultiblockPattern;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import vazkii.patchouli.api.IMultiblock;
import vazkii.patchouli.api.IStateMatcher;
import vazkii.patchouli.api.PatchouliAPI;
import vazkii.patchouli.client.book.ClientBookRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Patchouli integration (client, only called when Patchouli is loaded): every multiblock pattern the server sent
 * is registered as a Patchouli multiblock under the pattern's id, so a book page can show it with
 * {@code "type": "patchouli:multiblock", "multiblock_id": "mypack:crusher"}: 3D preview and Visualize always
 * match the real structure (also after /reload and datapack overrides). Blocks shown = what the Structure Builder
 * places; Visualize accepts whatever the structure check accepts.
 */
public final class PatchouliMultiblocks {
    private PatchouliMultiblocks() {}

    public static void register(Map<Identifier, MultiblockPattern> patterns) {
        PatchouliAPI.IPatchouliAPI api = PatchouliAPI.get();
        for (Map.Entry<Identifier, MultiblockPattern> entry : patterns.entrySet()) {
            Map<BlockPos, IStateMatcher> positions = new HashMap<>();
            for (MultiblockPattern.PreviewCell cell : entry.getValue().previewCells()) {
                positions.put(cell.offset(), cell.display() == null ? api.airMatcher()
                        : api.predicateMatcher(cell.display(), cell.accepts()));
            }
            IMultiblock multiblock = api.makeSparseMultiblock(positions);
            multiblock.setId(entry.getKey());
            // Offsets stay core-relative; the view (book preview + Visualize) is centred horizontally with the
            // bottom layer resting on the targeted block, instead of spinning around the core
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : positions.keySet()) {
                minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            }
            if (!positions.isEmpty()) multiblock.offsetView(Math.floorDiv(minX + maxX, 2), minY, Math.floorDiv(minZ + maxZ, 2));
            try {
                api.registerMultiblock(entry.getKey(), multiblock);
            } catch (IllegalArgumentException alreadyRegistered) {
                // A /reload: Patchouli stores the new multiblock before complaining that the id exists, so it's updated
            }
        }
        // Fit-to-size previews for every multiblock page (the 26.1 Patchouli port draws them at a fixed zoom)
        ClientBookRegistry.INSTANCE.pageTypes.put(Identifier.fromNamespaceAndPath("patchouli", "multiblock"),
                FittedMultiblockPage.class);
        // Rebuild the books so their pages pick up the (new) multiblocks
        if (Minecraft.getInstance().level != null) ClientBookRegistry.INSTANCE.reload();
    }
}
