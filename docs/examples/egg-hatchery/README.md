# Egg Hatchery (example)

A KubeJS machine that turns an egg into a random spawn egg (modded ones too), with a Machine Studio animation to
import that uses every studio feature. The egg is picked as the work starts: the spawner on top shows that mob
turning inside it, the hatching egg floats as that spawn egg near the end, and the same spawn egg comes out.

## Files

| File | Goes in |
|---|---|
| `kubejs/startup_scripts/egg_hatchery.js` | the core block (`mypack:egg_hatchery_core`): 1 input slot, 1 output slot |
| `kubejs/data/mypack/multiblock/egg_hatchery.json` | the structure, 3x3x3 |
| `kubejs/data/mypack/machine_recipe/egg_hatchery.json` | an egg in, 5 seconds |
| `kubejs/server_scripts/egg_hatchery.js` | picks the spawn egg as the work starts, shows its mob in the spawner, gives it at the end (and waits until the last one was taken out) |
| `alchyx_exports/mypack/machine_animation/egg_hatchery_studio.json` | the animation, for the studio's **Import** |

Copy the `kubejs` folder into the instance's `kubejs` folder, and `alchyx_exports` into the instance's folder (next
to `kubejs`). Restart the game (startup script).

## The structure

Seen from above, layer by layer (bottom to top), north at the top; the core faces you when you place it.

```
bottom      middle      top
B B B       B X B       B B B
B C B       G . G       B S B
B B B       B P B       B B B
```

`B` polished blackstone bricks, `C` an empty cauldron, `X` the core, `G` glass, `.` air (where the egg floats),
`P` a piston facing the center, `S` a spawner. The Structure Builder builds it for you in creative.

## The animation

Sneak and right-click the formed core with an empty hand (or use the Structure Recorder on it, no corners marked):
the Machine Studio opens. **Import**, pick
`mypack/machine_animation/egg_hatchery_studio.json`, then **Save**. Over one operation (5 s):

- **Blocks**: the spawner spins from 1 s to 4 s; the piston strikes from 2.25 s to 3.5 s; the cauldron goes through
  five state keyframes: water level 1 (0.25 s), 2 (1 s), 3 (1.75 s), then powder snow (3 s), then empty (4.5 s).
- **Shape**: the two glass blocks are turned 45 degrees and a little smaller, and the cauldron is smaller and raised a
  little (for the look only: the blocks stay where they are).
- **Items** (one anchor at the center, a list of three): the egg in the input spins (0 to 2 s), an egg travels up
  into the spawner (2 to 3 s), then the spawn egg it will give (the "result" display) bobs (3.5 to 5 s).
- **Particles**: soul fire flames over the spawner while it spins, bubbles in the cauldron while it fills.
- **Mobs**: a witch in front of the machine swings her arm all the time; a chicken walks up to her until the egg
  goes up (a "walk to" action); a parrot flaps its wings on top; a frog hops on top at the end.
- **Sounds**: a chicken laying as the work starts, the cauldron brewing, the piston every second while it strikes,
  and the trial spawner's spawn sound as the egg reaches the spawner.

Then change anything in the studio and **Save** (that one machine), or **Export** it as a datapack file for every
hatchery.

## The script

`server_scripts/egg_hatchery.js` uses what a script machine offers for its animation:

- `event.machine.setDisplay('result', item)` in `operationStarting`: what the floating "result" items show for this
  operation (else the recipe's output). `event.machine.getDisplay('result')` reads it back in `operationFinished`.
- `event.machine.setSpawnerMob('spawner', 'minecraft:chicken')`: the mob a spawner of the machine shows turning
  inside it, at one of the pattern's named anchors. `setPartData(anchor, nbt)` / `getPartData(anchor)` do the same
  for any block data (only drawn: the real block gets its own data back when the machine breaks).

This file only holds the studio's part (`"effects": []`), so it does nothing in a datapack by itself: Export
writes the full one.
