# Troubleshooting

## My pattern does not load

Look in the log for the line `AlchyX: loaded N multiblock pattern(s): [...]`. If your id is missing, an error just above it names the file and the reason. The common ones:

| Message | Fix |
|---|---|
| `"core" (the machine's core block id) is missing` | add the top level `core` field |
| `the grid must contain exactly one core ("core": true), found 0` | one key entry needs `"core": true`, and its character must be in the grid once |
| `"layers" has 3 layers, expected 4` (or a row length message) | every layer needs the same rows, and every row the same length. `result` must match `layers` |
| `each key entry needs exactly one of block, tag, air, core or any` | an entry has none of them, or two |
| `each result_key entry needs exactly one of block, same or keep` | same for results |
| `block minecraft:x has no property "y"` | a `state` name the block does not have |
| `the result at the core position must be "keep"` | the core's cell in `result` must use a `keep` entry |
| `"state" only works with block or tag` | `state` on an `air`, `any` or `core` entry |
| `restore only works with same` | `restore` needs `same` |
| `real only works with block (and not with hidden)` | `real` is for a look block that must be the real thing |
| an unknown block id | the block's mod is not loaded, or a typo |

`/reload` reloads patterns without restarting.

If a machine's pattern is missing or broken, a machine that is already formed is **not** broken by it. Its problem reads `no valid mymod:kiln pattern loaded (see the log)` until the file is fixed.

## The machine does not form

Right-click the core. The action bar (or your screen's preview) says what is wrong, for example `a lit campfire (under the middle) needed at 10 64 -3 (layer 1)`. Give your key entries a `name` so this message makes sense to players.

- **Wrong way round.** For a `FacingCoreBlock`, the pattern is turned so its `front` matches the core's facing. If the structure only forms when built mirrored or turned, `front` is probably on the wrong side. It should be the side of the grid where the core's face is.
- **Block states.** `{"lit": true}` on a campfire means an unlit one does not count. Use `"3|2|1"` style values to accept several.
- **Stairs.** `face_center`, `roof_edge` and `roof_corner` check orientation strictly. Build it with the Structure Builder once to see what the pattern wants.
- **Layers upside down.** The first layer is the bottom one.
- **Rows mirrored.** The first row is north, and each row goes west to east.

## It forms, then breaks at once

Something your machine does changes a block of its own structure (a crop growing in a slot that asks for a specific block, a cauldron level dropping below what the key allows). Either loosen the key (`any`, `air`, a state with several values) or, for a machine that reshapes itself on purpose, return true from `holdsStructure()` while it does.

## Pipes do not connect

Pipes reach only what `getItemHandler()`, `getFluidHandler()` and `getEnergyHandler()` return, and only while the machine is formed. If you swap a handler object at runtime, pipes keep the old one until the machine reforms. Keep one handler for the life of the block entity.

## The preview button is missing

Your menu must implement `CoreMenu` and return the core's position on the client too (read it from the extra data in the client constructor). The button only shows while the machine is not formed and `previewPatternId()` is not null.

## The animation does not play

- The file's `machine` (or its own id) must be the **pattern** id, not the core block id.
- Effects only run while the machine is formed, for players within 64 blocks.
- `working`, `start` and `finish` effects need your code to call `setAnimationWorking`, `startAnimationOperation` and `finishAnimationOperation`.
- An item effect needs `setAnimationDisplay(name, stack)` with the same name.
- Run `/reload` after editing, or rejoin.

## Asking for help

Open an issue on the [AlchyX repository](https://github.com/JustAlex14/AlchyX/issues) with the log lines around `AlchyX: loaded`, your pattern file, and what the action bar says when you right-click the core.
