# Upgrades

AlchyX has a shared upgrade system. You declare the kinds of upgrade your mod offers, and AlchyX gives each kind an item per tier, a slot in the machine's upgrade tab, a floating display on the structure, and a share of the machine's corruption. What an upgrade actually does is up to your machine's code: you read the installed tier and act on it.

## 1. Register the upgrade types

Register them once, early, in a static field:

```java
public final class KilnUpgrades {
    private static final Identifier EMBLEMS = Identifier.fromNamespaceAndPath(MyMod.MODID, "textures/gui/upgrade_emblems.png");

    public static final UpgradeType SPEED = UpgradeTypes.register(new UpgradeType(
            Identifier.fromNamespaceAndPath(MyMod.MODID, "speed"),
            4,                                              // highest tier
            0x3FD6E0,                                       // colour of its corruption veins (RGB)
            new UpgradeType.Emblem(EMBLEMS, 0, 0),          // 16 x 16 icon at u 0, v 0 of a 256 x 256 texture
            (tier, lines) -> lines.accept(Component.literal("x" + (1 + tier) + " speed"))));

    public static final List<UpgradeType> KILN = List.of(SPEED);
}
```

`UpgradeType(id, maxTier, color, emblem, tooltip)`:

- `maxTier`: 1 for a single item, more for tiers
- `color`: the corruption veins pulse in the colours of the installed upgrades
- `emblem`: shown faintly in the empty slot
- `tooltip`: lines under the item's name, per tier

The name of a type is translated with `upgrade.<namespace>.<path>`, for example `upgrade.mymod.speed`.

## 2. Register an item per tier

```java
public static final DeferredItem<UpgradeItem> SPEED_1 = ITEMS.registerItem("speed_upgrade_1",
        p -> new UpgradeItem(p, KilnUpgrades.SPEED, 1));
// ... up to SPEED_4
```

An `UpgradeItem` only fits its own type's slot, and shows its tooltip lines.

## 3. Give the machine slots

In the core block entity, call `createUpgrades` once, from a field initializer or the constructor:

```java
public KilnCoreBlockEntity(BlockPos pos, BlockState state) {
    super(ModRegistries.KILN_CORE_BE.get(), pos, state);
    createUpgrades(KilnUpgrades.KILN);
}
```

The base class then:

- saves and syncs the upgrades
- keeps them on the core's item when the core is broken (and drops them if no item drops)
- shows each installed upgrade on the structure as the animation display `upgrade_<type path>` (for example `upgrade_speed`)
- publishes the sum of the installed tiers as the animation level `corruption`
- draws corruption veins over the structure on clients

## 4. Read the tier

```java
UpgradeStorage upgrades = getUpgrades();
int speed = upgrades == null ? 0 : upgrades.tier(KilnUpgrades.SPEED); // 0 when none is installed
int ticks = Math.max(5, BAKE_TICKS / (1 + speed));
```

Other methods: `stack(type)`, `corruption()` (sum of tiers), `maxCorruption()`, `installedColors()`.

Override `onUpgradesChanged()` (call `super`) if you need to react when an upgrade goes in or out.

## 5. Add the tab to your menu

See The upgrade tab on [Inventories and GUIs](Inventories-and-GUIs).

## Corruption in animations

Because the base class publishes `corruption`, your animation file can make a pushed machine look unstable without code:

```json
{"type": "block_move", "interval": 45, "chance": 0.6,
 "targets": {"block": "minecraft:nether_bricks"},
 "direction": "up", "align": false,
 "keyframes": [{"t": 0, "offset": [0, 0, 0]}, {"t": 1, "offset": [0.07, 0, -0.05]},
               {"t": 2, "offset": [-0.06, 0.03, 0.06]}, {"t": 3, "offset": [0, 0, 0]}],
 "min_level": {"corruption": 3}}
```

AlchyMastery's `AlchymasteryUpgrades` and `Upgrades` classes are a full example with four types.
