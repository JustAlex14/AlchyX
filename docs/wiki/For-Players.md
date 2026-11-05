# For Players

You do not need to read this to play, but it explains what AlchyX does in any mod that uses it.

## Building a machine

Every AlchyX machine has a core block. You place the core, then build the structure around it out of ordinary blocks. The mod that adds the machine shows you the shape, usually in its guide book.

When the last block is right, the machine forms. A wave runs through the structure from the core outwards, and each block turns into a machine part. Parts often change their look as they turn (cobblestone into dark prismarine, for example). From then on:

- Right-clicking the core or any part opens the machine.
- Hoppers, pipes and cables can connect to any part. They reach the core's items, fluids and energy.
- Breaking any part breaks the machine. The parts vanish without dropping anything. The core keeps its contents.

A core that faces you when you place it (like a furnace) turns the whole structure with it, so you can build the machine in any of the four directions.

## The structure preview

Right-click a core that is not formed yet. If the machine has a screen, it opens with a **Show structure** button above it. If it has no screen, the click shows the preview directly and sneak-right-click hides it.

The preview draws small ghost blocks where something is missing and red dust on blocks that are wrong. It turns itself off once the machine forms.

Looking at a formed machine also shows small bars next to the crosshair with what it holds (energy, fluids, progress), for the machines that list them.

## The Structure Builder

The Structure Builder (in the AlchyX creative tab) builds a machine for you when you right-click its core.

- In creative it places everything for free.
- In survival it takes the blocks from your inventory. For a slot that accepts several blocks it uses whichever one you carry. It places nothing until you have everything, and lists what is missing instead. It keeps blocks that are already right and turns blocks that face the wrong way, but it never breaks anything in the way.

## Upgrades and corruption

Some machines take upgrades. They go in a tab on the right side of the machine's screen, one slot per kind. Pushing a machine with upgrades corrupts it: veins in the colours of the installed upgrades creep over the structure, and some machines start to glitch. Breaking a core keeps its upgrades on the dropped item.

## Rifted items

Some mods mark items as rifted, from level 1 to 6. A rifted item is an ordinary item with a level on it. Recipes can ask for rifted items of a certain level, and cooking a rifted item in a furnace keeps it rifted.

## Settings

`config/alchyx-client.toml`:

- `effects` and `particles` under `formationWave`: whether the forming wave plays its effect, and how many particles it uses (0 to 4).
- `amount` under `particles`: `FULL`, `REDUCED` (about 40 percent) or `MINIMAL` (about 15 percent) for the streams, links, rituals and idle motes of every AlchyX mod.

`config/alchyx-common.toml`:

- `machine` under `messages`: where machines tell you what they do (forming, formed, broken, a tier grown). `ACTION_BAR` (default), `CHAT` or `OFF`.
