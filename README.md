# Entity Detective

[![Build](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml/badge.svg)](https://github.com/netcrafts/Entity-Detective/actions/workflows/build.yml)
[![GitHub downloads](https://img.shields.io/github/downloads/netcrafts/Entity-Detective/total?label=Downloads&logo=github)](https://github.com/netcrafts/Entity-Detective/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A server-side [Fabric](https://fabricmc.net/) mod for Minecraft that gives admins commands to locate, audit, and diagnose entity accumulation — with a focus on **lazy-chunk mobs** that never despawn because no player is nearby.

> Replaces the limited `/execute as @e[type=...]` datapack approach. Works cross-dimension, respects LuckPerms, and shows live mob cap data.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.1.1  
2. Drop `entity-detective-<version>.jar` into your `mods/` folder  
3. No client mod required — server-side only

**Permissions:** requires op level 2 by default. If [LuckPerms](https://luckperms.net/) is present, grant the node `entitydetective.command` instead.

---

## Commands

### `/entitydetective mob <category>`
Find all entities of a mob category, grouped by chunk, across **all loaded dimensions** by default.

```
/entitydetective mob monster
/entitydetective mob creature
/entitydetective mob ambient
/entitydetective mob axolotls
/entitydetective mob water_creature
/entitydetective mob water_ambient
```

**Flags:**
| Flag | Description |
|------|-------------|
| `--lazy-only` | Only show entities with no player within 128 blocks (won't despawn naturally) |
| `--world <dim>` | Scope to a specific dimension: `overworld`, `nether`, or `end` |
| `--summary` | One-line summary per dimension instead of full chunk list |
| `--persistent` | Show only persistent mobs (name-tagged, holding a picked-up item, leashed, riding a vehicle) |
| `--debug` | Show each entity individually with exact coordinates, type, and persistence reason (clickable to `/tp`) |

**Examples:**
```
/entitydetective mob monster --lazy-only
/entitydetective mob monster --lazy-only --world overworld
/entitydetective mob creature --world nether --summary
/entitydetective mob monster --persistent --debug
/entitydetective mob monster --debug
```

> **Note:** The `misc` category is intentionally excluded — it is a Minecraft engine catch-all for non-mob entities (projectiles, XP orbs, falling blocks, etc.) with no mob cap. Item entities are handled by `/entitydetective item`. To locate or profile any specific entity type (including non-mobs), use `/entitydetective entity`.

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
List all loaded **item entities** (dropped items on the ground) grouped by item type across all dimensions. Useful for finding item overflow from farms or mob drops.

```
/entitydetective item
/entitydetective item --world overworld
```

Output shows entity count, total item quantity, and colour-coded severity per type:
- **Green** — < 100 items
- **Yellow** — 100–999 items
- **Red** — ≥ 1 000 items

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

## Debug output

Adding `--debug` to any command expands each chunk line to list every individual entity with:
- Registry type (e.g. `minecraft:piglin_brute`)
- Custom name if name-tagged
- Exact XYZ coordinates
- Persistence reason (`name tagged`, `holding item`, `leashed`, `riding vehicle`, `custom persistence`)
- Click the line to paste a `/tp` command directly to that entity

```
-- monster [nether]: 9 entities in 1 chunks --
  [6, 11] — 9 entities
    minecraft:piglin_brute  @ 103.5, 45.0, 182.3  (holding item)
    minecraft:piglin_brute  "Guard"  @ 101.0, 44.0, 184.7  (name tagged)
```

---

## Persistent mobs explained

A mob is considered **persistent** when it will not naturally despawn:
- **Name-tagged** — named with a name tag
- **Holding a picked-up item** — `persistenceRequired` is set when a mob equips a ground item via `setItemSlotAndDropWhenKilled`
- **Leashed** — attached to a fence post or held by a player
- **Riding a vehicle** — inside a boat or minecart

By default, `/entitydetective mob <category>` **excludes** persistent mobs (they never contribute to mob cap pressure). Use `--persistent` to see only them.

---

## Permissions

| Node | Default | Description |
|------|---------|-------------|
| `entitydetective.command` | op level 2 | Access to all `/entitydetective` commands |

---

## Compatibility

| Dependency | Version |
|------------|---------|
| Minecraft | 26.1.1 |
| Fabric Loader | ≥ 0.18.6 |
| Fabric API | ≥ 0.145.3+26.1.1 |
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
