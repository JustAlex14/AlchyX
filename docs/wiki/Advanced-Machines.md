# Advanced Machines

These are the bigger building blocks AlchyX has collected from its mods. Each one was first written for one mod and moved into the library so the others could share it. Each section names the mod that uses it, so you can read a full example.

## Machines that grow tiers

`TieredCoreBlockEntity` (used by every multiblock of AlchyForbidden through its `GrowingCoreBlockEntity`).

The player builds tier 1 by hand and it forms as usual. Every later tier grows by itself once the core has been given what that tier asks for: old parts the new tier has no place for fly up into a source and vanish, then the new parts fly out of the source down to their places, pillars rise out of the ground, and the core rises with its platform if the tier puts it higher. A broken structure keeps the earned tier, so rebuilding tier 1 grows the rest back for free. The core's item keeps the tier too.

Required:

```java
@Override
protected Identifier patternOf(int tier) {
    return Identifier.fromNamespaceAndPath(MyMod.MODID, "altar_" + tier); // one pattern per tier, all in the same box
}

@Override
protected List<TierCost> tierCosts(int tier) {
    return List.of(new TierCost(Items.ECHO_SHARD, 8));
}
```

Useful overrides (all have plain defaults): `maxTier()`, `rise(tier)`, `pillar(look)`, `platform(pos)`, `growsInPlace(look)`, `growthSource()`, `growthStyle()`, `gatherTicks()`, `ticksPerStep()`, `blocksPerStep()`, `eats(state)` (ground it may replace), `ownLook(look)`, `canGrow()`, `extraPaid(tier)` (something more than items, a kill beside it), and the themed hooks `onGatherStart`, `onPartEmerging`, `onAwakened`, `clientGrowing`, `growthText`. Put the machine's normal work in `tickWorking()`.

On the client, give your flights their own particles:

```java
GrowthParts.registerStyle(Identifier.fromNamespaceAndPath(MyMod.MODID, "rift"),
        new GrowthParts.Style(() -> ParticleTypes.SCULK_SOUL, (level, pos) -> { /* puff where a part lands */ }));
```

and return that id from `growthStyle()`.

## Worker cores

`WorkerCoreBlockEntity` (used by every workstation of AlchyFarm).

A core worked by one figure that goes on errands over a ground of squares: a farmhand in a field, a miner in a shaft. The server decides the errand, sends a few numbers, and the client draws the figure walking, facing the target and working from those numbers alone. Nothing more is sent while it moves.

You implement:

| Method | What it does |
|---|---|
| `findWork(ServerLevel, long now)` | look for something to do and call `startErrand(...)` |
| `first(ServerLevel)`, `second(ServerLevel)` | the two moments of the work (sow, then water) |
| `firstAt()`, `secondAt()` | when those moments happen, as shares of the work's length |

Set the ground in the `field` list (squares counted from the core). `runMoments` calls each moment once when its time comes. Save the errand with `saveWork` and `loadWork`. Helpers: `world(square)`, `square(pos)`, `middle()`, `nearest(x, z)`, `besides(square)`, `byThePost()`.

## Fenced fields and other free shapes

`com.lealex.alchyx.multiblock.Enclosure` (used by AlchyFarm for fields of any shape).

Some structures cannot be written as a grid: a fence around a field of any shape, a wall around a yard. `Enclosure.find(level, core, joins, reach, most)` follows a line of blocks from the core and returns the line and the ground it closes in.

Such a machine keeps a one-block pattern for its core (so the rest of AlchyX still works), returns the search reach from `watchReach()` so block changes nearby ask it for a check, and searches again only then. Override `requestStructureCheck(BlockPos changed)` to skip changes that cannot matter (a crop growing inside the field).

## Deaths near a machine

