# Making machines with KubeJS (AlchyX)

With AlchyX and KubeJS installed, a modpack can add complete multiblock machines without Java: a core block from a
startup script, the structure and recipes as JSON in `kubejs/data/`, animations as JSON, and optional server
script events. Everything below is one working example, a **Crusher**: a 3x3x3 structure whose piston crushes ores (with
Forge Energy) and cobblestone (with water) on a grindstone.

## 1. The core block (`kubejs/startup_scripts/machines.js`)

```js
StartupEvents.registry('block', event => {
  event.create('mypack:crusher_core', 'alchyx:machine_core')
    .displayName('Crusher Core')
    .pattern('mypack:crusher')          // data/mypack/multiblock/crusher.json (default: same id as the block)
    .itemInputs(1)                      // slots (0-9 each, default 1 / 1)
    .itemOutputs(2)
    .fluidInputs(1)                     // tanks (0-2 each, default 0)
    .tankCapacity(4000)                 // mB per tank (default 8000)
    .energy(50000, 500)                 // FE buffer and max FE/t from cables (no energy() = needs none)
    .texture('side', 'minecraft:block/blast_furnace_side')
    .texture('top', 'minecraft:block/blast_furnace_top')
    .texture('front', 'minecraft:block/blast_furnace_front')
})
```

Other options: `.recipeType('mypack:crushing')` (several cores sharing recipes; default: the block id) and every
normal KubeJS block option (hardness, sound...). The core faces the player who places it, like a furnace; with
`"front"` in the pattern, the whole structure turns with it. Startup scripts need a game restart.

## 2. The structure (`kubejs/data/mypack/multiblock/crusher.json`)

Layers go bottom to top; each layer is seen from above, rows north to south. `X` is the core; a piston facing
down hangs over the empty center, above a grindstone set in the floor.

```json
{
  "core": "mypack:crusher_core",
  "front": "north",
  "anchors": {"center": [0, 0, 1]},
  "layers": [
    ["BBB", "BRB", "BBB"],
    ["BXB", "G.G", "BBB"],
    ["BBB", "BPB", "BBB"]
  ],
  "key": {
    "B": {"block": "minecraft:stone_bricks", "name": "stone bricks"},
    "G": {"block": "minecraft:glass", "name": "glass"},
    "R": {"block": "minecraft:grindstone", "state": {"face": "floor"}, "name": "a grindstone on the floor (under the center)"},
    "P": {"block": "minecraft:piston", "state": {"facing": "down"}, "name": "a piston facing down (above the center)"},
    "X": {"core": true},
    ".": {"air": true}
  },
  "result": [
    ["bbb", "bsb", "bbb"],
    ["bXb", "s.s", "bbb"],
    ["bbb", "bsb", "bbb"]
  ],
  "result_key": {
    "b": {"block": "minecraft:polished_blackstone_bricks"},
    "s": {"same": true},
    "X": {"keep": true},
    ".": {"keep": true}
  }
}
```

When the structure is right, the core forms it: every block becomes a machine part that looks like its `result`.
Breaking any part breaks the machine (parts vanish without drops; the core keeps its contents). The full pattern
format (tags, block states, stairs and facing rules, consumed blocks, hidden parts...) is the same as AlchyMastery's
machines. The creative **Structure Builder** (`alchyx:structure_builder`) builds any pattern around its core.

## 3. Recipes (`kubejs/data/mypack/machine_recipe/*.json`)

`iron.json`:
```json
{
  "machine": "mypack:crusher_core",
  "inputs": [{"item": "#c:ores/iron"}],
  "outputs": [{"item": "minecraft:raw_iron", "count": 2}, {"item": "minecraft:gravel", "chance": 0.25}],
  "time": 100,
  "energy": 2000
}
```

`gravel.json` (with a fluid):
```json
{
  "machine": "mypack:crusher_core",
  "inputs": [{"item": "minecraft:cobblestone", "count": 2}],
  "fluid_inputs": [{"fluid": "minecraft:water", "amount": 250}],
  "outputs": [{"item": "minecraft:gravel"}],
  "time": 40
}
```

| Field | Default | Meaning |
|---|---|---|
| `machine` | | the core's recipe type (its block id unless `recipeType` was set) |
| `inputs` | [] | `{"item": id, #tag or [list], "count": 1}`; each input needs its own input slot |
| `fluid_inputs` | [] | `{"fluid": id, "amount": mB}`; each in its own input tank |
| `outputs` | [] | `{"item": id, "count": 1, "chance": 1}`; all must fit before the machine starts |
| `fluid_outputs` | [] | `{"fluid": id, "amount": mB}` |
| `time` | 100 | ticks (20 = 1 second) |
| `energy` | 0 | FE for the whole operation, spread over its time (the machine pauses when short) |

The first recipe (by file id) whose inputs are present and whose outputs fit runs. `/reload` applies changes.
JEI shows a page per machine.

## 4. Animations (`kubejs/data/mypack/machine_animation/crusher.json`)

