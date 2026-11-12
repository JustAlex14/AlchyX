# Core Block Entity

`com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity` is the base class of every machine. It checks the structure, runs the forming wave, breaks the structure, keeps the core tracker up to date, syncs to clients and saves. You extend it and fill in what your machine does.

This page lists what you can override and call. The method names are the real ones from the source.

## Life of a machine

1. The core loads (placed or chunk loaded). It registers itself with the core tracker and asks for a structure check.
2. A block changes near the core, or 40 ticks pass. The structure is checked against the pattern.
3. The structure is complete. The **forming** wave starts: each position converts in turn, nearest first. `tickForming()` runs every tick meanwhile.
4. The wave ends. The machine is **formed**. `onStructureChanged(true)` is called.
5. If `formationSettleTicks()` is above 0, the machine **settles** for that long (for a formation animation of your own). Then `onFormationSettled()` is called, the "formed" message and sound play, and the machine counts as settled.
6. While formed, `tickFormed()` runs every server tick.
7. A part is broken or a block goes wrong. The parts and consumed blocks are removed, and `onStructureChanged(false)` is called.

## Required

| Method | What to return |
|---|---|
| `Identifier patternId()` | the id of the structure file, e.g. `mymod:kiln` for `data/mymod/multiblock/kiln.json` |
| `String machineName()` | the name in messages, e.g. `"Kiln"` |

## Work

| Method | When it runs |
|---|---|
| `void tickFormed()` | every server tick while formed |
| `void tickForming()` | every server tick while the wave runs |
| `void onStructureChanged(boolean nowFormed)` | server, when the structure finishes forming (`true`) or breaks (`false`) |
| `void onFormationSettled()` | server, once formed and done settling |
| `void clientTick()` | every client tick (particles, visual state). Public |

## Automation

Return a handler and AlchyX exposes it on the core and on every part of the formed structure. Return `null` (the default) for none.

| Method | Capability |
|---|---|
| `ResourceHandler<ItemResource> getItemHandler()` | items (hoppers, pipes) |
| `ResourceHandler<FluidResource> getFluidHandler()` | fluids (pipes, and buckets on the core) |
| `EnergyHandler getEnergyHandler()` | Forge Energy |

You decide what pipes may do. AlchyMastery wraps its inventories so pipes can only insert into inputs and only extract from outputs.

## Opening the machine

`void openGui(Player player)` is called on the server when a player right-clicks the core or any part. By default it shows the state in the action bar: `Kiln: formed`, `Kiln: forming...` or `Kiln: not formed: bricks needed at 10 64 -3 (layer 2)`.

To open a menu, implement `MenuProvider` and write the core's position into the menu's extra data:

```java
@Override
public void openGui(Player player) {
    if (player instanceof ServerPlayer serverPlayer) {
        serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
    }
}
```

Opening it before the machine is formed is a good idea: AlchyX then adds the **Show structure** button to the screen. See [Inventories and GUIs](Inventories-and-GUIs).

For a menu's `stillValid`, use `withinReach(player)`. It measures from every block of the structure, not just the core, so the screen does not close at once when a big machine is opened from its far side.

## Animation state

These feed the animation files (see [Animations](Animations)). Each one syncs to clients only when its value changes, so calling them every tick is fine.

| Method | Animation file |
|---|---|
| `setAnimationWorking(boolean)` | `"when": "working"` and `"idle"` |
| `startAnimationOperation(int ticks)` | `"when": "start"`, and the progress used by `converge` items and `block_state` steps |
| `finishAnimationOperation()` | `"when": "finish"` |
| `retimeAnimationOperation(int ticks)` | the operation under way now lasts this long in all (a speed upgrade went in or out): it keeps how far it is, and the rest plays at the new pace, mini structures included |
| `setAnimationDisplay(String name, ItemStack)` | `"display": "<name>"` on item effects and item particles. Empty clears it |
| `setAnimationLevel(String name, int value)` | `"min_level": {"<name>": n}`. Saved and synced. 0 removes it |

Readers: `isAnimationWorking()`, `getAnimationProgress(gameTime)`, `getAnimationOperationTicks()`, `getAnimationDisplay(name)`, `getAnimationLevel(name)`.

Pass the operation's real length to `startAnimationOperation`, speed upgrades included: mini structures standing in the machine play one loop per operation, so they speed up with it. `ownMinis()` lists the minis players built for this machine in the studio.

## State

