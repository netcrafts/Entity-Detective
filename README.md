# Entity Detective

[![Build](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml/badge.svg)](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml)
[![GitHub downloads](https://img.shields.io/github/downloads/netcrafts/Entity-Detective/total?label=Downloads&logo=github)](https://github.com/netcrafts/Entity-Detective/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A server-side [Fabric](https://fabricmc.net/) mod for Minecraft that gives admins commands to locate, audit, diagnose, and **profile** entity accumulation, and detect active chunk loaders.

> Replaces the limited `/execute as @e[type=...]` datapack approach. Works cross-dimension, respects LuckPerms, and shows live mob cap data. Use `/ed` as a shorthand alias for `/entitydetective`.

## Command overview

```
/entitydetective
├── mob <category>              — Type-count summary per dimension
│   │   (monster | creature | ambient | axolotls | water_creature | water_ambient)
│   │   Flags: --lazy-only, --world, --persistent, --detail
│   │   Range: --range <chunks> [--lazy-only] [--persistent] [--detail]
│   └── cap                    — Live mob cap: current vs max, colour-coded saturation
│
├── entity                      — Summary of all entity types by count, per dimension
│   │   Flags: --lazy-only, --persistent, --world, --detail
│   │   Range: --range <chunks>
│   ├── locate <type>          — Locate entities or block entities by type ID (tab-complete from live world)
│   │       Flags: --lazy-only (entities only), --world, --detail, --ticking-only (block entities only)
│   │       Range: --range <chunks> [--lazy-only] [--detail] [--ticking-only]
│   ├── --range <chunks>       — Census of every entity type within range (instant)
   ├── profile <type> [ticks] — MSPT profiling of one entity or block entity type
   │       Accepts: entity type IDs (e.g. minecraft:zombie) or block entity type IDs (e.g. minecraft:hopper)
   │       Default: 100 ticks (5 s) | Min: 20 | Max: 6000
   │       Flags: --world <dim>, --debug (block entities only)
   │       Range: --range <chunks> (snapshots player position at start)
   └── profile all [ticks]    — Profile every entity and block entity type, sorted by MSPT cost
│           Bare: all dimensions | --world <dim>: one dimension | --range <chunks>: chunk square
│
└── item                       — Dropped item summary per dimension
    │   Flags: --lazy-only, --world, --detail
    │   Range: --range <chunks> [--detail]
    └── locate <item_id>       — Find chunks with a specific dropped item type
            Flags: --lazy-only, --world, --detail
            Range: --range <chunks> [--lazy-only] [--detail]

└── chunks                     — Detect active chunk loaders across all dimensions
        Portal loaders shown as paired OW ↔ Nether lines; stasis chambers listed separately
        Coordinates are clickable /tp suggestions when you are in the matching dimension
```

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.1.x  
2. Drop `entity-detective-<version>.jar` into your `mods/` folder  
3. No client mod required — server-side only

`/ed` is available as a shorthand alias for `/entitydetective`.

**Permissions:** The singleplayer world owner can use all commands without enabling cheats. On a dedicated server, op level 2 is required by default. If [LuckPerms](https://luckperms.net/) is present, grant the node `entitydetective.command` instead.

---

## Flags

| Flag | Description |
|------|-------------|
| `--lazy-only` | Filter to non-persistent entities in **lazy chunks** — no player within 128 blocks. Despawn can't fire so they accumulate silently. Returns a type-count table; add `--detail` to see individual entities. |
| `--persistent` | Filter to persistent mobs: name-tagged, holding a picked-up item, leashed, or riding a vehicle. Returns a type-count table. |
| `--world <dim>` | Scope to one dimension: `overworld`, `nether`, or `end`. |
| `--range <chunks>` | Scope to a (2N+1)×(2N+1) chunk square centred on your position (0–32). `--range 0` targets the single chunk you are standing in. Player source required; mutually exclusive with `--world`. |
| `--detail` | Expand output to a chunk-grouped list; each line is a clickable `/tp`. When combined with `--persistent`, shows the persistence reason per entity. **Must be the last flag.** |
| `--ticking-only` | *(block entity locate only)* Runs a short observation window and returns only positions that actually ticked — useful for finding which block entities in a large farm are still active. |
| `--debug` | *(block entity profile only)* Reports per-position tick counts instead of aggregate MSPT — useful for diagnosing uneven load across many instances of the same type. |

---

## Examples

**Quick census at your base:**
```
/entitydetective entity --range 10
```

**Find lazy monsters accumulating in the overworld:**
```
/entitydetective mob monster --lazy-only --world overworld
```
```
-- lazy monster [overworld]: 45 entities --
  minecraft:zombie      28
  minecraft:skeleton    12
  minecraft:creeper      5
Total: 45 entities across 3 types
```

**Locate those zombies (clickable `/tp` links per entity):**
```
/entitydetective mob monster --lazy-only --world overworld --detail
```
```
-- monster [overworld] (lazy, --detail): 45 entities in 2 chunks --
  Chunk (12, -4)  ×  31  →  /tp @s 192 ~ -64
    [196, 63, -61]  —  minecraft:zombie
    [191, 64, -68]  —  minecraft:skeleton "Bob"  (name tagged)
    ...
```

**What is costing the most MSPT at my base?**
```
/entitydetective entity profile all --range 10
```
```
-- Base Profile: 10-chunk range (100 ticks) --
  0.847mspt  avg:   0.003ms  item_frame ×312
  0.214mspt  avg:   0.012ms  armor_stand ×18
  0.182mspt  avg:   0.023ms  [be] hopper ×8
  0.109mspt  avg:   0.018ms  villager ×6
  1.352mspt  TOTAL ×344
```
Drill in: `/entitydetective entity profile minecraft:item_frame 200 --range 10`

**Detect active chunk loaders:**
```
/entitydetective chunks
```
```
-- chunk loaders: 3 portal loaders, 1 stasis chamber --
  Portal  OW (596, -4)   |  Nether (74, -1)
  Portal  OW (346, 674)  |  Nether (43, 84)
  Portal  Nether (21, -24)  (unconfirmed)
  Pearl   OW (-80, 128)
```
Coordinates are **green and clickable** when you are in the matching dimension, **red** (non-clickable) otherwise. Entries that could not be paired to a counterpart are marked `(unconfirmed)`.

---

## What's new in Entity Detective 2.5.4

### `/entitydetective chunks` — Chunk loader detection

New command that scans all dimensions for active portal chunk loaders and ender pearl stasis chambers:

- **Portal loaders** are shown as paired `OW (x, z) | Nether (x, z)` lines
- Coordinates are **green and clickable** as `/tp` suggestions when you are in the matching dimension; **red** (non-clickable) when you are not
- Entries that could not be paired to a counterpart in the other dimension are marked `(unconfirmed)` — they may be real loaders or the result of a brief player portal transit
- **Stasis chambers** (`Pearl`) are listed with their dimension and a tp link

### `entity locate <be_type> --ticking-only`

Adds `--ticking-only` to block entity locate. Runs a short observation window and returns only the positions that actually ticked — useful for finding which hoppers or comparators in a large farm are still active.

### `entity profile <be_type> --debug`

New `--debug` flag for block entity profiling. Instead of aggregate MSPT, reports how many times each individual position ticked during the window — useful for diagnosing uneven load across a large pool of the same block entity type.

### `entity profile <type> --world <dim>`

Profile a single entity or block entity type scoped to one dimension, without requiring a player source.

### `/ed` alias

`/ed` is now a registered alias for `/entitydetective`.

### Mod attribution in profiler output

Profile results for modded entity and block entity types now show a `[modid]` prefix in gold so third-party mod types are clearly distinguished from vanilla.

---

## Concepts

### Chunk loaders

Minecraft loads chunks around active nether portals via `PORTAL` chunk tickets, and keeps chunks loaded for ender pearl stasis chambers via `ENDER_PEARL` tickets. `/entitydetective chunks` reads these tickets directly from the server's distance manager across all loaded dimensions. Portal loader entries are paired OW↔Nether where possible (based on the 1:8 coordinate scale). Unpaired entries are shown with an `(unconfirmed)` tag since a player simply walking through a portal creates the same ticket type briefly.

### Lazy chunks

Chunks that are loaded but not ticked — no player within 128 blocks — are called **lazy chunks**. Entities there never move, never breed, and despawn logic never fires, so they accumulate silently until a player enters range. `--lazy-only` filters to non-persistent entities that fail the 128-block proximity check, showing what the server will never clean up on its own.

### Persistent mobs

A mob **won't naturally despawn** if it has been name-tagged, has picked up a ground item, is leashed, or is riding a vehicle. By default `mob <category>` excludes persistent mobs (they don't contribute to mob cap). Use `--persistent` to see only them. Combine `--lazy-only --persistent` to find mobs that are both outside player range and undespawnable.

### Range search

`--range N` covers a (2N+1)×(2N+1) chunk square centred on your position — `--range 10` = 21×21 chunks, matching a simulation distance of 10. Range requires a player source, is mutually exclusive with `--world`, and for profiling commands your position is captured at command-issue time.

---

## Permissions

| Node | Default | Description |
|------|---------|-------------|
| `entitydetective.command` | op level 2 | Access to all `/entitydetective` commands. Singleplayer world owner always has access regardless of cheat setting. |

---

## Compatibility

| Dependency | Version |
|------------|---------|
| Minecraft | 26.1.x |
| Fabric Loader | ≥ 0.19.1 |
| Fabric API | ≥ 0.145.4+26.1.2 |
| LuckPerms | optional |

---

## Building from source

```bash
git clone https://github.com/netcrafts/Entity-Detective.git
cd Entity-Detective
./gradlew build
# Output: build/libs/entity-detective-<version>.jar
```

---

## Credits

The entity tick profiling feature (`entity profile`) uses a mixin approach adapted from [fabric-carpet](https://github.com/gnembon/fabric-carpet) by gnembon and contributors, specifically `CarpetProfiler` and `Level_tickMixin`. fabric-carpet is licensed under LGPL-3.0.

---

## License

MIT — see [LICENSE](LICENSE)