```json
{
  "machine": "mypack:crusher",
  "effects": [
    {"type": "item", "display": "input", "at": {"anchor": "center", "offset": [0, -0.15, 0]}, "motion": "spin", "speed": 1, "working_speed": 4, "scale": 0.5, "bob": 0.03},

    {
      "type": "block_move", "when": "working", "interval": 20,
      "targets": {"block": "minecraft:piston"},
      "direction": "facing",
      "draw": "minecraft:piston_head",
      "swap": {"extended": true},
      "keyframes": [
        {"t": 0, "move": 0, "state": {"short": true}},
        {"t": 1, "move": 0.5, "state": {"short": false}},
        {"t": 2, "move": 0.6},
        {"t": 4, "move": 0.6},
        {"t": 5, "move": 0.5, "state": {"short": true}},
        {"t": 7, "move": 0}
      ]
    },
    {"type": "sound", "when": "working", "interval": 20, "sound": "minecraft:block.piston.extend", "volume": 0.4, "pitch": 0.9, "pitch_variance": 0.1},
    {"type": "sound", "when": "working", "interval": 20, "delay": 5, "sound": "minecraft:block.piston.contract", "volume": 0.4, "pitch": 0.9},
    {"type": "particle", "when": "working", "interval": 20, "delay": 2, "item": "input", "at": "center", "count": 8, "spread": [0.2, 0.1, 0.2], "speed": 0.06},
    {"type": "sound", "when": "working", "interval": 20, "delay": 2, "sound": "minecraft:block.grindstone.use", "volume": 0.4, "pitch": 1.2},

    {"type": "sound", "when": "finish", "sound": "minecraft:block.anvil.land", "volume": 0.3, "pitch": 1.5},
    {"type": "particle", "when": "finish", "particle": "minecraft:cloud", "at": "center", "count": 4, "spread": [0.2, 0.1, 0.2], "speed": 0.01}
  ]
}
```

The piston strikes the floating item every second (block move with the head, `short` arm while it's less than
half out), with crumbs and sounds; each finished operation lands with an anvil sound. Note `machine` here is the
**pattern** id. Displays of script machines: `input` (first input slot), `output`
(first output slot), `result` (the first output of the running recipe). Full reference: `ANIMATIONS.md`.

## 5. Script events (`kubejs/server_scripts/machines.js`, optional)

```js
// A redstone signal on the core pauses the crusher
AlchyXEvents.operationStarting('mypack:crusher_core', event => {
  if (event.level.hasNeighborSignal(event.pos)) event.cancel()
})

AlchyXEvents.operationFinished('mypack:crusher_core', event => {
  console.info(`Crusher at ${event.pos} finished ${event.recipeId}`)
})

AlchyXEvents.formed(event => console.info(`${event.block} formed at ${event.pos}`))
```

| Event | When | Notes |
|---|---|---|
| `formed` | the structure finished forming | |
| `broken` | the structure broke | |
| `operationStarting` | a recipe is about to start | `event.cancel()` prevents it (retried when inputs change) |
| `operationFinished` | a recipe finished, outputs inserted | |
| `tick` | every tick while formed | the core id is required: `AlchyXEvents.tick('mypack:crusher_core', e => ...)` |

The core id argument is optional except for `tick`. Every event has `event.level`, `event.pos`, `event.block` (core
id) and `event.machine`: `getItem(slot)`, `setItem(slot, stack)` (inputs first, then outputs), `getFluid(tank)`,
`getFluidAmount(tank)`, `getEnergy()`, `setEnergy(fe)`, `getProgress()`, `getCurrentRecipe()`, `isWorking()`,
`getSpec()`. Operation events add `event.recipeId` and `event.recipe`.

## 6. A guide book page (optional, needs Patchouli)

AlchyX registers every structure file as a Patchouli multiblock under its pattern id, so a book page only needs
`"multiblock_id"`: the 3D preview and the **Visualize** ghost blocks always match the real structure, also after
`/reload`. AlchyX ships a **Machine Manual** (AlchyX creative tab) with a "Building Machines" chapter and an empty
"Machines" category for your machines. Add one entry per machine in
`kubejs/assets/alchyx/patchouli_books/manual/en_us/entries/machines/crusher.json`:

```json
{
  "name": "Crusher",
  "icon": "mypack:crusher_core",
  "category": "alchyx:machines",
  "pages": [
    {"type": "patchouli:text", "title": "Crusher", "text": "Crushes ores into raw materials with Forge Energy."},
    {"type": "patchouli:multiblock", "name": "Crusher", "multiblock_id": "mypack:crusher", "enable_visualize": true,
     "text": "Stone bricks, glass sides, a piston over a grindstone."},
    {"type": "patchouli:spotlight", "item": "mypack:crusher_core", "text": "The Crusher Core."}
  ]
}
```

The same `"multiblock_id"` works in your own modpack book. The Structure Builder and the Manual are in the AlchyX creative tab;
machine cores appear in KubeJS's tab like any script block.

## Before it's formed

Right-clicking an unformed core opens its GUI too, with a **Show/Hide structure** button above it: small ghost
blocks show what's still missing around the core, and red dust marks blocks that are wrong. It turns itself off
once the machine forms.

## Automation

Hoppers, pipes and cables work on the core and on every part of the formed structure: items go into the input slots
and come out of the output slots, fluids into input tanks and out of output tanks, FE into the buffer. Buckets
work by right-clicking the machine.
