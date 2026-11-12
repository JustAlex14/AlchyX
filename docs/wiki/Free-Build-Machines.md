# Free-Build Machines

A free-build machine has no fixed shape. Each player builds it the way they like, inside a box and within a few rules, then records the build with a **Structure Recorder**. That shape is saved on that one machine: two players' machines of the same kind can look completely different.

## For players

1. Place the machine's core.
2. Build around it. Use the Recorder on the core with no corners marked to see the rules (how big, which blocks it needs).
3. Use the Recorder on a block: the first corner of the box. Use it on the opposite block: the second corner. The box shows as lines of sparks while the Recorder is in hand.
4. Use the Recorder on the core. If the build follows the rules, it is recorded and the machine forms. If not, chat says what is wrong: the box is too big, a block is not allowed, or some required blocks are missing.

After that the machine works like any other: break a block of it and it unforms, put it back and it forms again. The parts look exactly like the blocks you placed, and when the machine breaks they turn back into those blocks (nothing is lost).

### Making blocks move

Once the machine stands, use the Recorder on any of its blocks to make that block move while the machine works. Each use picks the next motion, then back to still:

| Motion | What the block does |
|---|---|
| spin | turns around itself, a full turn every 2 seconds |
| bob | floats up and down |
| strike | strikes out the way it faces, like a piston, and comes back |
| swing | swings a quarter turn like a door, waits, swings back |
| shake | trembles in place |
| light | lights up: furnaces, smokers, blast furnaces, campfires, candles, redstone lamps |
| fill | fills up as the work goes on: cauldrons (with water), composters, beehives, respawn anchors, snow layers, candles, sea pickles, and any modded block whose fill is a number in its state (`level`, `honey_level`, `charges`, `layers`...) |
| fill_lava | a cauldron fills with lava |
| fill_snow | a cauldron fills with powder snow |
| grow | grows: crops, saplings, anything with an `age` in its state (modded crops too) |
| open | opens: barrels, trapdoors, doors, fence gates |
| change | goes through the states you pick in the Machine Studio (**State...**), each from its own keyframe: any block with a state, like a trial spawner turned active or a vault ejecting, modded blocks too, and cauldrons (their level, and whether they hold nothing, water, lava or powder snow) (studio only) |

A piston given **strike** pushes its head out and back like a real piston. The last three only work on blocks that can do it.

Sneak + use on a block stops it at once. Some machines allow only some motions, or none.

### The Machine Studio

Once the machine stands, use the Recorder on its **core** with no corners marked: the Machine Studio opens. It shows the machine in 3D, like a page of a guide book, but you can change it:

- **Drag** to turn the view, use the **wheel** to zoom, and use the **- / +** layer buttons to hide the top layers and look inside.
- **Blocks** tab: **click** a block to choose it. **Shift + click** adds or removes one block. **Ctrl + click** chooses every block of that kind. Pick a **motion** on the right; greyed motions are the ones those blocks can't do. To time it, drag its bar on the timeline (see below), or move the player and press **Start here** / **Stop here** (a spawner that spins from 2 s to 5 s); **Always** plays it all the time again. **State...** opens a menu with every property of the block (a trial spawner's `trial_spawner_state`, a vault's `vault_state`, `lit`, `facing`...): pick a value for each, or **keep**. A cauldron also gets **Holds** (nothing, water, lava or powder snow) and its `level`. What you pick is a **step** from where the player stands on the work bar: move the player and press **State...** again to change the step showing there, **New step here** to add another one from that point (water level 1 at 1 s, level 3 at 2.5 s, lava at 4 s), or **Remove step**. Each step shows from its keyframe to the next one's, the block looks as it is before the first, and each step gets its own bar on the timeline to drag. A spawner has no state of its own, so it can spin or bob but not change. **Data...** opens the block's data (its block entity data as SNBT, like `/data` shows it: a spawner's mob, a banner's patterns, a modded block's settings) to change it; a spawner also gets a mob box (`minecraft:blaze`, **Set mob**). The machine keeps that data with the block and draws the block with it in the world (a spawner shows its mob turning inside). It only changes how the machine looks: the data never goes back into a real block. Blocks that had data when the machine formed keep it on their own (not containers: their contents drop as before).
- **Anchors** tab: click the side of a block to pick the spot in front of it (inside the build or right around it). Then choose **Fx** (particles: smoke, flames, enchanting glyphs...), a **Mob** (a figure: a villager, a golem, a cat...), an **Item** (the item the machine works on: its input, output or result, or any item), a **Snd** (sound) or a **Mod** effect (something a mod draws itself, like a rift portal; only when a mod adds some), and use **< >** to pick which one. **Turn** turns a figure or a mod effect. For an item, **Travel** then a click on another spot makes it travel there as the work goes on (the cobblestone carried from the hopper to the press). Click an anchor to change or **Remove** it. Up to 24 anchors.
- **Sounds**: **Sound** (or **Pick sound...** on a sound anchor) opens every sound of the game, modded ones too, with a search bar; click one to hear it, double click or **Done** to pick it. It plays as the work reaches the start of its bar on the timeline; **Once** / **Every 0.5s, 1s, 2s** makes it play once or again and again until its bar ends. The preview plays it too. (The sounds the machine's own animation file plays stay as they are.)
- The **work bar** at the bottom of the view plays a pretend operation: items travel and cauldrons fill along with it. Drag it to scrub through the work.
- **Items...** (Anchors tab): the anchor's item list, on its own screen. Each item has a row: how it moves while it shows (**Spin**, **Still**, **Bob**, or **Travel**: it goes to the anchor's other spot through its own time), **^ v** to move it in the list and **x** to take it out. **Add item...** opens a menu with every item in the game, modded ones included, with a search bar (by name, id or mod name, like `create:`); the machine's displays come first: **input**, **output** and **result**. **One by one** shares the work evenly, in list order. **Done** keeps the list, **Cancel** leaves it as it was. On an empty spot it makes a new item anchor. In the studio, the items under the anchor are lit while they show; click one to open the list.
- **The timeline**: over the work bar (the player), each timed thing has a track with a bar from where it starts to where it ends: the chosen blocks' motion (Blocks tab), or the chosen anchor and each of its items (Anchors tab), so an item can come in at one keyframe and go at another, and two can overlap. Drag a bar's ends, or the whole bar, along the work: they snap to the player's head and to the other keyframes. Click beside a bar to move the player there. Times show in seconds of the preview's work (5 s; on the machine they stretch to its real work). The buttons still work too: **Appear here** / **Vanish here** for an anchor, **Leave here** / **Arrive here** for a traveling display, **Reset times**; **Start here** / **Stop here** / **Always** for blocks.
- To put an anchor **inside** the build (the result appearing in the middle of the crusher), hide the top layers with **-**, then click the top of the block under the spot.
- **Play / Pause** (or Space) previews the motions in the view, before anything changes in the world.
- **Save** sends the motions to the machine. **Close** leaves without saving.

