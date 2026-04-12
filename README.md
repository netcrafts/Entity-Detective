# Entity Detective

[![Build](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml/badge.svg)](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml)
[![GitHub downloads](https://img.shields.io/github/downloads/netcrafts/Entity-Detective/total?label=Downloads&logo=github)](https://github.com/netcrafts/Entity-Detective/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A server-side [Fabric](https://fabricmc.net/) mod for Minecraft that gives admins commands to locate, audit, diagnose, and **profile** entity accumulation.

> Replaces the limited `/execute as @e[type=...]` datapack approach. Works cross-dimension, respects LuckPerms, and shows live mob cap data.

## What's new in v2.4.0

- **`--detail` flag** added to all `mob`, `entity`, `item`, `entity locate`, and `item locate` commands. Expands output from a type-count table to a **chunk-grouped entity list** — each chunk header is a clickable `/tp` to the chunk centre, each entity line is a clickable `/tp` to the entity's exact position. Persistence reason shown per entity when combined with `--persistent`. `--detail` must always be the **last flag** in the command.
- **Output consistency:** `--lazy-only` and `--persistent` now always return a **type-count table** regardless of which flags are combined. To see individual entities, add `--detail`.
- **`--debug` retired** on `entity locate` — replaced by `--detail` (identical expansion, now available on every command).
- **`entity profile all`** no longer requires `--range`. Bare `all` profiles every entity type across all loaded dimensions. Add `--world <dim>` to scope to one dimension, or `--range <chunks>` to scope to a chunk square around your position.
- **Profiler output redesigned:** rows now show `count  short-name  X.XXXmspt  avg: X.XXXms` — count is the avg entities/tick, `avg:` is ms-per-entity. Vanilla `minecraft:` prefix stripped from type names for readability.

---

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

**`--range` rules:** chunks unit (1–32), player source required, mutually exclusive with `--world`.

**`--detail` rule:** must always be the **last flag** in the command (Brigadier positional constraint). Example: `--range 10 --lazy-only --detail` ✓ — `--detail --lazy-only` ✗.

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
| `--lazy-only` | Filter to entities in lazy chunks (no player within 128 blocks). Returns a type-count table. |
| `--world <dim>` | Scope to a specific dimension: `overworld`, `nether`, or `end`. |
| `--persistent` | Filter to persistent mobs (name-tagged, holding a picked-up item, leashed, riding a vehicle). Returns a type-count table. |
| `--range <chunks>` | Scope the search to a square of the given chunk range (1–32) centred on your chunk. `--range N` covers a (2N+1) × (2N+1) square of chunks — e.g. `--range 10` = 21×21 chunks. Mutually exclusive with `--world`. Requires a player source. |
| `--detail` | Expand output to a chunk-grouped entity list. Each chunk header is a clickable `/tp` to the chunk centre; each entity line is a clickable `/tp` to the entity's exact position. When combined with `--persistent`, each line shows the persistence reason. Must be the last flag. |

**Examples:**
```
/entitydetective mob monster
/entitydetective mob monster --world overworld
/entitydetective mob monster --lazy-only
/entitydetective mob monster --lazy-only --world overworld
/entitydetective mob monster --lazy-only --persistent
/entitydetective mob monster --persistent
/entitydetective mob monster --range 10
/entitydetective mob monster --range 10 --lazy-only
/entitydetective mob monster --range 10 --persistent
/entitydetective mob monster --detail
/entitydetective mob monster --lazy-only --detail
/entitydetective mob monster --persistent --detail
/entitydetective mob monster --lazy-only --persistent --detail
/entitydetective mob monster --world overworld --detail
/entitydetective mob monster --range 10 --detail
/entitydetective mob monster --range 10 --lazy-only --detail
/entitydetective mob monster --range 10 --persistent --detail
```

**`--lazy-only` output** (type-count table):
```
-- lazy monster [overworld]: 45 entities --
  minecraft:zombie                              28
  minecraft:skeleton                            12
  minecraft:creeper                              5
Total: 45 entities across 3 types
```

**`--lazy-only --detail` output** (chunk-grouped, every entity clickable):
```
-- monster [overworld] (lazy, --detail): 45 entities in 3 chunks --
  Chunk (12, -4)  ×  18  →  /tp @s 192 ~ -64
    [196, 63, -61]  —  minecraft:zombie
    [191, 64, -68]  —  minecraft:skeleton "Bob"  (name tagged)
    ...
  Chunk (12, -5)  ×  15  →  /tp @s 192 ~ -80
    ...
Total: 45 entities
```

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
| `--persistent` | Filter to persistent mobs. Returns a type-count table. |
| `--lazy-only` | Filter to entities in lazy chunks. Returns a type-count table. Combine with `--persistent` to find lazy persistent mobs across all types. |
| `--detail` | Expand output to a chunk-grouped entity list with clickable `/tp` on every chunk header and entity line. When combined with `--persistent`, each entity line shows the persistence reason. Must be the last flag. |

---

### `/entitydetective entity locate <type>`
Find all entities of a specific type across all loaded dimensions. Works on **any entity type** — mobs, item frames, boats, falling blocks, etc. Tab-complete shows only types **currently loaded in the world**, with substring matching — type `bat` to find `minecraft:bat`, type `item_frame` to find item frames.

```
/entitydetective entity locate minecraft:bee
/entitydetective entity locate minecraft:bee --lazy-only
/entitydetective entity locate minecraft:bee --world overworld
/entitydetective entity locate minecraft:bee --detail
/entitydetective entity locate minecraft:item_frame --lazy-only --world overworld --detail
/entitydetective entity locate minecraft:item_frame --range 8
/entitydetective entity locate minecraft:item_frame --range 8 --lazy-only
/entitydetective entity locate minecraft:item_frame --range 8 --lazy-only --detail
```

| Flag | Description |
|------|-------------|
| `--lazy-only` | Only show entities in lazy chunks. |
| `--world <dim>` | Scope to one dimension. |
| `--detail` | Expand each chunk to show individual entity coordinates, type, name, and persistence reason. Clickable `/tp` on chunk headers and entity lines. Must be the last flag. |
| `--range <chunks>` | Scope results to a chunk square centred on your chunk (1–32). `--range N` = (2N+1)×(2N+1) chunks. Mutually exclusive with `--world`. |

---

### `/entitydetective entity --range <chunks>`
Instant census — counts **every entity type** within the given chunk range around your position, sorted by count descending. No tick window required. Useful for getting a quick overview of what is at your base before deciding whether to profile it.

```
/entitydetective entity --range 10
```

Output:
```
-- Entity Census: 10-chunk range: 423 entities --
  minecraft:item_frame          312
  minecraft:dropped_item         83
  minecraft:armor_stand          18
  minecraft:villager              6
  minecraft:chest_minecart        4
Total: 423 entities across 5 types
```

---

### `/entitydetective entity profile <type> [<ticks>] [--range <chunks>]`
Time how many milliseconds per tick all entities of a given type collectively consume, measured over a rolling window of server ticks. Results are sent automatically when the window completes. Works on **any entity type**.

| Parameter | Default | Min | Max |
|-----------|---------|-----|-----|
| `ticks` | `100` (5 s) | `20` (1 s) | `6000` (5 min) |

```
/entitydetective entity profile minecraft:zombie
/entitydetective entity profile minecraft:zombie 200
/entitydetective entity profile minecraft:item_frame
/entitydetective entity profile minecraft:item_frame --range 8
/entitydetective entity profile minecraft:item_frame 200 --range 8
```

Without `--range`, all entities of that type across all dimensions are measured.  
With `--range`, only entities within a (2N+1)×(2N+1) chunk square centred on your position at command-issue time are measured.

Output after the sample window:
- **Avg. tick cost** — average ms/tick spent ticking all entities of this type
- **Avg. count** — average number of entities ticked per tick during the window
- **Total sampled** — total entity-ticks observed
- Per-dimension breakdown when entities span multiple worlds

---

### `/entitydetective entity profile all [<ticks>]`
Profile **every** entity type sorted by MSPT cost descending. Three scope modes:

| Usage | Scope |
|-------|-------|
| `entity profile all` | All loaded dimensions (global) |
| `entity profile all --world <dim>` | One dimension (`overworld`, `nether`, or `end`) |
| `entity profile all --range <chunks>` | Chunk square around your position at command-issue time |

```
/entitydetective entity profile all
/entitydetective entity profile all 200
/entitydetective entity profile all --world overworld
/entitydetective entity profile all --world overworld 200
/entitydetective entity profile all --range 10
/entitydetective entity profile all --range 10 200
```

Output:
```
-- Base Profile: 10-chunk range (100 ticks) --
  312  item_frame                      0.847mspt  avg: 0.003ms
   18  armor_stand                     0.214mspt  avg: 0.012ms
    6  villager                        0.109mspt  avg: 0.018ms
    4  chest_minecart                  0.012mspt  avg: 0.003ms
  340  TOTAL                           1.182mspt
```

Row columns: **avg entities/tick** · **short name** (vanilla `minecraft:` prefix stripped) · **mspt** (total tick cost) · **avg ms per entity**. TOTAL row omits the per-entity average.

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
/entitydetective item --range 10
```

Default output per dimension shows entity count, total item quantity, and colour-coded severity per type:
- **Green** — < 100 items
- **Yellow** — 100–999 items
- **Red** — ≥ 1 000 items

`--lazy-only` filters to item entities in lazy chunks and returns a type-count table. Add `--detail` to see individual entity positions with clickable `/tp` links.

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
| `--range <chunks>` | Scope results to a chunk square centred on your chunk (1–32). `--range N` = (2N+1)×(2N+1) chunks. Mutually exclusive with `--world`. |
| `--detail` | Expand each chunk to show individual item entity positions with clickable `/tp` links. Must be the last flag. |

```
/entitydetective item locate minecraft:cobblestone --range 10
/entitydetective item locate minecraft:cobblestone --range 10 --lazy-only
/entitydetective item locate minecraft:cobblestone --detail
/entitydetective item locate minecraft:cobblestone --lazy-only --detail
/entitydetective item locate minecraft:cobblestone --range 10 --detail
```

---

## `--detail` flag

Adding `--detail` to any `mob`, `entity`, `item`, `entity locate`, or `item locate` command expands output from a type-count table to a **chunk-grouped entity list**:
- Each **chunk header** shows chunk coordinates, entity count, and a clickable `/tp` to the chunk centre
- Each **entity line** shows exact XYZ, registry type, custom name, and (when `--persistent` is active) the persistence reason
- Click any line to paste the `/tp` command directly

`--detail` must always be the **last flag** in the command.

Example — `entity locate` with `--detail`:
```
-- minecraft:bee [overworld] (lazy only, --detail): 9 entities in 1 chunks --
  Chunk (6, 11)  ×  9  →  /tp @s 100 ~ 180
    [103, 45, 182]  —  minecraft:bee
    [101, 44, 184]  —  minecraft:bee
    ...
Total: 9 entities
```

Example — `mob` with `--persistent --detail`:
```
-- monster [overworld] (persistent, --detail): 3 entities in 1 chunks --
  Chunk (0, 0)  ×  3  →  /tp @s 8 ~ 8
    [12, 64, 8]  —  minecraft:zombie "Bob"  (name tagged)
    [8, 63, 10]  —  minecraft:skeleton  (holding item)
    [14, 64, 6]  —  minecraft:creeper  (leashed)
Total: 3 entities
```

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

### Range search explained

Any command that accepts `--range <chunks>` scopes the search to a **square** of chunks centred on your current chunk. `--range N` covers a (2N+1) × (2N+1) square — your chunk plus N chunks out in every direction.

**Examples:**
- `--range 3` → 7×7 = 49 chunks
- `--range 10` → 21×21 = 441 chunks (matches a typical 10-chunk simulation distance)
- `--range 1` → 3×3 = 9 chunks (matches a typical chunk loader)

This aligns with how Minecraft counts simulation distance: a simulation distance of 10 loads entities in a 21×21 square of chunks around the player.

**Rules:**
- **Unit:** chunks, not blocks (1–32).
- **Player source required.** Range commands must be run by a player in-game. They cannot be run from the server console or by command blocks.
- **Mutually exclusive with `--world`.** Use one or the other, not both.
- **Snapshot position.** For profiling commands, your chunk is captured when the command is issued. Moving during the measurement window does not change the profiling area.

**Typical workflow — diagnosing a laggy base:**
1. Stand at your base and run `/entitydetective entity --range 10` to get a quick entity census.
2. If the count looks high, run `/entitydetective entity profile all --range 10` to see which type costs the most MSPT.
3. Drill in with `/entitydetective entity profile minecraft:item_frame --range 10` for a longer, more accurate reading.

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
