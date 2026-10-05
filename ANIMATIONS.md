# Machine animations (AlchyX)

Every machine built on AlchyX can be animated with a JSON file, without code. Files live in a datapack:

```
<world>/datapacks/<any name>/data/<namespace>/machine_animation/<name>.json
```

Run `/reload` (or rejoin) to apply: the server sends the animations to every player. Effects only run while the
machine is formed, and only for players within 64 blocks.

```json
{
  "machine": "alchymastery:destructuration_chamber",
  "replace": false,
  "effects": [ ... ]
}
```

- `machine`: the id of the machine's multiblock pattern (`data/<namespace>/multiblock/<name>.json`). Default: the
  animation file's own id, so `data/alchymastery/machine_animation/destructuration_chamber.json` already means
  `alchymastery:destructuration_chamber`.
- `replace`: several files can animate the same machine and their effects add up. A file with `"replace": true`
  throws away the effects of the files loaded before it (files load in id order).
- `effects`: the list below.

## When an effect runs (all effects)

| Field | Default | Meaning |
|---|---|---|
| `when` | `always` | `always`, `idle`, `working` (repeat while true), `start`, `finish` (once per operation) |
| `interval` | 1 | for `always` / `idle` / `working`: ticks between runs (20 ticks = 1 second) |
| `chance` | 1 | 0 to 1, chance to run each time |
| `delay` | 0 | ticks to wait after the trigger (e.g. a sound 5 ticks after a piston extends) |
| `min_level` | | only while the machine's named levels are at least this much, e.g. `{"corruption": 4}` (machines publish them with `setAnimationLevel`; AlchyMastery: `corruption` = the sum of the installed upgrade tiers) |

## Positions

Positions are written in the pattern's own coordinates and turn with the machine (machines with a facing core).

- `"core"` or an anchor name from the pattern's `"anchors"` (e.g. `"wormhole"`): the center of that block.
- `[x, y, z]`: offset in blocks from the center of the core (decimals allowed). `[0, 1, 0]` is the center of the block
  above the core.