The machine must stay formed and within 16 blocks while the screen is open. Only motions the machine allows are offered, and the server checks them again on save.

- **Sneak + use the Recorder on the core**: forget its shape, to rebuild it differently. A formed machine breaks first.
- **Sneak + use the Recorder in the air**: forget the corners.
- Chests, barrels and other blocks that hold things (items, fluid or energy, modded ones too) stay real blocks, so they keep what is in them. So do liquids. Other blocks with data (a spawner, a banner, a sign, a head...) become parts that keep their data and show it: a spawner shows its mob turning (but spawns nothing), and gets its data back as it was when the machine breaks. Change what they show with **Data...** in the studio.
- A piston in the machine is never an opaque part, so the blocks around it don't show holes when it strikes.
- The Structure Builder rebuilds a free-build machine's recorded shape, like any other machine.

Recipe: a stick, a compass, two gold ingots and an amethyst shard (diagonal, the shard at the top right).

## For mod and modpack makers

Add `free_build` to a pattern file. The grid is then just the core, so the rest of AlchyX still knows the machine:

```json
{
  "core": "mymod:press_core",
  "layers": [["X"]],
  "key": {"X": {"core": true}},
  "free_build": {
    "max_width": 7,
    "max_height": 5,
    "min_blocks": 20,
    "allowed": ["#minecraft:stone_bricks", "#c:glass_blocks", "minecraft:piston", "minecraft:hopper", "minecraft:cauldron"],
    "forbidden": ["minecraft:tnt"],
    "requires": [
      {"block": "minecraft:piston", "count": 2, "name": "pistons"},
      {"block": "minecraft:hopper", "count": 1, "name": "a hopper"},
      {"block": "#c:glass_blocks", "count": 4, "name": "glass"}
    ],
    "formed": "same",
    "motions": ["spin", "strike", "shake"],
    "motion_when": "working"
  }
}
```

| Field | Default | Meaning |
|---|---|---|
| `max_width` | 7 | the most blocks along x and along z (32 at most) |
| `max_height` | 7 | the most blocks along y (32 at most) |
| `min_blocks` | 1 | the fewest blocks in the box, not counting air and the core |
| `allowed` | any block | block ids or `#tags` the build may use |
| `forbidden` | none | block ids or `#tags` it may never use |
| `requires` | none | blocks it must contain: `block` (id or `#tag`), `count` (default 1), `name` (what the player reads) |
| `formed` | `same` | `same`: the blocks become machine parts that look the same, and turn back into the blocks when the machine breaks. `keep`: they stay as they are |
| `motions` | all | the motions players may give the blocks: `spin`, `bob`, `strike`, `swing`, `shake`, `light`, `fill`, `fill_lava`, `fill_snow`, `grow`, `open`, `change`. `[]`: none |
| `motion_when` | `working` | when the motions (and anchor particles) play: `always`, `idle` or `working` |
| `particles` | a list of 22 | particle types players may put at anchors in the Machine Studio (plain particle types only). `[]`: none |
| `figures` | a list of 20 | entity types players may stand at anchors in the Machine Studio (only drawn: no AI, no collision). `[]`: none |

Blocks that can never be used: a part of another machine, another machine's core, and blocks nothing can break (bedrock, barriers).

