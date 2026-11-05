# Guide Books

AlchyX has three helpers for Patchouli books. Patchouli is optional: when it is missing, nothing here runs and nothing breaks, so you can call these helpers either way.

## Every pattern is a book multiblock

When the server sends the patterns to a player, AlchyX registers each one as a Patchouli multiblock under the pattern's id. A book page needs only `multiblock_id`:

```json
{
  "type": "patchouli:multiblock",
  "name": "Kiln",
  "multiblock_id": "mymod:kiln",
  "enable_visualize": true,
  "text": "Bricks around a lit campfire, slabs on top."
}
```

The 3D preview shows what the Structure Builder would place. The **Visualize** ghost accepts whatever the structure check accepts (any block of a tag, any of the allowed states). Both follow `/reload` and datapack overrides, so the book never shows an old shape.

AlchyX also replaces Patchouli's multiblock page renderer so big structures are scaled to fit the page instead of being cropped. Nothing to do on your side.

### The Machine Manual

AlchyX ships its own small book, the Machine Manual (`alchyx:manual`, in the AlchyX creative tab). It has a Building Machines chapter and an empty Machines category (`alchyx:machines`) that modpacks and mods can fill with entries in `assets/alchyx/patchouli_books/manual/en_us/entries/machines/`. Most mods will prefer their own book.

## Chapters that unlock when another is read

Patchouli can lock an entry behind an advancement. AlchyX adds a way to award a criterion when the player opens an entry, so you can unlock chapter B by reading chapter A.

When a player opens any entry, the server awards every advancement criterion named `read:<entry id>`. Make an advancement with an impossible criterion of that name:

`data/mymod/advancement/read_intro.json`:

```json
{
  "criteria": {
    "read:mymod:basics/intro": {"trigger": "minecraft:impossible"}
  }
}
```

and lock the next entry behind it:

```json
{
  "name": "The Kiln",
  "category": "mymod:basics",
  "advancement": "mymod:read_intro",
  "pages": [ ... ]
}
```

This works for any Patchouli book, including ones you did not write.

## Paper that changes per chapter

`com.lealex.alchyx.client.BookPapers` lets a book's paper change with the chapter being read, fading from one sheet to the next. Register it once from your client entry point:

```java
List<Identifier> sheets = List.of(
        Identifier.fromNamespaceAndPath(MyMod.MODID, "textures/gui/book.png"),     // must be the book.json book_texture
        Identifier.fromNamespaceAndPath(MyMod.MODID, "textures/gui/book_2.png"),
        Identifier.fromNamespaceAndPath(MyMod.MODID, "textures/gui/book_3.png"));
BookPapers.register(Identifier.fromNamespaceAndPath(MyMod.MODID, "guide"),
        List.of("basics", "kilns", "deeper"),   // the paths of the book's categories, in order
        sheets);
```

- One sheet per chapter, all the same size as Patchouli's book texture. The first sheet is the texture `book.json` names as `book_texture`.
- The longer overload adds `inks` (text, header, nameplate, link and link hover colours per chapter), a `Painter` called every tick to draw over the sheet (drifting clouds, blinking stars), and `Marks`.
- `Marks(pictures, bare)`: pictures the size of the open book (272 x 180), see-through except for a mark on the left page and one on the right. Each page always gets the same mark, or nothing for a `bare` share of the pages.

AlchyFarm's `AlchyFarmClient` registers its ledger with six sheets and six marks. AlchyMastery's `VoidBookTexture` is a painter that animates the void.
