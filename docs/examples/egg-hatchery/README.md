# Egg Hatchery (example)

A KubeJS machine that turns an egg into a random spawn egg (modded ones too), with a Machine Studio animation to
import that uses every studio feature: motions (several on one block), state keyframes, the Shape tab, floating
items, particles, mobs doing things, sounds, and two **mini structures** on its roof, one from a mini file and one
built in the studio. Blaze powder in its heat slot makes it hatch faster, and the animation (minis included) speeds
up with it.

## Files

| File | Goes in |
|---|---|
| `kubejs/startup_scripts/egg_hatchery.js` | the core block (`mypack:egg_hatchery_core`): 2 input slots (egg, heat), 1 output slot |
| `kubejs/data/mypack/multiblock/egg_hatchery.json` | the structure, 5 x 4 x 5 |
| `kubejs/data/mypack/machine_recipe/egg_hatchery.json` | an egg in, 5 seconds |
| `kubejs/data/mypack/machine_mini/tiny_coop.json` | a mini structure: a little chicken coop, with its own animation |
| `kubejs/server_scripts/egg_hatchery.js` | picks the spawn egg as the work starts, shows its mob in the spawner, gives it at the end; sets the speed from the heat slot |
| `alchyx_exports/mypack/machine_animation/egg_hatchery_studio.json` | the animation, for the studio's **Import** |

Copy the `kubejs` folder into the instance's `kubejs` folder, and `alchyx_exports` into the instance's folder (next
to `kubejs`). Restart the game (startup script).

## The structure

Seen from above, layer by layer (bottom to top), north at the top; the core faces you when you place it.

```
floor        layer 2      layer 3      roof
B B B B B    L B X B L    L A G A L    L B B B L
B H H H B    G . . . G    G . . . G    B B B B B
B H C H B    G . . . G    G . . . G    B B S B B
B H H H B    G . . . G    G . . . G    B B B B B
B B B B B    L B P B L    L M K M L    L B B B L
```

`B` polished blackstone bricks, `H` hay bales, `C` an empty cauldron, `L` stripped dark oak logs, `X` the core,
`G` glass, `.` air (the pen inside, where the egg floats and the chicken walks), `P` a piston facing the middle,
`A` barrels facing out, `M` composters, `K` a smoker facing the middle, `S` a spawner. The Structure Builder builds it
for you in creative.

## The animation

Sneak and right-click the formed core with an empty hand: the Machine Studio opens. **Import**, pick
`mypack/machine_animation/egg_hatchery_studio.json`, then **Save**. Over one operation (5 s without heat):

- **Blocks**:
  - The spawner spins and bobs at once (Spin, then Shift + click Bob), from 1 s to 4 s.
  - The piston strikes from 2.25 s to 3.5 s.
  - The cauldron goes through five state keyframes: water level 1 (0.25 s), 2 (1 s), 3 (1.75 s), then powder snow
    (3 s), then empty (4.5 s).
  - The smoker is lit while it works, and the two composters fill up through the work.
  - The four hay bales by the cauldron shake as the egg hatches (2.9 s to 3.4 s), and the barrels open at the end.
- **Shape**: the two middle glass blocks are turned 45 degrees and a little smaller, and the cauldron is smaller and
  raised a little (for the look only: the blocks stay where they are).
- **Items** (one anchor in the middle of the pen, a list of three):
  - the egg in the input spins (0 to 2 s);
  - an egg travels up under the spawner (2 to 3 s);
  - then the spawn egg it will give (the "result" display) bobs (3.5 to 5 s).
- **Particles**:
  - soul fire flames over the spawner while it spins;
  - bubbles in the cauldron while it fills;
  - smoke over the smoker;
  - happy sparks where the egg hatches.
- **Mobs**:
  - a witch in front swings her arm all the time;
  - a chicken walks across the pen until the egg goes up (a "walk to" action);
  - a cat sits by the door;
  - a parrot flaps its wings on the roof;
  - a frog hops on the roof at the end.
- **Mini structures** on the roof, both timed on the hatchery's recipe:
  - the **Tiny coop** (from `machine_mini/tiny_coop.json`): its hay bobs, its chicken flaps, its chimney smokes;
  - the **Little nest**, built in the studio and kept by the machine: a hay bale that shakes under a turtle egg that
    fills up (1 to 4 eggs) through each operation.
- **Sounds**:
  - a chicken laying as the work starts;
  - the cauldron brewing;
  - the piston every second while it strikes;
  - the trial spawner's spawn sound as the egg reaches the spawner;
  - a barrel opening at the end.

Then change anything in the studio and **Save** (that one machine), or **Export** it as a datapack file for every
hatchery (the Little nest goes out as a mini file of its own beside it).

### Mini structures

Click a spot on the **Anchors** tab, then **Mini**:

- **Import a multiblock...**: any multiblock that already exists (and every mini file of the datapacks) stands
  there small, with its own animation. Nothing to build.
- **Build a new mini...**: build one block by block in a small view, give its blocks motions, and press **Play** to
  see it move. The machine keeps it (up to 4).
- **Built here: ...**: change one you built, or delete it.

**Turn** turns it a quarter, and **Size** sets how big one of its blocks is (1/8 to 1/2 block). A mini plays its
animation timed on the machine: one operation of the hatchery plays the mini's whole loop, so with blaze powder in the
heat slot it plays faster too.

## The script

`server_scripts/egg_hatchery.js` uses what a script machine offers for its animation:

- `event.machine.setDisplay('result', item)` in `operationStarting`: what the floating "result" items show for this
  operation (else the recipe's output). `event.machine.getDisplay('result')` reads it back in `operationFinished`.
- `event.machine.setSpawnerMob('spawner', 'minecraft:chicken')`: the mob a spawner of the machine shows turning
  inside it, at one of the pattern's named anchors. `setPartData(anchor, nbt)` / `getPartData(anchor)` do the same
  for any block data (only drawn: the real block gets its own data back when the machine breaks).
- `event.machine.setSpeed(n)` in `tick`: how fast recipes go (the heat: 1 + 0.25 per blaze powder). The animation and
  the minis follow it, even in the middle of an operation.

This file only holds the studio's part (`"effects": []`), so it does nothing in a datapack by itself: Export
writes the full one.
