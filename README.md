# AlchyX

A library mod for NeoForge (Minecraft 26.1.2) that lets mods and datapacks define multiblock machines in JSON.

- Structure patterns with detection, a forming wave and disguised shell blocks
- Machine animations defined in datapack files (see [ANIMATIONS.md](ANIMATIONS.md))
- KubeJS support (see [KUBEJS.md](KUBEJS.md))
- Patchouli integration: every pattern becomes a book multiblock, with fitted previews, and book chapters can unlock when another chapter is read

AlchyX does nothing on its own. It is used by [AlchyMastery](https://github.com/JustAlex14/AlchyMastery).

## Building

```
./gradlew build
./gradlew publishToMavenLocal   # needed to build AlchyMastery
```

The jar ends up in `build/libs`.

## License

MIT, see [LICENSE](LICENSE).