- `{"block": "minecraft:cauldron"}` or `{"block": "#minecraft:campfires", "state": {"lit": true}}`: every block of
  the structure that looks like that (a formed block's look counts).
- Object form: `{"anchor": "wormhole", "offset": [0, -0.5, 0]}` or `{"block": "...", "offset": [...]}`.

## `particle`

```json
{"type": "particle", "when": "working", "interval": 20, "particle": "minecraft:smoke",
 "at": [0, 0.6, 0], "spread": [0.3, 0.1, 0.3], "count": 4, "speed": 0.01}
```

| Field | Default | Meaning |
|---|---|---|
| `particle` | | any particle id, or an object for particles with options: `{"type": "minecraft:dust", "color": [1, 0, 1], "scale": 1}` |
| `item` | | instead of `particle`: crumbs of the item the machine shows under that display name (`"input"`) |
| `at` | core | where (every matching block for a block selector) |
| `spread` | [0, 0, 0] | random offset, +/- blocks per axis |
| `count` | 1 | particles per run and position |
| `style` | `spread` | `spread`: random motion of `speed`; `toward`: flies to `to` and stops there; `attract`: glyph-style particles (enchant, nautilus) drawn into `to` |
| `speed` | 0 | for `spread` |
| `to` | core | target for `toward` / `attract` |
| `drag` | 0.91 | for `toward`: the particle's own friction (0.91 suits most; end rods 0.96) |

## `sound`

```json
{"type": "sound", "when": "finish", "sound": "minecraft:block.grindstone.use", "volume": 0.6, "pitch": 1.0, "pitch_variance": 0.1}
```

`sound` is any sound id (vanilla, modded or from a resource pack), played at `at` (default core).

## `block_move`

Draws a block that moves, e.g. a piston head striking:

```json
{"type": "block_move", "when": "working", "interval": 20,
 "targets": {"block": "minecraft:piston"},
 "direction": "facing",
 "draw": "minecraft:piston_head",
 "swap": {"extended": true},
 "keyframes": [{"t": 0, "move": 0, "state": {"short": true}}, {"t": 1, "move": 0.5, "state": {"short": false}},
               {"t": 2, "move": 1}, {"t": 4, "move": 1},
               {"t": 5.5, "move": 0.5, "state": {"short": true}}, {"t": 7, "move": 0}]}
```

| Field | Default | Meaning |
|---|---|---|
| `targets` | | the blocks that animate (one moving block each) |
| `draw` | copy of the target | the block drawn: `"minecraft:piston_head"` or `{"block": "...", "state": {...}}` |
| `direction` | `facing` | `facing` (the target's own facing), `toward_core`, `outward`, or `up`/`down`/`north`/`south`/`east`/`west` (pattern coordinates) |
| `align` | true | turn the drawn block's facing to the direction |
| `swap` | | state values the target shows while the move lasts (`{"extended": true}` hides a piston's face) |
| `keyframes` | | `t` (ticks from the start), `move` (blocks along the direction), `offset` [x, y, z], `rotation` [x, y, z] degrees around the block's center, `scale`; blended linearly, the move ends at the last `t`. `state` sets state values on the drawn block from that keyframe on (not blended), e.g. `{"short": true}` for a piston head less than half out, so its arm doesn't stick out of the base |

Another use: a glitch, where every block of a kind twitches for a few ticks once a machine is corrupted enough
(not in AlchyMastery's default files, add it to a machine's file if you want it):

```json
{"type": "block_move", "interval": 45, "chance": 0.6,
 "targets": {"block": "minecraft:polished_blackstone_bricks"},
 "direction": "up", "align": false,
 "keyframes": [{"t": 0, "offset": [0, 0, 0]}, {"t": 1, "offset": [0.07, 0, -0.05]},
               {"t": 2, "offset": [-0.06, 0.03, 0.06]}, {"t": 3, "offset": [0, 0, 0]}],
 "min_level": {"corruption": 9}}
```

## `item`

Draws an item the machine displays (each machine names its displays; AlchyMastery: `input` on the
destructuration chamber):

```json
{"type": "item", "display": "input", "at": [0, 0.75, 0], "motion": "spin", "speed": 0.8, "working_speed": 3.0, "scale": 0.6}
```

| Field | Default | Meaning |
|---|---|---|
| `display` | | which item |
| `when` | `always` | `always`, `idle` or `working` (`start`/`finish` count as working) |
| `motion` | `spin` | `spin` in place, `orbit` around the point, `converge` (orbit closing in as the operation progresses), `still` |
| `speed` / `working_speed` | 1 / same | degrees per tick |
| `radius` | 0.5 | orbit radius |
| `copies` | 1 | items evenly spaced around the orbit |
| `per_items` | 0 | if set: one copy per that many items in the stack (at most `copies`) |
| `scale` | 0.5 | |
| `bob` | 0.05 | up-down float in blocks |
| `phase` | 0 | degrees added to the angle: items sharing one orbit stay apart (e.g. 0 / 120 / 240) |

## `figure`

A figure standing (or sitting) in the machine, drawn every frame while `when` holds (`always`, `idle`, `working`).
It's only drawn: no AI, no collision, no sounds of its own (add `sound` effects for that).

```json
{"type": "figure", "entity": "minecraft:cat", "nbt": "{variant:\"minecraft:all_black\",Sitting:1b}",
 "at": "cat", "yaw": -90, "look_at_player": true}
{"type": "figure", "entity": "minecraft:vindicator", "at": "caster", "face": "pedestal", "ghost": true}
{"type": "figure", "figure": "alchymastery:ghost_witch", "unless_display": "linked_player", "at": "caster", "face": "pedestal", "hover": 0.12, "bob": 0.06}
```

| Field | Default | Meaning |
|---|---|---|
| `entity` | | any entity type (vanilla or modded), drawn by its own renderer |
| `nbt` | | SNBT read into the entity: variants (`variant`), `Sitting:1b`, `IsBaby:1b`, armor, held items... |
| `figure` | | instead of `entity`: a figure coded by a mod (posed ghosts...). AlchyMastery: `alchymastery:ghost_witch`, `alchymastery:linked_ghost` (the player in the effect's `display`) |
| `at` | `core` | where its feet are (bottom of that block, plus the offset) |
| `face` | | a position it turns toward |
| `yaw` | `0` | otherwise a heading in the pattern's frame: 0 = toward +z (south as written), 90 = toward -x, -90 = toward +x; turned with the machine |
| `look_at_player` | `false` | the head follows the camera (entities) |
| `hover` / `bob` | `0` | blocks above the floor / up-down amplitude in blocks |
| `scale` | `1` | size |
| `ghost` | `false` | see-through, like an invisible mob seen by a spectator (entities) |
| `display` / `unless_display` | | only while the machine's display of that name holds an item / is empty |

Mods register coded figures with `Figures.register(id, (context, poseStack, collector) -> ...)` during client setup
(see `CodedFigure`), and can draw a machine's figures elsewhere (a miniature) with `FigureDrawer.prepare` / `submit`.

## For mod developers

A machine core block entity extends `MultiblockCoreBlockEntity` and feeds the animation state:
`setAnimationWorking(boolean)`, `startAnimationOperation(ticks)`, `finishAnimationOperation()`,
`setAnimationDisplay("input", stack)`. Everything else is in the file. Example: AlchyMastery's
`data/alchymastery/machine_animation/destructuration_chamber.json`.
