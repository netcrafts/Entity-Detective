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

### `/entitydetective <category>`
Find all entities of a mob category, grouped by chunk, across all loaded dimensions.

```
/entitydetective monster
/entitydetective animal
/entitydetective ambient
/entitydetective water_creature
/entitydetective water_ambient
/entitydetective misc
```

**Flags:**
| Flag | Description |
|------|-------------|
| `--lazy-only` | Only show entities with no player within 128 blocks (won't despawn naturally) |
| `--world <dim>` | Scope to a specific dimension: `overworld`, `nether`, or `end` |
| `--summary` | One-line summary per dimension instead of full chunk list |
| `--persistent` | Include named, leashed, and traded-with mobs (excluded by default) |

**Examples:**
```
/entitydetective monster --lazy-only
/entitydetective monster --lazy-only --world overworld
/entitydetective animal --world nether --summary
```

---

### `/entitydetective entity <type>`
Find all entities of a specific type across all loaded dimensions. Tab-complete shows only types currently loaded in the world.

```
/entitydetective entity minecraft:bee
/entitydetective entity minecraft:villager --lazy-only
/entitydetective entity minecraft:cow --world overworld
```

---

### `/entitydetective mobcap`
Show the live mob cap for your current dimension — current count vs. maximum per category, with color-coded saturation (green < 50%, yellow 50–85%, red > 85%).

```
/entitydetective mobcap
```

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

## License

MIT — see [LICENSE](LICENSE)