| Method | Meaning |
|---|---|
| `isFormed()` | formed (the wave is done) |
| `isForming()` | the wave is running |
| `isSettled()` | formed and done settling |
| `getStructureProblem()` | the last problem found, or null |
| `getFormedAt()` | game time the machine last finished forming, saved and synced |
| `pattern()` | the loaded pattern, or null |
| `rotation()` | how the pattern is turned for this core |
| `structureContains(pos)` | true if pos is one of the structure's blocks (a block, tag or core cell, not air or any) |
| `protects(pos)` | true if pos is one of this formed machine's real blocks (kept campfires, cauldrons, candles) |

## Talking to players and clients

| Method | Use |
|---|---|
| `notifyNearbyPlayers(String)` / `(Component)` | tells nearby players something about the machine, where the server config says (action bar by default) |
| `playSound(pos, sound, volume, pitch)` | a sound at a position |
| `syncToClients()` | send this block entity's data to nearby players once, at the end of the tick |
| `requestStructureCheck()` | ask for a structure check on the next tick |

## Optional behaviour

Override these to change how the machine behaves. All defaults are in brackets.

| Method | What it changes |
|---|---|
| `boolean quiet()` [false] | true: no "forming", "formed" and "broken" messages or sounds from AlchyX. For a machine that announces itself its own way |
| `ParticleOptions waveParticle()` [reverse portal] | the puff each block gives as it converts |
| `int formationSettleTicks()` [0] | ticks spent settling after the wave, for a formation animation |
| `boolean veilsWave()` [false] | true: converted blocks stay invisible on clients until your own formation animation reveals them |
| `boolean veilPuffs()` [true] | whether veiled blocks puff a few motes as they hide |
| `Identifier previewPatternId()` [the pattern, while not formed] | the pattern the structure preview shows. A machine that grows can return its next tier while formed. Null for no preview |
| `boolean holdsStructure()` [false] | true while the machine reshapes itself on purpose: structure checks wait, so a part it removes does not break it |
| `int watchReach()` [0] | for a machine whose structure is searched for and not in its pattern: how far along x and z a block change must also ask it for a check |
| `void requestStructureCheck(BlockPos changed)` | override to ignore changes that cannot matter (your own work inside a field) |
| `int deathReach()` [0] | how far deaths are reported to this machine, see [Advanced Machines](Advanced-Machines) |
| `void lookMeters(List<LookMeter> out)` | bars shown next to the crosshair while a player looks at the machine, see below |

## Look meters

While a player looks at any block of a machine, AlchyX draws the machine's name and a few bars next to the crosshair. Add one bar per thing it holds. This runs on the client, so use values your block entity syncs.

```java
@Override
public void lookMeters(List<LookMeter> out) {
    out.add(new LookMeter(progress, BAKE_TICKS, 0xFFE07030, Component.literal("baking")));
}
```

`LookMeter(long amount, long cap, int argb, Component label)`. The label can be null.

## Keeping state on the item

When a core is broken, its item can carry its state, and the state comes back when the core is placed again. By default this happens for machines that take upgrades: the upgrades go on the item instead of dropping.

| Method | Use |
|---|---|
| `boolean keepsStateOnItem()` | [true when the machine takes upgrades] |
| `void keepOnItem(CompoundTag tag)` | write what else the item keeps. Keys starting with `alchyx_` are reserved |
| `void restoreFromItem(CompoundTag tag)` | read it back when the core is placed |

This works for every machine without changing loot tables.

## Upgrades

`createUpgrades(List<UpgradeType>)` gives the machine one upgrade slot per type. See [Upgrades](Upgrades).

## Saving

Override `saveAdditional(ValueOutput)` and `loadAdditional(ValueInput)` and call `super` first. The base class saves the formed state, upgrades, animation levels and protected positions. Your data is sent to clients with the update tag, so what you save is what the client sees after `syncToClients()`.

## The core block

`MultiblockCoreBlock` (and `FacingCoreBlock`, which adds `FACING` and turns the pattern) is the block side. It:

- ticks the block entity on both sides
- calls `openGui` on right-click, and when the clicked core is itself absorbed inside a bigger formed machine, opens that machine instead
- fills and empties tanks with buckets when `getFluidHandler()` is not null
- lets items that implement `MachineTool` act first (the Structure Builder, AlchyMastery's wormhole attuner)

Your block only needs `newBlockEntity`. Add shapes, extra state properties or a renderer as you would on any block.

## Global hooks: AlchyXApi

`com.lealex.alchyx.AlchyXApi` has two hooks for your mod's constructor or common setup:

- `registerLegacyPart(Predicate<BlockState>)`: blocks that count as machine parts besides shells, for example part blocks an older version of your mod placed. Formed structures keep accepting them and convert them on the next check.
- `registerPistonHead(Block pistonBase, Supplier<BlockState> head)`: the head drawn when a part disguised as this piston-like block strikes in an animation. Vanilla pistons are built in.
