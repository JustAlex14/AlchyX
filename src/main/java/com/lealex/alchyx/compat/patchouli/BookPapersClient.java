package com.lealex.alchyx.compat.patchouli;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.client.BookPapers;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import vazkii.patchouli.client.book.BookCategory;
import vazkii.patchouli.client.book.gui.GuiBook;
import vazkii.patchouli.client.book.gui.GuiBookCategory;
import vazkii.patchouli.client.book.gui.GuiBookEntry;
import vazkii.patchouli.client.book.gui.GuiBookLanding;
import vazkii.patchouli.common.book.Book;

/**
 * Client half of {@link BookPapers}: while a registered book is open, puts the sheet of the chapter being read under
 * the id Patchouli draws the book from, fading from the one before; swaps the writing's colours as a chapter opens;
 * lets the book's painter draw over the sheet. Registered from AlchyXClient only when Patchouli is loaded, since this
 * class touches Patchouli's screens. A book whose sheets can't be read, or whose screen is not as expected, is left
 * on the paper it has (logged once).
 */
public final class BookPapersClient {
    private BookPapersClient() {}

    private static final float FADE_PER_TICK = 0.1F;

    /** A book being shown: its texture, its sheets' pixels, where the paper is between sheets and what was last painted. */
    private static final class Open {
        DynamicTexture texture;
        int width, height;
        int[][] sheets;
        /** The marks' pixels, each MARK_WIDTH x MARK_HEIGHT, and which one the left and the right page carry now (-1: none). */
        int[][] marks;
        int leftMark = -1, rightMark = -1;
        float shown, painted = -1;
        int ink = -1, ticks;
        boolean repaint;
    }

    /** The open book on its sheet: what a mark picture covers. */
    private static final int MARK_WIDTH = 272, MARK_HEIGHT = 180;

    private static final Map<Identifier, Open> OPEN = new HashMap<>();
    private static final Map<Identifier, Boolean> FAILED = new HashMap<>();
    private static Field categoryField;