`com.lealex.alchyx.multiblock.DeathWatch` (used by AlchyForbidden's Killing Pit, Lament Well and Echo Resonator).

Return a distance from `deathReach()` and the machine is told of every living death within that many blocks while it is loaded and formed:

```java
@Override public int deathReach() { return 8; }

@Override
public void onDeathNearby(LivingEntity dead, DamageSource source) {
    essence += 1;
}

@Override
public boolean takeDropsNearby(LivingEntity dead, Collection<ItemEntity> drops) {
    // return true to keep the drops (store them, void them); the next core will not see them
    return false;
}
```

Only cores that ask are registered, so machines without it cost nothing.

## Choice screens

`KindChoice`, `ChoiceMenu` and `ChoiceScreen` (used by AlchyFarm's workstations).

A ready-made screen for a machine that offers kinds to turn on or off: a grid of pictures nine to a row, a search box, a filter (all, on, off), an optional dial with four buttons, notes under the grid, and the odds of each kind under the mouse.

1. The block entity implements `KindChoice`: `offered()`, `chosen(item)`, `toggle(index)`, `chosenLabel()`, `notes()`, and optionally `warning`, `given`, `odds`, `dial`, `turnDial`, `button`. Keep the choices synced: the screen reads the client's copy.
2. Register your own menu type: `IMenuTypeExtension.create((id, inventory, data) -> new ChoiceMenu(MY_TYPE.get(), id, inventory, data))`. Open it with the block entity's position as extra data.
3. Extend `ChoiceScreen` to give it your `theme()` (a `WindowTheme`), and optionally `icon()`, `problem()`, `details(...)` and a side panel (`drawSide`, `clickSide`). Register it for your menu type.

## Linking tools

`LinkingTool` and `client/LinkLines` (used by AlchyFarm's Surveyor's Cord).

The shared part of a tool that links one block to another: it holds a block in its custom data, glows while it holds one, says where in its tooltip, and lets go on sneak-use in the air. `held(stack)`, `hold(stack, pos)`, `letGo(stack)`, `note(stack, name, pos)` and `noted(stack, name)` manage what it holds. What a use on a block does is your own `useOn`.

On the client, call `LinkLines.inHand(player, MyTool.class)` and `LinkLines.line(level, from, to, mote, most, every)` from your client tick to draw lines of motes between linked blocks.

`com.lealex.alchyx.link.Links` has the rule every Alchy mod follows for stored links: a linked place is gone only when its chunk is loaded and what stands there is wrong. An unloaded place is never judged gone, so leaving an area never cuts links. Use `Links.gone(level, pos, wanted)` and `Links.goneEntity(...)`.

## Rifted items

`com.lealex.alchyx.rift.RiftMark` and `RiftedIngredient` (made by AlchyFarm).

A rifted item is any item marked with a level from 1 to 6 in its custom data. There is no new item and no new texture: a mod changes the item's components as it likes (more food, a glint) and marks it.

```java
RiftMark.mark(stack, 3);
int level = RiftMark.levelOf(stack);   // 0 when not rifted
```

Recipes can ask for any rifted item of a level or more:

```json
{"neoforge:ingredient_type": "alchyx:rifted", "level": 3}
```

A furnace normally loses the mark. A mixin asks `RiftMark.cooked(input, result)` instead, and mods that make rifted things say what a rifted input cooks into with `RiftMark.onCooked(...)`. Give recipe viewers better samples with `RiftedIngredient.samples`.

## Miniatures

`miniature/BlockBox`, `client/miniature/BoxMesh`, `client/miniature/Miniatures` and `client/animation/MiniatureAnimation` (used by AlchyMastery's nexuses and AlchyRift's looking-in view).

A `BlockBox` is a box of blocks to show in small, captured from the world on the server (`BlockBox.capture`) or built from a list (`BlockBox.of`), sent in a compact form and drawn on the client through a `BoxMesh`. `MiniatureAnimation` plays a machine's own animation file inside a miniature, so you do not write a second animation. See the end of [Animations](Animations).

## Coded figures

Animation files can place a figure a mod draws in code (`"figure": "mymod:ghost"`). Register it during client setup with `Figures.register(id, (context, poseStack, collector, camera) -> ...)`. A figure does not have to be a body: anything your block draws with its own code (a book, an orb) can be one, and then miniatures show it too.

## Sharing a common config with clients

`com.lealex.alchyx.config.CommonConfigSync.share(modContainer)` sends your COMMON config to players when they join, so screens and tooltips show the numbers the server plays by. Call it after registering the config. The client keeps it in memory and never writes it to its own file.
