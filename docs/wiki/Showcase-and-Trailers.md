# Showcase and Trailers

AlchyX has two tools for videos and showcase maps. Both work for any machine of any mod.

## Building a machine from a command

```
/alchyx showcase build <pattern> <pos> [facing]
```

- `pattern`: the pattern id, e.g. `mymod:kiln`
- `pos`: the middle of the machine's bottom layer (the floor of its bay). The core goes where that puts it
- `facing`: optional, for cores that can turn

The blocks are laid a few per tick from the ground up, each with its sound and dust, and the core last, so the machine is seen being built and then forms by itself. A machine that grows tiers (`TieredCoreBlockEntity`) is given its top tier, so it then grows in front of the camera. The command is silent, so it can be run from a trailer or a function.

## Trailers

A trailer is a camera path with titles over it, read from a resource pack:

```
assets/<namespace>/trailers/<name>.json
```

Play it with `/trailer play <namespace:name>` (the origin is the block you stand in), `/trailer at <x> <y> <z> <id>`, and stop it with `/trailer stop`. `/trailer list` lists them. Playing is client only: the camera leaves the player, the HUD is hidden, and black bars and titles are drawn.

Every position is relative to the trailer's origin: x east, y up, z south.

```json
{
  "letterbox": true,
  "stand": [0, 0, 3],
  "music": "mymod:trailer",
  "shots": [
    {"ticks": 120, "points": [[0, 4, 12], [6, 3, 6]], "look": [0, 1, 0], "ease": "smooth", "fade_in": 10},
    {"ticks": 160, "orbit": {"center": [0, 0, 0], "radius": 9, "height": 4, "from": 0, "to": 90, "look_height": 1}}
  ],
  "texts": [
    {"at": 20, "ticks": 80, "text": "My Mod", "style": "title", "color": "#C9A2FF"}
  ],
  "commands": [
    {"at": 0, "command": "alchyx showcase build mymod:kiln ~3 ~ ~-8 south"}
  ]
}
```

| Field | Meaning |
|---|---|
| `letterbox` | black bars top and bottom (default true) |
| `stand` | where the player stands, from the origin |
| `music` | a sound event from your `sounds.json`, played from the start on the Music volume |
| `shots` | `ticks`, then `points` (a smooth curve) or `orbit` (circles a centre, 0 = south of it, 90 = west). `look` (a point), `looks` (a path of targets) or neither (it looks where it flies). `ease`: `linear`, `smooth`, `in`, `out`. Optional `fov` and `roll` as `[from, to]`, and `fade_in`, `fade_out` in ticks |
| `texts` | `at`, `ticks`, `text` (a lang key or plain text), `style` (`title`, `subtitle`, `caption`), `color` |
| `commands` | `at` and `command`, run by the player at that tick. `~` is from where they stand. They need the permission the command asks for |

Commands run in time order. A command that takes the player to another dimension is followed: the camera goes with them, and later shots are measured from where they land. The camera waits for the world to load if it falls behind, and tells you at the end how long it waited.
