# AlchyX

A library mod for NeoForge (Minecraft 26.1.2) that lets mods and datapacks define multiblock machines in JSON, and gives them the parts they would otherwise each write.

- Structure patterns with detection, a forming wave and disguised shell blocks
- Machine animations defined in datapack files (see [ANIMATIONS.md](ANIMATIONS.md))
- KubeJS support (see [KUBEJS.md](KUBEJS.md))
- Patchouli integration: every pattern becomes a book multiblock, with fitted previews, and book chapters can unlock when another chapter is read. Book paper can change chapter by chapter, with marks on some pages
- Upgrades: upgrade kinds with tiers, slots, emblems and the corruption rifts they open on a machine
- Miniatures: living models of a structure or a room, mobs included
- Worker cores: a core worked by one figure going on errands over its ground, with the timing shared by server and client
- Choice screens: a grid of kinds to turn on or off, with search, a filter, a dial, notes and chances under the mouse, drawn in a mod's own window theme
- Linking tools: a tool that holds a block, glows while it does, and shows its links with lines of motes
- Rifted items: a level (1 to 6) marked on any item, a recipe ingredient `alchyx:rifted` for it, and a furnace hook so a rifted thing stays rifted when cooked

AlchyX does nothing on its own. It is used by [AlchyMastery](https://github.com/JustAlex14/AlchyMastery), [AlchyRift](https://github.com/JustAlex14/AlchyRift) and AlchyFarm.

## Building

```
./gradlew build
./gradlew publishToMavenLocal   # only needed to build a mod against a published jar
```

The mods of the series build AlchyX together with themselves when it sits next to them (`../AlchyX`). The jar ends up in `build/libs`.

## License

MIT, see [LICENSE](LICENSE).
