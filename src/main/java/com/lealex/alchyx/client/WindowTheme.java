package com.lealex.alchyx.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * How a machine's screens look when they are laid out by code and not painted whole: a window of the machine's own
 * MATERIAL (a sheet of pixel art: planks, bricks, tiles, stone), a BORDER BAND of a second material with a dark line
 * each side of it and a spark of the accent in each corner, and everything the screen holds SUNK into it: dark wells
 * with a thin line of the accent round them ({@link #well}). Because the window is laid from sheets, one pair of
 * sheets serves screens of any size.
 *
 * Each mod brings its own sheets and colours (its own theme): this only lays them. A sheet is 256 x 512; the window
 * takes its top left part, the band four strips from where they lie, so its pattern runs on round the corners.
 *
 * @param frame  the darkest: lines, wells
 * @param panel  the window's own tone (for flat colours that sit on it)
 * @param line   darker than the panel
 * @param text   words
 * @param faint  lesser words
 * @param warn   what is wrong
 * @param accent the machine's own colour: lines round the wells, the title, what is on
 * @param panelSheet the window's material
 * @param bandSheet  the border's
 */
public record WindowTheme(int frame, int panel, int line, int text, int faint, int warn, int accent, Identifier panelSheet, Identifier bandSheet) {
    private static final int BAND = 5, SHEET_WIDE = 256, SHEET_HIGH = 512;

    /** The accent at this share of its strength (for the thin lines round a well). */
    private int dim(int alpha) {
        return (accent & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * A screen's back: the window of its material, the border band with its lines and corner sparks, a sunk header
     * down to headerBottom with this picture at its left if it is not empty (a title is then written from x 24).
     */
    public void back(GuiGraphicsExtractor graphics, int x, int y, int wide, int high, int headerBottom, ItemStack icon) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, panelSheet, x, y, 0.0F, 0.0F, wide, high, SHEET_WIDE, SHEET_HIGH);
        // The band: four strips of the second material, each taken from where it lies so its pattern runs on
        graphics.blit(RenderPipelines.GUI_TEXTURED, bandSheet, x, y, 0.0F, 0.0F, wide, BAND, SHEET_WIDE, SHEET_HIGH);
        graphics.blit(RenderPipelines.GUI_TEXTURED, bandSheet, x, y + high - BAND, 0.0F, high - BAND, wide, BAND, SHEET_WIDE, SHEET_HIGH);
        graphics.blit(RenderPipelines.GUI_TEXTURED, bandSheet, x, y + BAND, 0.0F, BAND, BAND, high - 2 * BAND, SHEET_WIDE, SHEET_HIGH);
        graphics.blit(RenderPipelines.GUI_TEXTURED, bandSheet, x + wide - BAND, y + BAND, wide - BAND, BAND, BAND, high - 2 * BAND, SHEET_WIDE, SHEET_HIGH);
        outline(graphics, x, y, x + wide, y + high, frame);                                     // round the window
        outline(graphics, x + BAND - 1, y + BAND - 1, x + wide - BAND + 1, y + high - BAND + 1, frame);  // inside the band
        for (int cornerX : new int[] {x + BAND, x + wide - BAND - 1}) {                         // a spark in each corner
            for (int cornerY : new int[] {y + BAND, y + high - BAND - 1}) {
                graphics.fill(cornerX, cornerY, cornerX + 1, cornerY + 1, accent);
                graphics.fill(cornerX - 1, cornerY, cornerX + 2, cornerY + 1, dim(0x70));
                graphics.fill(cornerX, cornerY - 1, cornerX + 1, cornerY + 2, dim(0x70));
            }
        }
        well(graphics, x + BAND + 1, y + BAND, x + wide - BAND - 1, y + headerBottom);
        if (!icon.isEmpty()) graphics.item(icon, x + BAND + 1, y + Math.max(0, (headerBottom + BAND - 16) / 2));
    }

    /** Something sunk into the window: a dark well with a thin line of the accent round it (a row of a list, a header, a frame). */
    public void well(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, bottom, (frame & 0x00FFFFFF) | 0xEC000000);
        outline(graphics, left, top, right, bottom, dim(0x8C));
    }

    private static void outline(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top + 1, left + 1, bottom - 1, color);
        graphics.fill(right - 1, top + 1, right, bottom - 1, color);
    }
}
