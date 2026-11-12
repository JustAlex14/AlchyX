# AlchyX

AlchyX is a library mod for NeoForge (Minecraft 26.1.2). It lets a mod, a datapack or a KubeJS script add multiblock machines without writing the structure code every time. You describe the structure in a JSON file, give the machine a core block, and AlchyX handles the rest: checking the structure, the forming wave that turns the blocks into machine parts, rotation, breaking, automation through the parts, previews, the guide book page and the animations.

AlchyX does nothing on its own. Players install it because another mod needs it: [AlchyMastery](https://github.com/JustAlex14/AlchyMastery), [AlchyRift](https://github.com/JustAlex14/AlchyRift), [AlchyFarm](https://github.com/JustAlex14/AlchyFarm) and [AlchyForbidden](https://github.com/JustAlex14/AlchyForbidden) are all built on it.

## Which page do I need?

If you play a modpack that uses AlchyX, read [For Players](For-Players). It explains how machines form, the structure preview and the Structure Builder.

If you want to add your own machine, pick the way that suits you:

| You want to | Read |
|---|---|
| Write a NeoForge mod with machines in Java | [Getting Started](Getting-Started), then [Your First Multiblock](Your-First-Multiblock) |
| Add machines to a modpack without Java | [KubeJS Machines](KubeJS-Machines) |
| Change or add structures with a datapack | [Pattern Format](Pattern-Format) |
| Change recipes, tags and shapes of AlchyX mods in a modpack | [Modpack Guide](Modpack-Guide) |
| Animate a machine | [Animations](Animations) |
| Let players choose a machine's shape | [Free-Build Machines](Free-Build-Machines) |
| Animate a machine in game, without writing files | [Free-Build Machines](Free-Build-Machines) (the Machine Studio) |
| Show a small machine inside a bigger one | [Animations](Animations#mini-structures) |
| Add your mod's animations to machines (Create...) | [Addon API](Addon-API) |

## Developer pages

- [Getting Started](Getting-Started): adding AlchyX to your build
- [Your First Multiblock](Your-First-Multiblock): a complete small machine, from the block to the JSON
- [Pattern Format](Pattern-Format): every field of a structure file
- [Free-Build Machines](Free-Build-Machines): machines whose shape each player builds and records, and the Machine Studio
- [Core Block Entity](Core-Block-Entity): the base class and every method you can override
- [Inventories and GUIs](Inventories-and-GUIs): items, fluids, energy, menus and the structure preview button
- [Upgrades](Upgrades): upgrade types, tiers, slots and corruption
- [Animations](Animations): the machine animation files and how your code drives them
- [Guide Books](Guide-Books): Patchouli pages, chapters that unlock, paper per chapter
- [Advanced Machines](Advanced-Machines): machines that grow tiers, worker cores, fenced fields, deaths nearby, choice screens, linking tools, rifted items
- [Addon API](Addon-API): studio motions, part animators and mini structures for other mods
- [Showcase and Trailers](Showcase-and-Trailers): building machines from a command for videos
- [Troubleshooting](Troubleshooting): when a pattern does not load or a machine does not form

## Versions

| AlchyX | Minecraft | NeoForge | Java |
|---|---|---|---|
| 0.3.x | 26.1.2 | 26.1.2.x | 25 |

The source is MIT licensed.
