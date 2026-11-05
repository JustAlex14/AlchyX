# Modpack Guide

How to change recipes, structures and machines in a pack built on AlchyX, without Java. The same methods work for every AlchyX mod (AlchyMastery, AlchyRift, AlchyFarm, AlchyForbidden); each of those wikis has its own Modpack Guide for what is specific to it.

## Where each thing lives

| What | Where |
|---|---|
| Crafting recipes | `data/<mod>/recipe/<name>.json` |
| Machine shapes | `data/<mod>/multiblock/<name>.json`, see [Pattern Format](Pattern-Format) |
| Machine animations | `data/<mod>/machine_animation/<name>.json`, see [Animations](Animations) |
| Recipes of KubeJS machines | `data/<namespace>/machine_recipe/<name>.json`, see [KubeJS Machines](KubeJS-Machines#3-recipes-kubejsdatamypackmachine_recipejson) |
| Block and item tags | `data/<namespace>/tags/block/` and `tags/item/` |
| Settings | `config/alchyx-common.toml` and `config/alchyx-client.toml` |

All of these are datapack files. In a pack, put them in a datapack, or in `kubejs/data/` if KubeJS is installed. Run `/reload` to apply changes to recipes, tags and machine recipes. Machine shapes are also read on reload.

## Changing a crafting recipe

Put a file at the same path as the original in your datapack. It replaces the original completely. For example, to make the Structure Builder cheaper, copy `data/alchyx/recipe/structure_builder.json` from the mod jar into your pack at the same path and edit it.

The recipes are ordinary Minecraft recipes (`minecraft:crafting_shaped` and `minecraft:crafting_shapeless`), so any guide to vanilla recipe files applies.

## Removing a recipe

Replace the file with one that is never loaded:

```json
{
  "neoforge:conditions": [ { "type": "neoforge:false" } ]
}
```

With KubeJS, a server script does the same:

```js
ServerEvents.recipes(event => {
  event.remove({ id: 'alchyx:structure_builder' })
})
```

## Adding a recipe with KubeJS

```js
ServerEvents.recipes(event => {
  event.shaped('alchyx:structure_builder', ['GDG', 'DND', 'GDG'], {
    G: 'minecraft:gold_block',
    D: 'minecraft:diamond',
    N: 'minecraft:nether_star'
  })
})
```

## Asking for rifted items

AlchyX adds an ingredient that accepts any item rifted to a level or higher, whatever the item. Use it anywhere a recipe takes an ingredient:

```json
{ "neoforge:ingredient_type": "alchyx:rifted", "level": 3 }
```

`level` goes from 1 to 6 and defaults to 1. AlchyFarm's tier 4 stamps use it. JEI shows a few sample items with the mark.

## Changing a machine's shape

Every machine of every AlchyX mod is a pattern file. A file at the same path in your datapack replaces the built-in one, so you can change the blocks, the size or the look of any machine. Start from a copy of the original and keep its `core`: the core block stays the same item.

- To accept more blocks in one spot, change a `block` entry to a `tag` and make your own tag. If the original already uses a tag (for example `alchymastery:copper_grates`), add blocks to that tag instead and leave the pattern alone.
- Check a new shape with the creative Structure Builder: if it builds it and the core forms, the pattern is right.
- Try the change on a copy of a world first. Players may have to rebuild machines that no longer match.

## Adding to a tag

A tag file at the same path adds to the original when `replace` is false, which is the default:

```json
{
  "replace": false,
  "values": [ "minecraft:polished_tuff", { "id": "somemod:marble_bricks", "required": false } ]
}
```

Use `"required": false` for blocks from mods that may not be installed. `"replace": true` throws away the original list.

## New machines

New machines with their own recipes need KubeJS. [KubeJS Machines](KubeJS-Machines) walks through one from the core block to the JEI page.

## Settings

`config/alchyx-common.toml` has `messages.machine` (where machines report forming and breaking: `ACTION_BAR`, `CHAT` or `OFF`). The client file has the forming wave effects and the particle amount. See [For Players](For-Players#settings).

## Shipping it

- Datapack files and KubeJS `data` are server side and reach players when they join.
- A datapack in a world's `datapacks` folder only applies to that world. For a modpack, use KubeJS or a mod that loads datapacks for every world.
- Put your edited config files in the pack's `config` folder.
- Check the log after `/reload`: a file with a mistake is skipped and the log says which one and why.
