# Your First Multiblock

This page builds a complete small machine in Java: a **Kiln** that bakes clay balls into bricks. It is a 3 x 3 x 3 box of bricks around a furnace-like core, with a campfire under the middle. When it forms, the bricks turn into nether bricks and the campfire stays a real, lit campfire.

You will write five things:

1. the core block
2. the core block entity
3. the registration
4. the structure file
5. a loot table, a name and a model

Replace `mymod` with your mod id everywhere. Everything here follows the code of AlchyMastery's machines, so you can look there for bigger examples (`CondensatorCoreBlock`, `DestructurationCoreBlockEntity`).

## 1. The core block

Every machine has one core block. Extend `FacingCoreBlock` if the core should face the player who places it (like a furnace) and the structure should turn with it. Extend `MultiblockCoreBlock` if it should not turn.

```java
package com.example.mymod.block;

import com.example.mymod.block.entity.KilnCoreBlockEntity;
import com.lealex.alchyx.block.FacingCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class KilnCoreBlock extends FacingCoreBlock {
    public KilnCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KilnCoreBlockEntity(pos, state);
    }
}
```

The base class already does the rest:

- it ticks your block entity on the server (`serverTick`) and on the client (`clientTick`)
- right-click calls your block entity's `openGui(player)`
- a bucket on a machine with tanks fills or empties them
- a Structure Builder (or any item that implements `MachineTool`) acts on the core instead of opening it

## 2. The core block entity

Extend `MultiblockCoreBlockEntity`. Two methods are required: `patternId()` (which structure file this machine uses) and `machineName()` (the name used in chat and action bar messages). Your machine's own work goes in `tickFormed()`, which only runs while the structure is formed.

```java
package com.example.mymod.block.entity;

import com.example.mymod.MyMod;
import com.example.mymod.registry.ModRegistries;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

public class KilnCoreBlockEntity extends MultiblockCoreBlockEntity {
    public static final int INPUT = 0, OUTPUT = 1;
    public static final int BAKE_TICKS = 40; // 2 seconds per brick

    private int progress = 0;

    // Slot 0: clay balls in. Slot 1: bricks out. Pipes and hoppers may only insert clay.
    private final ItemStacksResourceHandler inventory = new ItemStacksResourceHandler(2) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return index == INPUT && resource.is(Items.CLAY_BALL);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
            // The animation file draws this item floating over the campfire
            setAnimationDisplay("input", getResource(INPUT).toStack(getAmountAsInt(INPUT)));
        }
    };

    public KilnCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.KILN_CORE_BE.get(), pos, state);
    }

    @Override
    public Identifier patternId() {
        // data/mymod/multiblock/kiln.json
        return Identifier.fromNamespaceAndPath(MyMod.MODID, "kiln");
    }

    @Override
    protected String machineName() {
        return "Kiln";
    }

    // Every shell of the formed structure gives pipes and hoppers this handler
    @Override
    public ResourceHandler<ItemResource> getItemHandler() {
        return inventory;
    }

    @Override
    protected void tickFormed() {
        int clay = inventory.getAmountAsInt(INPUT);
        int bricks = inventory.getAmountAsInt(OUTPUT);
        boolean canWork = clay > 0 && bricks < 64;
        setAnimationWorking(canWork); // "when": "working" in the animation file

        if (!canWork) {
            progress = 0;
            return;
        }
        if (progress == 0) startAnimationOperation(BAKE_TICKS); // "when": "start"
        if (++progress < BAKE_TICKS) return;

        progress = 0;
        inventory.set(INPUT, clay > 1 ? ItemResource.of(Items.CLAY_BALL) : ItemResource.EMPTY, clay - 1);
        inventory.set(OUTPUT, ItemResource.of(Items.BRICK), bricks + 1);
        finishAnimationOperation(); // "when": "finish"
    }

    @Override
    protected void onStructureChanged(boolean nowFormed) {
        super.onStructureChanged(nowFormed);
        if (!nowFormed) {
            progress = 0;
            setAnimationWorking(false);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        inventory.serialize(output.child("inventory"));
        output.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        inventory.deserialize(input.childOrEmpty("inventory"));
        progress = input.getIntOr("progress", 0);
    }
}
```

Always call `super` in `saveAdditional` and `loadAdditional`. The base class saves whether the machine is formed, its upgrades, its animation state and the list of real blocks it protects.

The contents of a broken core do not drop by themselves. If you want them to, override `preRemoveSideEffects` like AlchyMastery does:

```java
@Override
public void preRemoveSideEffects(BlockPos pos, BlockState state) {
    super.preRemoveSideEffects(pos, state); // breaks the structure, handles upgrades
    if (level == null || level.isClientSide()) return;
    for (ItemStack stack : inventory.copyToList()) {
        if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
    }
}
```

## 3. Registration

Register the block, its item and its block entity type the usual NeoForge way:

```java
public static final DeferredBlock<KilnCoreBlock> KILN_CORE = BLOCKS.registerBlock("kiln_core",
        KilnCoreBlock::new,
        p -> p.mapColor(MapColor.COLOR_RED).strength(3.0f, 6.0f).requiresCorrectToolForDrops());

public static final DeferredItem<BlockItem> KILN_CORE_ITEM =
        ITEMS.registerSimpleBlockItem("kiln_core", KILN_CORE);

public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KilnCoreBlockEntity>> KILN_CORE_BE =
        BLOCK_ENTITY_TYPES.register("kiln_core",
                () -> new BlockEntityType<>(KilnCoreBlockEntity::new, false, KILN_CORE.get()));
```

