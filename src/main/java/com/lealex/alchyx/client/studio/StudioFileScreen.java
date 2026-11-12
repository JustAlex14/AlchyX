package com.lealex.alchyx.client.studio;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Machine Studio's Export and Import window, over the studio. Export: a name for the file (it goes in the game
 * folder's alchyx_exports/&lt;mod&gt;/machine_animation/), then Export writes it. Import: every file in alchyx_exports,
 * this machine's first, to pick one (a double click or Import brings it in). Both have Open folder, to copy files
 * in or out (a datapack, a friend's file).
 */
public class StudioFileScreen extends Screen {
    private static final int BACK = 0xE0101018, PANEL = 0xFF1A1A26, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0, FAINT = 0xFF9A93AA,
            WARN = 0xFFFF7A7A;
    private static final int WIDE = 320, LINE = 12, TOP = 64;

    private final Screen parent;
    private final boolean exporting;
    private final Path root, folder;
    private final String machineName;
    private final Consumer<Path> done;
    private final List<Path> files = new ArrayList<>();
    private @Nullable Path chosen;
    private @Nullable EditBox nameBox;
    private String name;
    private @Nullable String problem;
    private int scrolled;

    /**
     * @param root     the exports folder (alchyx_exports)
     * @param folder   where this machine's files go (alchyx_exports/&lt;mod&gt;/machine_animation)
     * @param suggested the file name Export starts with (no ".json")
     * @param done     gets the file to write (Export) or read (Import)
     */
    public StudioFileScreen(Screen parent, boolean exporting, Path root, Path folder, String machineName, String suggested, Consumer<Path> done) {
        super(Component.literal(exporting ? "Export" : "Import"));
        this.parent = parent;
        this.exporting = exporting;
        this.root = root;
        this.folder = folder;
        this.machineName = machineName;
        this.name = suggested;
        this.done = done;
        if (!exporting) findFiles();
    }

    private void findFiles() {
        files.clear();
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root, 6)) {
            walk.filter(path -> Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            problem = "Can't read the folder: " + e.getMessage();
        }
        // This machine's files first, then the others, each by name
        files.sort((a, b) -> {
            boolean ownA = a.startsWith(folder), ownB = b.startsWith(folder);
            if (ownA != ownB) return ownA ? -1 : 1;
            return a.toString().compareToIgnoreCase(b.toString());
        });
        if (chosen == null && !files.isEmpty() && files.getFirst().startsWith(folder)) chosen = files.getFirst();
    }

    private int left() { return (width - WIDE) / 2; }
    private int lines() { return Math.max(1, (height - TOP - 40) / LINE); }

    @Override
    protected void init() {
        int left = left(), bottom = height - 26;
        if (exporting) {
            nameBox = addRenderableWidget(new EditBox(font, left, 44, WIDE - 40, 18, Component.literal("File name")));
            nameBox.setMaxLength(64);
            nameBox.setValue(name);
            nameBox.setResponder(text -> {
                name = text;
                problem = null;
            });
            setInitialFocus(nameBox);
        }
        Button act = addRenderableWidget(Button.builder(Component.literal(exporting ? "Export" : "Import"), b -> finish())
                .bounds(width / 2 - 154, bottom, 100, 20).build());
        act.active = exporting || chosen != null;
        Button open = addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> openFolder())
                .bounds(width / 2 - 50, bottom, 100, 20).build());
        open.setTooltip(Tooltip.create(Component.literal(exporting
                ? "The folder the file goes in: copy it from there into a datapack (data/<mod>/machine_animation/)"
                : "Put a file here (exported on another world, or by a friend) to import it")));
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 54, bottom, 100, 20).build());
    }

    private void openFolder() {
        Path where = exporting || Files.isDirectory(folder) ? folder : root;
        try {
            Files.createDirectories(where);
            Util.getPlatform().openPath(where);
        } catch (Exception e) {
            problem = "Can't open the folder: " + e.getMessage();
        }
    }

    /** The name typed, made a file name: letters, digits, "_", "-" and "." (no folders). */
    private @Nullable String cleanName() {
        String clean = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (clean.endsWith(".json")) clean = clean.substring(0, clean.length() - 5);
        if (clean.isEmpty() || !clean.matches("[a-z0-9_.\\-]+") || clean.startsWith(".")) return null;
        return clean;
    }

    private void finish() {
        if (exporting) {
            String clean = cleanName();
            if (clean == null) {
                problem = "A name with only a-z, 0-9, _ - and . (it's also the file's id in a datapack)";
                return;
            }
            minecraft.setScreen(parent);
            done.accept(folder.resolve(clean + ".json"));
            return;
        }
        if (chosen == null) return;
        minecraft.setScreen(parent);
        done.accept(chosen);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int lineUnder(double x, double y) {
        if (exporting || x < left() || x > left() + WIDE || y < TOP) return -1;
        int line = (int) ((y - TOP) / LINE);
        if (line >= lines()) return -1;
        int index = scrolled + line;
        return index < files.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = lineUnder(event.x(), event.y());
        if (index >= 0 && event.button() == 0) {
            chosen = files.get(index);
            if (doubleClick) finish();
            else rebuildWidgets();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrolled = Mth.clamp(scrolled - (int) Math.signum(scrollY) * 3, 0, Math.max(0, files.size() - lines()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) { // enter
            finish();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    private String shown(Path path) {
        String text = root.relativize(path).toString().replace('\\', '/');
        while (font.width(text) > WIDE - 6 && text.length() > 1) text = text.substring(1);
        return text;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int left = left();
        graphics.text(font, (exporting ? "Export " : "Import into ") + machineName, left, 8, ACCENT, false);
        if (exporting) {
            graphics.text(font, "Writes what you made here as an animation file, for a datapack:", left, 20, FAINT, false);
            graphics.text(font, "every machine of this kind then plays it. File name:", left, 31, FAINT, false);
            graphics.text(font, ".json", left + WIDE - 34, 49, FAINT, false);
            String clean = cleanName();
            String where = clean == null ? "" : "alchyx_exports/" + root.relativize(folder.resolve(clean + ".json")).toString().replace('\\', '/');
            while (font.width(where) > WIDE && where.length() > 1) where = where.substring(1);
            graphics.text(font, where, left, 68, TEXT, false);
            if (clean != null && java.nio.file.Files.exists(folder.resolve(clean + ".json"))) {
                graphics.text(font, "A file of that name is there: Export replaces it", left, 80, WARN, false);
            }
        } else {
            graphics.text(font, "Pick a file Export made (this machine's come first). It replaces what's in", left, 20, FAINT, false);
            graphics.text(font, "the studio now; Save keeps it on the machine.", left, 31, FAINT, false);
            graphics.fill(left - 2, TOP - 2, left + WIDE + 2, TOP + lines() * LINE + 2, PANEL);
            int under = lineUnder(mouseX, mouseY);
            for (int line = 0; line < lines() && scrolled + line < files.size(); line++) {
                int index = scrolled + line, y = TOP + line * LINE;
                Path file = files.get(index);
                boolean on = file.equals(chosen);
                if (on || index == under) graphics.fill(left - 2, y - 1, left + WIDE + 2, y + LINE - 1, on ? 0xFF3C3550 : 0xFF26263A);
                graphics.text(font, shown(file), left + 2, y + 1, on ? ACCENT : file.startsWith(folder) ? TEXT : FAINT, false);
            }
            if (files.isEmpty()) graphics.text(font, "No files yet: Export makes one, or Open folder to put one there", left + 2, TOP + 2, FAINT, false);
        }
        if (problem != null) {
            String shown = problem;
            while (font.width(shown) > WIDE && shown.length() > 1) shown = shown.substring(0, shown.length() - 1);
            graphics.text(font, shown, left, height - 38, WARN, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}
