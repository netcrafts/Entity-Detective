<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **New command**: `/entitydetective chunks` — detects active portal chunk loaders (shown as paired OW ↔ Nether lines) and ender pearl stasis chambers across all dimensions. Coordinates are clickable `/tp` suggestions when you are in the matching dimension (green), non-clickable otherwise (red). Unpaired entries are marked `(unconfirmed)`.
- **New flag**: `--ticking-only` for `entity locate <be_type>` — runs a short observation window and returns only block entity positions that actually ticked.
- **New flag**: `--debug` for `entity profile <be_type>` — reports per-position tick counts instead of aggregate MSPT, useful for pinpointing which specific instance of a type is active.
- **New flag**: `--world <dim>` for `entity profile <type>` — profile a single entity or block entity type scoped to one dimension without requiring a player source.
- **New alias**: `/ed` as a shorthand for `/entitydetective`.
- **Mod attribution**: modded entity and block entity types now show a `[modid]` prefix in gold in profiler output.