### Keep the machine fair

The shape is the player's, so the machine's strength should come from what you require and from its tier, never from the shape. A fancy build should be neither better nor worse than a plain one with the same required blocks.

### What gets recorded

Every block in the box is recorded exactly, turned the way it stands (facing, axis, slab type, upper or lower half). State that changes by itself, like a crop's age or a stair's corner shape, is not checked. Air is recorded as "anything": a torch placed later never breaks the machine.

### Any machine, your own way (after building it once)

A machine with a fixed shape (AlchyMastery's, a KubeJS machine...) unlocks free building once its player has completed it one time: from then on, that core may stand in any build. To change it:

1. Take one block of the standing machine out (it breaks, its parts turn back into their blocks).
2. Build whatever you want around the core (any blocks, none required, up to 32 x 32 x 32: a whole factory), mark two opposite corners with the Structure Recorder, then use the Recorder on the core: the shape is recorded and the machine forms from it, working as before.
3. **Sneak + use the Recorder on the core** forgets that shape: the machine needs its original shape again (it stays unlocked).

The core remembers it was completed, also when it is broken and placed again. What was made in the studio for the old layout is cleared when a new shape is recorded. Machines kept out of the studio (`studioEditable()` false, or the `alchyx:studio_locked` tag) never unlock.

### The studio on any machine

The Machine Studio also opens on machines with a fixed shape (AlchyMastery's, for example): use the Recorder on a formed machine's core with no corners marked. Everything above works the same, on the blocks the machine is made of. Two more buttons at the bottom:

- **Own anim: on / off**: the machine's own animation (its mod's files) keeps playing along with what you make (on), or stops so only yours plays (off). **Save** keeps it.
- **Export**: opens a window to name the file (it starts as `<machine>_studio`), then writes what you made (saved or not) as an animation file in the game folder, `alchyx_exports/<mod>/machine_animation/<name>.json`. **Open folder** shows it. Put it in a datapack at `data/<mod>/machine_animation/` and every machine of that kind plays it. With the own animation off, the file says `"replace": true` and replaces the mod's.
- **Import**: opens a window listing every file in `alchyx_exports` (this machine's first). Pick one (double click, or **Import**) to bring it back into the studio, in place of what's there, to change it. **Open folder** to drop in a file made on another world or by someone else. Nothing changes on the machine until **Save**. The file keeps what you made in the pattern's own frame, so it fits a machine of that kind turned any way.

The preview also plays the machine's own block moves (a piston striking, from its animation file) while its own animation is on.

An example to import, a KubeJS **Egg Hatchery** (an egg in, a random spawn egg out) whose animation uses every studio feature: [docs/examples/egg-hatchery](https://github.com/JustAlex14/AlchyX/tree/free-build/docs/examples/egg-hatchery).

What you save is kept on that one machine only. Block data (**Data...**) stays on the machine and isn't exported.

A mod can keep the studio off its machines: its core block entity returns `false` from `studioEditable()` (for a machine that draws itself in its own code). A pack can do the same for any machine by adding its core block to the block tag `alchyx:studio_locked`.

### Mod effects (from code)

A mod can let players put its own coded effects at anchors (a rift portal, a sigil...). Register the id on both sides, and draw it on the client as a coded figure:

```java
StudioEffects.register(Identifier.fromNamespaceAndPath("alchyrift", "portal"), "Rift portal"); // common (mod constructor)
Figures.register(Identifier.fromNamespaceAndPath("alchyrift", "portal"), (context, pose, collector, camera) -> { ... }); // client setup
```

The studio lists it under **Mod**, previews it, and the machine plays it as a `figure` effect (`"figure": "alchyrift:portal"`) at the anchor, turned by the anchor's turn, through its time on the timeline. The context gives the time, whether the machine works, the light and the yaw.

### Animations

Animation files work on free-build machines too. Block selectors (`"targets": {"block": "minecraft:piston"}`) find the matching blocks in each player's own build, so one file animates every version of the machine. Anchors don't exist on a recorded shape, so use `core`, offsets from it, or block selectors.

### From code

```java
FreeBuild rules = core.freeBuild();             // null for a fixed-shape machine
FreeBuild studio = core.studioRules();          // what the studio allows here (fixed shape: FreeBuild.STUDIO), or null
core.studioEditable();                          // override: false keeps the studio and free building off this machine
core.freeBuildUnlocked();                       // a fixed-shape machine completed once: freeBuild() is FreeBuild.STUDIO
MultiblockPattern shape = core.recordedShape(); // null until a player records one
String problem = core.recordShape(cornerA, cornerB); // null when recorded (server side)
core.forgetShape();                             // also clears the motions
core.motions();                                 // offset from the core -> Motion
core.changeMotion(pos, false);                  // next allowed motion for that part (true: none)
```

`pattern()` returns the recorded shape once there is one, so everything built on it (checks, parts, previews, the Structure Builder) follows the player's build. `filePattern()` is always the file.

Motions only work on parts (`formed: same`), since the block itself is hidden while its moving copy is drawn. They are played by the same engine as animation files, as `block_move` effects with `"hide": true`.
