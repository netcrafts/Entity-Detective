# Entity Detective

[![Build](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml/badge.svg)](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml)
[![GitHub downloads](https://img.shields.io/github/downloads/netcrafts/Entity-Detective/total?label=Downloads&logo=github)](https://github.com/netcrafts/Entity-Detective/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A server-side [Fabric](https://fabricmc.net/) mod for Minecraft that gives admins commands to locate, audit, diagnose, and **profile** entity accumulation.

> Replaces the limited `/execute as @e[type=...]` datapack approach. Works cross-dimension, respects LuckPerms, and shows live mob cap data.

---

## Command overview

```
/entitydetective
├── mob <category>              — Type-count summary per dimension
│   │   (monster | creature | ambient | axolotls | water_creature | water_ambient)
│   │   Flags: --lazy-only, --world, --persistent
│   │   --lazy-only: individual entity list sorted by distance from spawn
│   └── cap                    — Live mob cap: current vs max, colour-coded saturation
│
├── entity                      — Summary of all entity types by count, per dimension
│   │   Flags: --lazy-only, --persistent, --world
│   │   --lazy-only: individual entity list sorted by distance from spawn
│   │   --persistent: show only persistent mobs
│   ├── locate <type>          — Locate any entity type (tab-complete from live world)
│   │       Flags: --lazy-only, --world, --debug
│   └── profile <type> [ticks] — MSPT profiling of any entity type over a tick window
│           Default: 100 ticks (5 s) | Min: 20 | Max: 6000
│
└── item                       — Dropped item summary per dimension
    │   Flags: --lazy-only, --world
    │   --lazy-only: individual item entity list sorted by distance from spawn
    └── locate <item_id>       — Find chunks with a specific dropped item type
            Flags: --lazy-only, --world
```

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.1.x  
2. Drop `entity-detective-<version>.jar` into your `mods/` folder  
3. No client mod required — server-side only

**Permissions:** requires op level 2 by default. If [LuckPerms](https://luckperms.net/) is present, grant the node `entitydetective.command` instead.

---

## Commands

### `/entitydetective mob <category>`
Show a per-dimension type-count summary for entities in a mob category.

```
/entitydetective mob monster
/entitydetective mob creature
/entitydetective mob ambient
/entitydetective mob axolotls
/entitydetective mob water_creature
/entitydetective mob water_ambient
```

Output per dimension:
```
-- monster [overworld]: 128 entities --
  minecraft:zombie                                       54
  minecraft:skeleton                                     38
  minecraft:creeper                                      21
  ...
Total: 128 entities across 8 types
```

**Flags:**
| Flag | Description |
|------|-------------|
| `--lazy-only` | List every individual entity in lazy chunks (no player within 128 blocks), sorted closest to spawn first. Each line is clickable to `/tp`. |
| `--world <dim>` | Scope to a specific dimension: `overworld`, `nether`, or `end` |
| `--persistent` | List every persistent mob (name-tagged, holding a picked-up item, leashed, riding a vehicle) individually with exact locations, sorted by distance from spawn. |

**Examples:**
```
/entitydetective mob monster
/entitydetective mob monster --world overworld
/entitydetective mob monster --lazy-only
/entitydetective mob monster --lazy-only --world overworld
/entitydetective mob monster --lazy-only --persistent
/entitydetective mob monster --persistent
```

**`--lazy-only` output:**
```
-- lazy monster [overworld]: 45 entities --
  [−312, 64, 188]  —  minecraft:zombie
  [−314, 64, 190]  —  minecraft:zombie
  [904, 48, −233]  —  minecraft:creeper "Bob"
  ...
Total: 45 entities
```
Each line is clickable and pastes a `/tp` command to that entity's exact location.

> **Note:** The `misc` category is intentionally excluded — it is a Minecraft engine catch-all for non-mob entities (projectiles, XP orbs, falling blocks, etc.) with no mob cap. Item entities are handled by `/entitydetective item`. To locate or profile any specific entity type (including non-mobs), use `/entitydetective entity`.

---

### `/entitydetective entity`
List all loaded entity types sorted by count, one block per dimension.

```
/entitydetective entity
/entitydetective entity --world overworld
/entitydetective entity --world nether
/entitydetective entity --world end
/entitydetective entity --persistent
/entitydetective entity --lazy-only
/entitydetective entity --lazy-only --world overworld
/entitydetective entity --lazy-only --persistent
```

Default output per dimension:
```
-- Entity Types [overworld]: 847 entities --
  minecraft:zombie                                      312
  minecraft:skeleton                                    201
  minecraft:item_frame                                   98
  ...
Total: 847 entities across 12 types
```

| Flag | Description |
|------|-------------|
| `--persistent` | List every persistent mob individually with exact locations and clickable `/tp` links, sorted by distance from spawn. |
| `--lazy-only` | Lists every lazy entity individually sorted by distance from spawn. Combine with `--persistent` to find lazy persistent mobs across all types. |

---

### `/entitydetective entity locate <type>`
Find all entities of a specific type across all loaded dimensions. Works on **any entity type** — mobs, item frames, boats, falling blocks, etc. Tab-complete shows only types **currently loaded in the world**, with substring matching — type `bat` to find `minecraft:bat`, type `item_frame` to find item frames.

```
/entitydetective entity locate minecraft:bee
/entitydetective entity locate minecraft:bee --lazy-only
/entitydetective entity locate minecraft:bee --world overworld
/entitydetective entity locate minecraft:bee --debug
/entitydetective entity locate minecraft:item_frame --lazy-only --world overworld --debug
```

---

### `/entitydetective entity profile <type> [<ticks>]`
Time how many milliseconds per tick all entities of a given type collectively consume, measured over a rolling window of server ticks. Results are sent automatically when the window completes. Works on **any entity type** — useful for diagnosing MSPT spikes from mobs, item frames, boats, or any other entity.

| Parameter | Default | Min | Max |
|-----------|---------|-----|-----|
| `ticks` | `100` (5 s) | `20` (1 s) | `6000` (5 min) |

```
/entitydetective entity profile minecraft:zombie
/entitydetective entity profile minecraft:zombie 200
/entitydetective entity profile minecraft:item_frame
```

Output after the sample window:
- **Avg. tick cost** — average ms/tick spent ticking all entities of this type
- **Avg. count** — average number of entities ticked per tick during the window
- **Total sampled** — total entity-ticks observed
- Per-dimension breakdown when entities span multiple worlds

> Profiling technique adapted from [fabric-carpet](https://github.com/gnembon/fabric-carpet)'s `CarpetProfiler`.

---

### `/entitydetective mob cap`
Show the live mob cap for your **current dimension** — current count vs. maximum per category, with color-coded saturation (green < 50%, yellow 50–85%, red > 85%).

```
/entitydetective mob cap
```

---

### `/entitydetective item`
List all loaded **item entities** (dropped items on the ground) grouped by item type, one block per dimension. Useful for finding item overflow from farms or mob drops.

```
/entitydetective item
/entitydetective item --world overworld
/entitydetective item --lazy-only
/entitydetective item --lazy-only --world overworld
```

Default output per dimension shows entity count, total item quantity, and colour-coded severity per type:
- **Green** — < 100 items
- **Yellow** — 100–999 items
- **Red** — ≥ 1 000 items

`--lazy-only` lists every lazy item entity individually (same flat format as `mob --lazy-only`), sorted by distance from spawn.

---

### `/entitydetective item locate <item_id>`
Find which chunks contain a specific item entity type, sorted by concentration. Tab-complete is dynamic and limited to item types currently loaded in the world — substring matching works (e.g. `cobble` matches `minecraft:cobblestone`).

```
/entitydetective item locate minecraft:cobblestone
/entitydetective item locate minecraft:cobblestone --lazy-only
/entitydetective item locate minecraft:cobblestone --lazy-only --world overworld
/entitydetective item locate minecraft:cobblestone --world overworld
```

| Flag | Description |
|------|-------------|
| `--lazy-only` | Only show items in lazy chunks (no player nearby). Useful for finding accumulated drops far from spawn. |
| `--world <dim>` | Limit search to a specific dimension. |

---

## Debug flag

Adding `--debug` to `entity locate` expands each chunk line to list every individual entity with:
- Registry type (e.g. `minecraft:piglin_brute`)
- Custom name if name-tagged
- Exact XYZ coordinates
- Persistence reason (`name tagged`, `holding item`, `leashed`, `riding vehicle`, `custom persistence`)
- Click the line to paste a `/tp` command directly to that entity

```
-- minecraft:bee [overworld] (lazy only): 9 entities in 1 chunks --
  [6, 11] — 9 entities
    [103, 45, 182]  —  minecraft:bee
    [101, 44, 184]  —  minecraft:bee
```

> **Tip:** For a full list of lazy entities from any mob category with clickable `/tp` links, use `--lazy-only` on the bare `mob`, `entity`, or `item` command instead of `--debug`.

---

## Concepts

### Lazy chunks explained

The Minecraft wiki defines four chunk load types based on internal **load levels**. The relevant two for this mod are:

| Load type | Level | Entity behaviour |
|-----------|-------|-----------------|
| **Entity Ticking** | 31 and below | Entities tick normally — they move, breed, and are evaluated for despawn |
| **Block Ticking** | 32 | Entities are loaded in memory but **not processed** — no movement, no breeding, no despawn check |

The wiki itself notes that Block Ticking chunks are *"sometimes referred to as lazy chunks"*. This is the informal term used throughout the mod.

Because entities in Block Ticking chunks never receive a tick, Minecraft's despawn logic never runs on them. Mobs that wander (or are pushed) beyond player range can accumulate there indefinitely.

**How this mod detects lazy chunks**

Reading the engine's internal load level directly is unreliable — after a player leaves a dimension the ticket system takes time to downgrade chunks, so a chunk can still report `ENTITY_TICKING` even when no player is present. Instead, the mod mirrors Minecraft's own despawn logic: it checks whether any player is **within 128 blocks** of the entity. This is the exact threshold the game uses for mob despawn checks, making it the most accurate signal for "will this mob ever despawn naturally?"

The `--lazy-only` flag filters results to only those entities that fail this 128‑block check, letting you pinpoint accumulations the server will never clean up on its own.

### Persistent mobs explained

A mob is considered **persistent** when it will not naturally despawn even if it does tick:
- **Name-tagged** — named with a name tag
- **Holding a picked-up item** — `persistenceRequired` is set when a mob equips a ground item via `setItemSlotAndDropWhenKilled`
- **Leashed** — attached to a fence post or held by a player
- **Riding a vehicle** — inside a boat or minecart

By default, `/entitydetective mob <category>` **excludes** persistent mobs (they never contribute to mob cap pressure). Use `--persistent` to see only them.

> Tip: combine `--lazy-only` and `--persistent` to find the worst offenders — mobs that are both outside player range *and* flagged to never despawn.

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
