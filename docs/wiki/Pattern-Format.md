# Pattern Format

A pattern is a JSON file in a datapack:

```
data/<namespace>/multiblock/<name>.json
```

Its id is `<namespace>:<name>`. Mods ship them in `src/main/resources/data/...`, modpacks put them in a datapack or in `kubejs/data/...`. They load when the world loads and on `/reload`, and the server sends them to every player (for previews and book pages). A file with a mistake is skipped and the log says why.

A datapack can override a mod's pattern by using the same id. The machine then uses the new shape, and the Structure Builder, previews and book pages follow it.

## Top level

| Field | Required | Meaning |
|---|---|---|
| `core` | yes | the block id of the machine's core, e.g. `"alchymastery:condensator_core"` |
| `layers` | yes | what the player builds (see below) |
| `key` | yes | what each character of `layers` means |
| `result` | no | what each position turns into once formed. Same size as `layers`, its own characters. Without it nothing is converted |
| `result_key` | with `result` | what each character of `result` means |
| `front` | no | `north`, `south`, `east` or `west`: the side the grid's front faces as written |
| `anchors` | no | named spots, as `[x, y, z]` offsets from the core in the written grid |

## The grid

`layers` is a list of layers from the **bottom** to the **top**. Each layer is a list of rows seen from above: the first row is the **north** edge, the last row is the **south** edge, and in each row the characters go from **west** to **east**.

```json
"layers": [
  ["PPP", "PPP", "PPP"],
  ["G.G", ".X.", "G.G"],
  ["PPP", "PPP", "PPP"]
]
```

Rules checked when the file loads:

- every layer has the same number of rows, and every row the same length
- every character is in `key`, and every key is a single character
- exactly one character in the whole grid is the core

Positions are measured from the core, so the core can be anywhere in the grid.

## Key entries

Each key entry says what the player must place there. It needs **exactly one** of `block`, `tag`, `air`, `core` or `any`.

| Field | Meaning |
|---|---|
| `block` | a block id, e.g. `"minecraft:glowstone"` |
| `tag` | a block tag, e.g. `"alchymastery:copper_grates"` (any block of the tag works) |
| `air` | `true`: built empty. The Structure Builder and previews leave it empty, but anything the player puts there later is fine (a torch, a tap on a cauldron). It never breaks the machine |
| `core` | `true`: the machine's core. `matrix` is an older name for the same thing |
| `any` | `true`: anything goes, including air |
| `state` | with `block` or `tag`: block state values that must match, e.g. `{"lit": true}` or `{"level": 3}`. Several allowed values are separated by `\|`: `{"level": "3\|2\|1"}`. The Structure Builder places the first one |
| `consumed` | with `block` or `tag`: the block is destroyed without drops when the machine breaks (lava, candles, a block used up by forming) |
| `stairs` | with `block` or `tag`: an orientation rule for stairs, see below |
| `facing` | with `block` or `tag`: for blocks with a `facing` property (pistons, observers, wall torches). `center`: must point toward the core. `outward`: must point away from it |
| `facing_check` | default `true`. `false`: the `facing` rule only orients what the Structure Builder places and previews show, and the check accepts any facing |
| `name` | how the block is named in the `... needed at x y z` message. Write it for players: `"a full water cauldron"` |

State values can be written as strings or as plain JSON booleans and numbers. A state name that the block does not have is an error.

### Stair rules

| Rule | What the check wants |
|---|---|
| `face_center` | the tall side faces the core's column. Below the core's level the stairs sit on the floor, above it they hang upside down |
| `roof_edge` | upright stairs on the outer edge of the grid, tall side toward the middle. Also works for a floor rim |
| `roof_corner` | upright stairs at a corner, facing inward along either edge, joined into an outer corner |

## Result entries

Each `result_key` entry says what a position becomes when the machine forms. It needs **exactly one** of `block`, `same` or `keep`.

| Field | Meaning |
|---|---|
| `keep` | leave the block exactly as the player placed it. The core's cell must be `keep` |
| `same` | become a machine part that looks exactly like the block that was there |
| `block` | become a machine part that looks like this block (any block id, vanilla or modded) |
| `state` | with `block`: state values for the look, e.g. `{"axis": "y"}` |
| `copy_state` | with `block`: copy matching state values from the original block (stairs keep their facing, half and shape). Applied before `state` |
| `hidden` | with `block` or `same`: the part stays part of the machine but draws nothing (windows that vanish so the inside shows) |
| `real` | with `block`: place the real block instead of a part, for blocks whose flames or particles must show (candles, a spore blossom). Usually on an air cell, so it appears when the machine forms. Removed without drops when the machine breaks |
| `restore` | with `same`: when the machine breaks, the original block comes back instead of being destroyed. For valuable blocks absorbed by a machine. Breaking that part itself drops the original block |

A machine part (a "chamber shell") is AlchyX's own block wearing the look you chose. It has the look's shape, does not drop anything, opens the machine when clicked, and lets pipes reach the core.

A result block cannot be air or AlchyX's shell block itself. Use `keep` to leave a position alone.

When the machine breaks, every part and every `consumed` block is removed without drops. Kept blocks stay.

## Front and rotation

Without `front`, the pattern is always used as written.

