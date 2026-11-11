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

Sneak + use on a block stops it at once. Some machines allow only some motions, or none.

### The Machine Studio

Once the machine stands, use the Recorder on its **core** with no corners marked: the Machine Studio opens. It shows the machine in 3D, like a page of a guide book, but you can change it:

- **Drag** to turn the view, use the **wheel** to zoom, and use the **- / +** layer buttons to hide the top layers and look inside.
- **Click** a block to choose it. **Shift + click** adds or removes one block. **Ctrl + click** chooses every block of that kind.
- Pick a **motion** on the right. **Play / Pause** (or Space) previews the motions in the view, before anything changes in the world.
- **Save** sends the motions to the machine. **Close** leaves without saving.

The machine must stay formed and within 16 blocks while the screen is open. Only motions the machine allows are offered, and the server checks them again on save.

- **Sneak + use the Recorder on the core**: forget its shape, to rebuild it differently. A formed machine breaks first.
- **Sneak + use the Recorder in the air**: forget the corners.
- Chests, barrels and other blocks that hold things stay real blocks, so they keep what is in them. So do liquids.
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
| `motions` | all | the motions players may give the blocks: `spin`, `bob`, `strike`, `swing`, `shake`. `[]`: none |
| `motion_when` | `working` | when the motions play: `always`, `idle` or `working` |

Blocks that can never be used: a part of another machine, another machine's core, and blocks nothing can break (bedrock, barriers).

### Keep the machine fair

The shape is the player's, so the machine's strength should come from what you require and from its tier, never from the shape. A fancy build should be neither better nor worse than a plain one with the same required blocks.

### What gets recorded

Every block in the box is recorded exactly, turned the way it stands (facing, axis, slab type, upper or lower half). State that changes by itself, like a crop's age or a stair's corner shape, is not checked. Air is recorded as "anything": a torch placed later never breaks the machine.

### Animations

Animation files work on free-build machines too. Block selectors (`"targets": {"block": "minecraft:piston"}`) find the matching blocks in each player's own build, so one file animates every version of the machine. Anchors don't exist on a recorded shape, so use `core`, offsets from it, or block selectors.

### From code

```java
FreeBuild rules = core.freeBuild();             // null for a fixed-shape machine
MultiblockPattern shape = core.recordedShape(); // null until a player records one
String problem = core.recordShape(cornerA, cornerB); // null when recorded (server side)
core.forgetShape();                             // also clears the motions
core.motions();                                 // offset from the core -> Motion
core.changeMotion(pos, false);                  // next allowed motion for that part (true: none)
```

`pattern()` returns the recorded shape once there is one, so everything built on it (checks, parts, previews, the Structure Builder) follows the player's build. `filePattern()` is always the file.

Motions only work on parts (`formed: same`), since the block itself is hidden while its moving copy is drawn. They are played by the same engine as animation files, as `block_move` effects with `"hide": true`.
