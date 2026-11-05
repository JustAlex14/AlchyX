# Getting Started

This page sets up a NeoForge mod project so it can use AlchyX. It assumes you already have a working NeoForge 26.1.2 mod built with ModDevGradle and Java 25.

## 1. Get AlchyX into your build

AlchyX is not on a public Maven yet. There are two ways to compile against it.

### Option A: build it next to your mod (recommended)

This is how the Alchy mods themselves are set up. Clone AlchyX into a folder next to your project:

```
workspace/
  AlchyX/
  MyMod/
```

In `MyMod/settings.gradle`, after the `plugins` block:

```groovy
// Build AlchyX from ../AlchyX and use it for "com.lealex:alchyx"
if (file('../AlchyX').exists()) {
    includeBuild('../AlchyX')
}
```

Gradle then builds AlchyX from source whenever you build your mod, and you can edit both at the same time.

### Option B: publish it to your local Maven

In the AlchyX folder:

```
./gradlew publishToMavenLocal
```

and make sure `mavenLocal()` is in the `repositories` of your `build.gradle`.

## 2. Add the dependency

In `gradle.properties`:

```properties
alchyx_version=0.3.1
```

In `build.gradle`:

```groovy
dependencies {
    implementation "com.lealex:alchyx:${alchyx_version}"
}
```

`implementation` puts AlchyX on the compile classpath and loads it in `runClient` and `runServer`.

If you want the version available in your mod metadata template, add it to the properties that `generateModMetadata` expands:

```groovy
var replaceProperties = [
        // ... the usual ones
        alchyx_version: alchyx_version
]
```

## 3. Declare the dependency in neoforge.mods.toml

In `src/main/templates/META-INF/neoforge.mods.toml`:

```toml
[[dependencies.${mod_id}]]
    modId="alchyx"
    type="required"
    versionRange="[${alchyx_version},)"
    ordering="AFTER"
    side="BOTH"
```

AlchyX must be installed on both the server and the client. The client draws the shells, the forming wave, the previews and the animations.

## 4. Optional mods

AlchyX works with these mods when they are present, and needs none of them:

| Mod | What AlchyX adds |
|---|---|
| Patchouli | every structure becomes a book multiblock, chapters that unlock on reading, paper per chapter |
| JEI | a recipe page for each KubeJS machine |
| Jade | machine info on parts and cores |
| KubeJS | machine cores, recipes and events from scripts |

## Package overview

| Package | What is in it |
|---|---|
| `com.lealex.alchyx.block` | `MultiblockCoreBlock`, `FacingCoreBlock`, the chamber shell block |
| `com.lealex.alchyx.block.entity` | `MultiblockCoreBlockEntity` and the specialised bases (`TieredCoreBlockEntity`, `WorkerCoreBlockEntity`) |
| `com.lealex.alchyx.multiblock` | pattern loading (`MultiblockPatterns`), `MultiblockPattern`, `CoreTracker`, `Enclosure`, `DeathWatch` |
| `com.lealex.alchyx.menu` | `CoreMenu`, `UpgradeMenu`, `UpgradeSlot`, `ChoiceMenu` |
| `com.lealex.alchyx.upgrade` | `UpgradeType`, `UpgradeTypes`, `UpgradeItem`, `UpgradeStorage` |
| `com.lealex.alchyx.animation` | the animation files and their engine |
| `com.lealex.alchyx.client` | screens, previews, look meters, miniatures, growth flights |
| `com.lealex.alchyx.rift` | rifted items and the `alchyx:rifted` ingredient |
| `com.lealex.alchyx` | `AlchyXApi` (a few global hooks) and the configs |

Next: [Your First Multiblock](Your-First-Multiblock).