With `front`, and a core block that has a `facing` property (`FacingCoreBlock`), the whole pattern is turned so its front matches the way the core faces. The player can then build the machine in any of the four directions. Block states inside the pattern (stairs, pistons, logs) are turned too, so a pattern written facing north checks a structure built facing east correctly.

Pick `front` as the side of the grid where the core's face is. If the core sits in the north wall and looks out, write `"front": "north"`.

## Anchors

```json
"anchors": {"input_shelf": [1, 1, 1], "wormhole": [0, -1, 0]}
```

An anchor is a named offset from the core in the written grid. Animation files use them as positions (`"at": "wormhole"`), and your code can ask for the world position, turned with the machine:

```java
BlockPos shelf = pattern().anchor(worldPosition, rotation(), "input_shelf");
```

## A complete example

AlchyMastery's distortion condensator (`data/alchymastery/multiblock/distortion_condensator.json`) uses most of the features: tags, states with several values, `facing`, both stair rules, `air` and `any` cells, `copy_state`, a custom look block and `same`.

```json
{
  "core": "alchymastery:condensator_core",
  "front": "east",
  "layers": [
    ["PPPPP", "PPPPE", "PPLPE", "PPPPE", "PPPPP"],
    ["wgggw", "q.c._", "qcb._", "q.c._", "wgggw"],
    ["wgggw", "q.d._", "qdX._", "q.d._", "wgggw"],
    ["wgggw", "q.d._", "qd.._", "q.d._", "wgggw"],
    ["CRRRC", "RSSSR", "RSWSR", "RSSSR", "CRRRC"]
  ],
  "key": {
    "P": {"block": "minecraft:cobblestone", "name": "cobblestone"},
    "E": {"block": "minecraft:cobblestone_stairs", "state": {"half": "bottom"}, "facing": "center", "name": "cobblestone stairs (front step)"},
    "L": {"block": "minecraft:glowstone", "name": "glowstone under the basin (becomes a sea lantern)"},
    "w": {"block": "minecraft:cobblestone_wall", "name": "a cobblestone wall (corner post)"},
    "g": {"block": "minecraft:glass", "name": "glass (side window)"},
    "q": {"tag": "alchymastery:copper_grates", "name": "a copper grate (back wall, any oxidation)"},
    "c": {"block": "minecraft:water_cauldron", "state": {"level": 3}, "name": "a full water cauldron"},
    "d": {"block": "minecraft:pointed_dripstone", "state": {"vertical_direction": "down"}, "name": "pointed dripstone hanging from the roof (two blocks long)"},
    "C": {"block": "minecraft:cobblestone_stairs", "stairs": "roof_corner", "name": "cobblestone stairs (roof corner)"},
    "R": {"block": "minecraft:cobblestone_stairs", "stairs": "roof_edge", "name": "cobblestone stairs (roof edge)"},
    "S": {"block": "minecraft:cobblestone_slab", "state": {"type": "bottom"}, "name": "a cobblestone slab (bottom half)"},
    "b": {"block": "minecraft:water_cauldron", "state": {"level": "3|2|1"}, "name": "a water cauldron under the core"},
    "W": {"block": "alchymastery:wormhole", "name": "a wormhole"},
    "X": {"core": true},
    ".": {"air": true},
    "_": {"any": true}
  },
  "result": [
    ["ppppp", "ppppe", "ppLpe", "ppppe", "ppppp"],
    ["wgggw", "o.=..", "o==..", "o.=..", "wgggw"],
    ["wgggw", "o.t..", "ot...", "o.t..", "wgggw"],
    ["wgggw", "o.u..", "ou...", "o.u..", "wgggw"],
    ["xxxxx", "xsssx", "xs.sx", "xsssx", "xxxxx"]
  ],
  "result_key": {
    "p": {"block": "minecraft:dark_prismarine"},
    "e": {"block": "minecraft:dark_prismarine_stairs", "copy_state": true},
    "g": {"block": "minecraft:cyan_stained_glass"},
    "o": {"block": "minecraft:oxidized_copper_grate"},
    "x": {"block": "minecraft:dark_prismarine_stairs", "copy_state": true},
    "s": {"block": "minecraft:dark_prismarine_slab", "copy_state": true},
    "t": {"block": "alchymastery:chamber_part_stalactite"},
    "u": {"block": "alchymastery:chamber_part_stalactite", "state": {"base": true}},
    "=": {"same": true},
    ".": {"keep": true},
    "L": {"block": "minecraft:sea_lantern"},
    "w": {"block": "minecraft:prismarine_wall", "copy_state": true}
  }
}
```

Note `alchymastery:chamber_part_stalactite`: a block the mod registers only to be worn as a look. Any block you register can be a look, which is how a machine gets parts that do not exist in the vanilla game.

## Patterns from code

`MultiblockPatterns` holds what was loaded:

```java
MultiblockPattern pattern = MultiblockPatterns.get(id);      // null if missing or broken (server side)
MultiblockPattern forBlock = MultiblockPatterns.forCore(block); // the pattern whose core is this block
Map<Identifier, MultiblockPattern> onClient = MultiblockPatterns.client(); // what the server sent
```

Useful methods of `MultiblockPattern`: `findProblem(level, core, rotation)` (null when complete), `blueprint(core, rotation)` (what to place where, used by the Structure Builder), `allPositions(core, rotation)`, `contains(core, rotation, pos)`, `anchor(core, rotation, name)`, `minOffset()` and `maxOffset()`.