Nothing has to be registered with AlchyX itself. The library finds your machine through the structure file (its `core` field) and through your block entity, which adds itself to the core tracker when it loads.

It helps to add AlchyX's Structure Builder to your creative tab, so testers can build your machine in one click:

```java
output.accept(com.lealex.alchyx.registry.AlchyXRegistries.STRUCTURE_BUILDER.get());
```

## 4. The structure file

Create `src/main/resources/data/mymod/multiblock/kiln.json`. The file name is the pattern id your block entity returned (`mymod:kiln`).

```json
{
  "core": "mymod:kiln_core",
  "front": "north",
  "anchors": {"fire": [0, -1, 1]},
  "layers": [
    ["BBB", "BCB", "BBB"],
    ["BXB", "B.B", "BBB"],
    ["SSS", "SSS", "SSS"]
  ],
  "key": {
    "B": {"block": "minecraft:bricks", "name": "bricks"},
    "C": {"block": "minecraft:campfire", "state": {"lit": true}, "name": "a lit campfire (under the middle)"},
    "S": {"block": "minecraft:brick_slab", "state": {"type": "bottom"}, "name": "a brick slab (bottom half)"},
    "X": {"core": true},
    ".": {"air": true}
  },
  "result": [
    ["bbb", "bkb", "bbb"],
    ["bkb", "b.b", "bbb"],
    ["sss", "sss", "sss"]
  ],
  "result_key": {
    "b": {"block": "minecraft:nether_bricks"},
    "s": {"block": "minecraft:nether_brick_slab", "copy_state": true},
    "k": {"keep": true},
    ".": {"keep": true}
  }
}
```

How to read it:

- `layers` go from the bottom layer to the top layer. Each layer is a map seen from above: the first string is the north row, and each string goes from west to east.
- Every character must be in `key`. Exactly one must be the core (`"core": true`). All positions are measured from the core, so the shape does not need to be centred on it.
- `front` says which side the grid's front is on as written. Here the core sits on the north row, so the front is north. When the player places the core facing east, the whole pattern turns so its front is east.
- `result` has the same size as `layers` and says what each block becomes once formed. `keep` leaves the block as the player placed it. The core's own cell must be `keep`. The campfire is also kept, so it stays a real campfire with its smoke and flames.
- `anchors` names spots for animations and for your code (`pattern().anchor(...)`). Here `fire` is one block below and one block south of the core, which is the campfire.

Every field is described in [Pattern Format](Pattern-Format).

Patterns are datapack files: `/reload` reloads them, and a broken file is logged with the reason and skipped (look for `AlchyX: loaded N multiblock pattern(s)` in the log).

## 5. Loot table, name and model

The core is an ordinary block, so it needs what any block needs.

`data/mymod/loot_table/blocks/kiln_core.json`:

```json
{
  "type": "minecraft:block",
  "pools": [{
    "rolls": 1,
    "entries": [{"type": "minecraft:item", "name": "mymod:kiln_core"}],
    "conditions": [{"condition": "minecraft:survives_explosion"}]
  }]
}
```

`assets/mymod/lang/en_us.json`:

```json
{
  "block.mymod.kiln_core": "Kiln Core"
}
```

and a blockstate, block model and item model like any `FACING` block (copy a furnace's and point it to your textures).

## 6. Try it

1. Start the game with `runClient`, make a creative world.
2. Place the Kiln Core, then right-click it with the Structure Builder. The structure is built around it, and a moment later the wave turns it into nether bricks.
3. Throw clay balls in with a hopper on any part. Take the bricks out with a hopper under any part.
4. Break one brick: the structure breaks, the parts vanish, and the campfire and core stay.

Right-clicking the core now only shows its state in the action bar (`Kiln: formed`). To give it a real screen, see [Inventories and GUIs](Inventories-and-GUIs).

## 7. Add an animation (optional)

`data/mymod/machine_animation/kiln.json` (the file id matches the pattern id, so no `machine` field is needed):

```json
{
  "effects": [
    {"type": "item", "display": "input", "at": {"anchor": "fire", "offset": [0, 0.9, 0]},
     "motion": "spin", "speed": 1, "working_speed": 4, "scale": 0.4},
    {"type": "particle", "when": "working", "interval": 10, "particle": "minecraft:flame",
     "at": "fire", "spread": [0.25, 0.3, 0.25], "count": 2, "speed": 0.01},
    {"type": "sound", "when": "finish", "sound": "minecraft:block.fire.extinguish", "volume": 0.3, "pitch": 1.4}
  ]
}
```

See [Animations](Animations) for everything a file can do.

## What you got for free

Without any more code, your Kiln:

- checks its structure when a block nearby changes, and every 2 seconds as a safety
- tells nearby players what is missing (`... needed at x y z`) and when it forms or breaks
- forms with the wave, and can be built facing any of the four directions
- breaks as soon as any part is broken, with parts vanishing and no duplication
- gives pipes, hoppers and cables access through every part
- shows a preview of missing blocks
- can be built by the Structure Builder in creative and in survival
- appears as a Patchouli multiblock under `mymod:kiln` if Patchouli is installed (see [Guide Books](Guide-Books))
