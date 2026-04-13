# Entity Detective

[![Build](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml/badge.svg)](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml)
[![GitHub downloads](https://img.shields.io/github/downloads/netcrafts/Entity-Detective/total?label=Downloads&logo=github)](https://github.com/netcrafts/Entity-Detective/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A server-side [Fabric](https://fabricmc.net/) mod for Minecraft that gives admins commands to locate, audit, diagnose, and **profile** entity accumulation.

> Replaces the limited `/execute as @e[type=...]` datapack approach. Works cross-dimension, respects LuckPerms, and shows live mob cap data.

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
│   ├── locate <type>          — Locate any entity type (tab-complete from live world)
│   │       Flags: --lazy-only, --world, --detail
│   │       Range: --range <chunks> [--lazy-only] [--detail]
│   ├── --range <chunks>       — Census of every entity type within range (instant)
│   ├── profile <type> [ticks] — MSPT profiling of one entity type
│   │       Default: 100 ticks (5 s) | Min: 20 | Max: 6000
│   │       Range: --range <chunks> (snapshots player position at start)
│   └── profile all [ticks]    — Profile every entity type, sorted by MSPT cost
│           Bare: all dimensions | --world <dim>: one dimension | --range <chunks>: chunk square
│
└── item                       — Dropped item summary per dimension
    │   Flags: --lazy-only, --world, --detail
    │   Range: --range <chunks> [--detail]
    └── locate <item_id>       — Find chunks with a specific dropped item type
            Flags: --lazy-only, --world, --detail
            Range: --range <chunks> [--lazy-only] [--detail]
```

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.1.x  
2. Drop `entity-detective-<version>.jar` into your `mods/` folder  
3. No client mod required — server-side only

**Permissions:** requires op level 2 by default. If [LuckPerms](https://luckperms.net/) is present, grant the node `entitydetective.command` instead.

---

## Flags

| Flag | Description |
|------|-------------|
| `--lazy-only` | Filter to entities in **lazy chunks** — no player within 128 blocks. They never despawn. Returns a type-count table; add `--detail` to see individual entities. |
| `--persistent` | Filter to persistent mobs: name-tagged, holding a picked-up item, leashed, or riding a vehicle. Returns a type-count table. |
| `--world <dim>` | Scope to one dimension: `overworld`, `nether`, or `end`. |
| `--range <chunks>` | Scope to a (2N+1)×(2N+1) chunk square centred on your position (1–32). Player source required; mutually exclusive with `--world`. |
| `--detail` | Expand output to a chunk-grouped list; each line is a clickable `/tp`. When combined with `--persistent`, shows the persistence reason per entity. **Must be the last flag.** |

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
  312  item_frame       0.847mspt  avg: 0.003ms
   18  armor_stand      0.214mspt  avg: 0.012ms
    6  villager         0.109mspt  avg: 0.018ms
  340  TOTAL            1.182mspt
```
Drill in: `/entitydetective entity profile minecraft:item_frame 200 --range 10`

---

## Concepts

### Lazy chunks

Chunks that are loaded but not ticked — no player within 128 blocks — are called **lazy chunks**. Entities there never move, never breed, and never despawn. They accumulate silently until a player enters range. `--lazy-only` filters to entities that fail the 128-block proximity check, showing you what the server will never clean up on its own.

### Persistent mobs

A mob **won't naturally despawn** if it has been name-tagged, has picked up a ground item, is leashed, or is riding a vehicle. By default `mob <category>` excludes persistent mobs (they don't contribute to mob cap). Use `--persistent` to see only them. Combine `--lazy-only --persistent` to find mobs that are both outside player range and undespawnable.

### Range search

`--range N` covers a (2N+1)×(2N+1) chunk square centred on your position — `--range 10` = 21×21 chunks, matching a simulation distance of 10. Range requires a player source, is mutually exclusive with `--world`, and for profiling commands your position is captured at command-issue time.

---

## Permissions

| Node | Default | Description |
|------|---------|-------------|
| `entitydetective.command` | op level 2 | Access to all `/entitydetective` commands |

---

## Compatibility

| Dependency | Version |
|------------|---------|
| Minecraft | 26.1.x |
| Fabric Loader | ≥ 0.18.6 |
| Fabric API | ≥ 0.145.3+26.1.1 (built against 26.1.1) |
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