    /**
     * A page of a registered book is about to be laid out: its chapter's ink goes into the book first. (Patchouli
     * reads the colours while it lays a page out, so a tick later would be a page too late.)
     */
    public static void onScreenInit(ScreenEvent.Init.Pre event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof GuiBook gui) || gui.book == null) return;
        BookPapers.Papers papers = BookPapers.of(gui.book.id);
        if (papers == null || papers.inks() == null || FAILED.containsKey(papers.book())) return;
        try {
            int chapter = chapterOf(gui, papers);
            Open open = OPEN.get(papers.book());
            // What the paper shows, when this screen says nothing of its own (search, bookmarks...)
            if (chapter < 0) chapter = open == null ? 0 : Math.round(open.shown);
            if (open != null && open.ink == chapter) return;
            BookPapers.Ink ink = papers.inks().get(chapter);
            set(gui.book, "textColor", ink.text());
            set(gui.book, "headerColor", ink.header());
            set(gui.book, "nameplateColor", ink.nameplate());
            set(gui.book, "linkColor", ink.link());
            set(gui.book, "linkHoverColor", ink.linkHover());
            if (open != null) open.ink = chapter;
        } catch (Exception e) {
            FAILED.put(papers.book(), true);
            AlchyX.LOGGER.warn("The paper of the book {} will not change with its chapters", papers.book(), e);
        }
    }

    /** Patchouli keeps a book's colours in final fields, read once from book.json. */
    private static void set(Book book, String field, int rgb) throws ReflectiveOperationException {
        Field color = Book.class.getField(field);
        color.setAccessible(true);
        color.setInt(book, rgb);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof GuiBook gui) || gui.book == null) return;
        BookPapers.Papers papers = BookPapers.of(gui.book.id);
        if (papers == null || FAILED.containsKey(papers.book())) return;
        try {
            Open open = OPEN.get(papers.book());
            if (open == null) {
                open = load(minecraft, papers);
                OPEN.put(papers.book(), open);
            }
            int wanted = chapterOf(gui, papers);
            if (wanted >= 0) {
                open.shown = open.shown < wanted ? Math.min(wanted, open.shown + FADE_PER_TICK) : Math.max(wanted, open.shown - FADE_PER_TICK);
            }
            if (open.marks != null) {
                // Which mark each of the two pages in view carries: always the same for the same page
                int page = pageKey(gui);
                int left = page == 0 ? -1 : markOf(papers.marks(), open.marks.length, page * 2);
                int right = page == 0 ? -1 : markOf(papers.marks(), open.marks.length, page * 2 + 1);
                if (left != open.leftMark || right != open.rightMark) {
                    open.leftMark = left;
                    open.rightMark = right;
                    open.repaint = true;
                }
            }
            if (open.shown != open.painted || open.repaint || papers.painter() != null) {
                open.repaint = false;
                paint(open);
                if (papers.painter() != null) papers.painter().paint(open.texture.getPixels(), open.shown, ++open.ticks);
                open.texture.upload();
                open.painted = open.shown;
            }
        } catch (Exception e) {
            FAILED.put(papers.book(), true);
            AlchyX.LOGGER.warn("The paper of the book {} will not change with its chapters", papers.book(), e);
        }
    }

    /** A number for the pair of pages in view (an entry's id and how far into it), 0 where pages carry no mark (front page, lists). */
    private static int pageKey(GuiBook gui) {
        if (!(gui instanceof GuiBookEntry entry) || entry.getEntry() == null) return 0;
        int key = entry.getEntry().getId().hashCode() * 31 + gui.getSpread();
        return key == 0 ? 1 : key;
    }

    /** The mark a page carries, or -1 for a bare page. */
    private static int markOf(BookPapers.Marks marks, int count, int page) {
        int n = page * 0x9E3779B9;
        n = (n ^ (n >>> 15)) * 0x85EBCA6B;
        n ^= n >>> 13;
        float roll = (n & 0xFFFF) / 65536.0F;
        if (roll < marks.bare()) return -1;
        return Math.min(count - 1, (int) ((roll - marks.bare()) / Math.max(1.0E-4F, 1 - marks.bare()) * count));
    }

    /** The chapter of what the screen shows, or -1 to keep the paper as it is. */
    private static int chapterOf(GuiBook gui, BookPapers.Papers papers) throws ReflectiveOperationException {
        BookCategory category = null;
        if (gui instanceof GuiBookEntry entry) {
            if (entry.getEntry() != null) category = entry.getEntry().getCategory();
        } else if (gui instanceof GuiBookCategory) {
            if (categoryField == null) { // private in Patchouli
                categoryField = GuiBookCategory.class.getDeclaredField("category");
                categoryField.setAccessible(true);
            }
            category = (BookCategory) categoryField.get(gui);
        } else if (gui instanceof GuiBookLanding) {
            return 0;
        }
        // A sub-category takes the paper of the chapter it belongs to
        while (category != null && papers.chapters().indexOf(category.getId().getPath()) < 0) category = category.getParentCategory();
        return category == null ? -1 : papers.chapters().indexOf(category.getId().getPath());
    }

    private static Open load(Minecraft minecraft, BookPapers.Papers papers) throws Exception {
        Open open = new Open();
        NativeImage first = null;
        for (int chapter = 0; chapter < papers.sheets().size(); chapter++) {
            NativeImage image;
            try (InputStream in = minecraft.getResourceManager().open(papers.sheets().get(chapter))) {
                image = NativeImage.read(in);
            }
            if (chapter == 0) {
                open.width = image.getWidth();
                open.height = image.getHeight();
                open.sheets = new int[papers.sheets().size()][open.width * open.height];
            } else if (image.getWidth() != open.width || image.getHeight() != open.height) {
                image.close();
                throw new IllegalStateException(papers.sheets().get(chapter) + " is not the size of the book's first sheet");
            }
            for (int y = 0; y < open.height; y++) {
                for (int x = 0; x < open.width; x++) open.sheets[chapter][y * open.width + x] = image.getPixel(x, y);
            }
            if (chapter == 0) first = image;
            else image.close();
        }
        if (papers.marks() != null) {
            if (open.width < MARK_WIDTH || open.height < MARK_HEIGHT) throw new IllegalStateException(papers.texture() + " is smaller than an open book");
            open.marks = new int[papers.marks().pictures().size()][MARK_WIDTH * MARK_HEIGHT];
            for (int mark = 0; mark < open.marks.length; mark++) {
                try (InputStream in = minecraft.getResourceManager().open(papers.marks().pictures().get(mark)); NativeImage image = NativeImage.read(in)) {
                    for (int y = 0; y < Math.min(MARK_HEIGHT, image.getHeight()); y++) {
                        for (int x = 0; x < Math.min(MARK_WIDTH, image.getWidth()); x++) open.marks[mark][y * MARK_WIDTH + x] = image.getPixel(x, y);
                    }
                }
            }
        }
        open.texture = new DynamicTexture(() -> papers.texture().toString(), first);
        minecraft.getTextureManager().register(papers.texture(), open.texture);
        return open;
    }

    private static void paint(Open open) {
        NativeImage image = open.texture.getPixels();
        if (image == null) return;
        int from = Mth.clamp((int) open.shown, 0, open.sheets.length - 1), to = Math.min(open.sheets.length - 1, from + 1);
        float blend = Mth.clamp(open.shown - from, 0.0F, 1.0F);
        int[] a = open.sheets[from], b = open.sheets[to];
        for (int y = 0; y < open.height; y++) {
            for (int x = 0; x < open.width; x++) {
                int i = y * open.width + x;
                image.setPixel(x, y, blend <= 0 || a[i] == b[i] ? a[i] : ARGB.srgbLerp(blend, a[i], b[i]));
            }
        }
        if (open.marks == null) return;
        // The marks of the two pages in view, each over its own half of the book
        for (int side = 0; side < 2; side++) {
            int mark = side == 0 ? open.leftMark : open.rightMark;
            if (mark < 0) continue;
            int[] pixels = open.marks[mark];
            for (int y = 0; y < MARK_HEIGHT; y++) {
                for (int x = side * MARK_WIDTH / 2; x < (side + 1) * MARK_WIDTH / 2; x++) {
                    int over = pixels[y * MARK_WIDTH + x];
                    int alpha = ARGB.alpha(over);
                    if (alpha == 0) continue;
                    int under = image.getPixel(x, y);
                    image.setPixel(x, y, alpha == 255 ? over : ARGB.srgbLerp(alpha / 255.0F, under, ARGB.opaque(over)));
                }
            }
        }
    }
}
