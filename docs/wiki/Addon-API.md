# Addon API

Other mods can plug their own animations into AlchyX machines. A Create addon, for example, can make a machine's
shafts turn while it works, or add "Press" to the Machine Studio so players can make any block move like a
mechanical press.

There are two hooks, and most addons need both:

| Hook | What it does | Where |
| --- | --- | --- |
| `StudioMotions` | Adds a motion to the studio's list (a keyframe loop, like a `block_move`) | both sides |
| `PartAnimators` | Lets a block's **own** renderer animate inside a machine (a shaft turning at its speed) | client |

Everything below is plain Java: call it from your mod's constructor (or client setup for client-only hooks). No
mixins, no extra dependency besides AlchyX.

## Studio motions

A studio motion is one loop of keyframes. Players find it in the studio's Blocks tab, next to Spin, Strike and the
others, but only on the blocks it fits. A plain click gives it to the selected blocks. Shift + click adds it to the
motions they already have, so a block can bob **and** press.

```java
import com.lealex.alchyx.animation.Keyframe;
import com.lealex.alchyx.animation.StudioMotions;
import net.minecraft.world.phys.Vec3;

// A press: the head goes down half a block, holds, and comes back up (one 20-tick loop)
StudioMotions.register(
        Identifier.fromNamespaceAndPath("mymod", "press"),
        "Press",
        state -> state.getBlock() instanceof MechanicalPressBlock,   // or List.of(block1, block2)
        "down",                                                     // the way "move" goes
        List.of(
                new Keyframe(0,  0,    Vec3.ZERO, Vec3.ZERO, 1, Map.of()),
                new Keyframe(6,  0.5f, Vec3.ZERO, Vec3.ZERO, 1, Map.of()),
                new Keyframe(12, 0.5f, Vec3.ZERO, Vec3.ZERO, 1, Map.of()),
                new Keyframe(20, 0,    Vec3.ZERO, Vec3.ZERO, 1, Map.of())));
```

A keyframe is `(t, move, offset, rotation, scale, state)`:

- `t`: the tick in the loop.
- `move`: how far the block has gone along the direction.
- `offset`: an extra shift.
- `rotation`: degrees around x, y and z, about the block's middle.
- `scale`: the block's size.
- `state`: block state values from that keyframe on, without blending (`Map.of("powered", "true")`).

These are the same fields as in [animation files](Animations).

The direction is `"facing"` (the way the block faces) or a side (`"up"`, `"north"`...). Either way it turns with
the machine.

**Register on both sides.** The server keeps the motion on the machine, and the clients play it. A motion that isn't
registered (the addon was removed) is simply dropped from the machine the next time it is saved.

A machine only offers addon motions if its free-build rules allow at least one motion (`"motions"` in its
`free_build` file).

### In animation files

The studio's Export writes addon motions like this, and you can write them by hand too:

```json
{ "offset": [0, 2, 0], "motion": "mod", "setup": { "mods": ["mymod:press"] } }
```

`"mods"` also works next to a built-in motion: `"motion": "spin", "setup": { "mods": ["mymod:press"] }` spins **and**
presses. A block can have up to 4 motions in all.

## Part animators

Some blocks animate themselves: a Create shaft turns at its speed, a fan spins, a mixer whisks. Inside a machine these
blocks are shells that draw the block, so by default they stand still. A part animator gives the drawn copy a
block entity each tick, so the block's own renderer plays its own animation. You set the values, for example the
speed, from what the machine is doing.

```java
import com.lealex.alchyx.animation.PartAnimators;

// Client setup: every kinetic block in a machine turns while the machine works
PartAnimators.register(
        state -> state.getBlock() instanceof KineticBlock,
        (puppet, look, machine) -> {
            if (puppet instanceof KineticBlockEntity kinetic) kinetic.setSpeed(machine.working() ? 64 : 0);
        });
```

`machine` tells you:

- `core()`: the machine's core, or null for a moment while it loads.
- `working()`: whether it is running.
- `progress()`: 0 to 1 through the current operation.
- `time()`: the game time, for your own timing.

`register(Block, animator)` is a shortcut for a single block.

An animator that throws is logged and skipped. It never crashes the game.

Part animators and studio motions work together. A shaft can turn with its own renderer **and** bob up and down with
a studio motion.

## Other hooks

- **Coded figures** (client): `Figures.register(id, figure)` adds a figure that animation files use with
  `"figure": "<id>"`. See [Animations](Animations).
- **Piston-like blocks**: `AlchyXApi.registerPistonHead(base, () -> headState)` sets the head drawn when a shell
  disguised as your block uses Strike. Vanilla pistons are built in.
- **Legacy parts**: `AlchyXApi.registerLegacyPart(predicate)` lets formed machines accept blocks an older version of your mod placed as parts, and turns them into shells on the next check.

## Checklist for an addon

1. Server and client: one `StudioMotions.register` per new move.
2. Client: one `PartAnimators.register` per family of blocks that animate themselves.
3. Ship an example machine (a `free_build` file plus an animation file) so players see it right away.
