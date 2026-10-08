package com.lealex.alchyx.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A guide book (Patchouli) whose paper changes with the chapter being read: old paper at the start, something else
 * by the end. A mod registers its book once, from its client entry point:
 * <pre>
 * BookPapers.register(bookId, List.of("leaving", "door", "eye"), List.of(sheet0, sheet1, sheet2));
 * </pre>
 * {@code chapters} are the paths of the book's categories, in order; {@code sheets} are whole book sheets (Patchouli's
 * layout, all the same size), one per chapter. The first sheet must be the texture the book's book.json names as
 * {@code book_texture}: the others are painted into it while the book is open, fading from one to the next in about
 * half a second. The book's front page shows the first sheet.
 * <p>
 * Optional extras:
 * <ul>
 *   <li>{@link Marks}: what was left on some pages and not on others (dust, a stain, a splash). Each is a picture
 *       the size of the open book (272 x 180), see-through except for the mark, with something on its left page
 *       and something on its right. Every page of the book gets one of them, or nothing, decided by which page it
 *       is: the same page always carries the same mark, and a share of the pages stay bare.</li>
 *   <li>{@link Ink}: the colours of the writing, one per chapter (Patchouli has one set per book; here it is swapped
 *       as a chapter opens). Colours written into the text itself (macros, $(#rrggbb)) are not touched: pick ones
 *       that read on every sheet.</li>
 *   <li>{@link Painter}: called every tick with the sheet as it is now, to draw on top of it (drifting clouds,
 *       blinking stars). With one, the sheet is repainted every tick.</li>
 * </ul>
 * Nothing here touches Patchouli, so mods call it whether Patchouli is installed or not; the work is done by
 * compat/patchouli/BookPapersClient, which only exists in the game when Patchouli does.
 */
public final class BookPapers {
    private BookPapers() {}

    /** The colours of a chapter's writing, as rgb: what book.json calls text_color, header_color, nameplate_color, link_color, link_hover_color. */
    public record Ink(int text, int header, int nameplate, int link, int linkHover) {}

    /** Draws over the sheet, every tick. {@code chapter} is where the paper is between sheets (2.4: four tenths of the way from the third to the fourth). */
    @FunctionalInterface
    public interface Painter {
        void paint(NativeImage sheet, float chapter, int tick);
    }

    /**
     * What some pages carry.
     *
     * @param pictures the marks, each a picture of the open book (272 x 180) with a mark for a left page and one for a right page
     * @param bare     the share of pages that carry nothing, 0 to 1
     */
    public record Marks(List<Identifier> pictures, float bare) {}

    /** One registered book: its id, its categories' paths in order, a sheet for each, and maybe inks, a painter, marks. */
    public record Papers(Identifier book, List<String> chapters, List<Identifier> sheets, @Nullable List<Ink> inks, @Nullable Painter painter,
                         @Nullable Marks marks) {
        /** The texture the book draws from, repainted as chapters change. */
        public Identifier texture() {
            return sheets.getFirst();
        }
    }

    private static final List<Papers> REGISTERED = new ArrayList<>();

    public static void register(Identifier book, List<String> chapters, List<Identifier> sheets) {
        register(book, chapters, sheets, null, null);
    }

    public static void register(Identifier book, List<String> chapters, List<Identifier> sheets, @Nullable List<Ink> inks,
                                @Nullable Painter painter) {
        register(book, chapters, sheets, inks, painter, null);
    }

    public static synchronized void register(Identifier book, List<String> chapters, List<Identifier> sheets, @Nullable List<Ink> inks,
                                             @Nullable Painter painter, @Nullable Marks marks) {
        if (chapters.isEmpty() || chapters.size() != sheets.size()) throw new IllegalArgumentException("One sheet per chapter, at least one: " + book);
        if (inks != null && inks.size() != chapters.size()) throw new IllegalArgumentException("One ink per chapter: " + book);
        if (marks != null && marks.pictures().isEmpty()) marks = null;
        REGISTERED.add(new Papers(book, List.copyOf(chapters), List.copyOf(sheets), inks == null ? null : List.copyOf(inks), painter,
                marks == null ? null : new Marks(List.copyOf(marks.pictures()), marks.bare())));
    }

    /** The papers of a book, or null. */
    public static synchronized @Nullable Papers of(Identifier book) {
        for (Papers papers : REGISTERED) if (papers.book().equals(book)) return papers;
        return null;
    }
}
