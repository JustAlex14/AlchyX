package com.lealex.alchyx.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Words that must stay inside a screen: every line a screen or a JEI page writes goes through here, measured with the
 * game's own font, so a long name, another language or a pack's config can't push text out of its panel.
 */
public final class Lines {
    private Lines() {}

    /** The text cut into lines no wider than this, at its spaces (a single word too wide is clipped). */
    public static List<String> wrap(Font font, String text, int width) {
        List<String> lines = new ArrayList<>(2);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String longer = line.isEmpty() ? word : line + " " + word;
            if (font.width(longer) <= width) {
                line.setLength(0);
                line.append(longer);
                continue;
            }
            if (!line.isEmpty()) lines.add(line.toString());
            line.setLength(0);
            line.append(clip(font, word, width));
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** The text if it fits in this width, else its beginning and "...". */
    public static String clip(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + "...") > width) end--;
        return text.substring(0, end).stripTrailing() + "...";
    }
}
