<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **Breaking:** `mob locate` → `entity locate`, `mob profile` → `entity profile`. The new `entity` namespace accepts any entity type ID (mobs, item frames, boats, etc.), making the command intent explicit.
- Added `entity profile <type> [<ticks>]` command — measures avg MSPT contribution and avg entity count/tick for any entity type over a configurable window (default 100 ticks, min 20, max 6000); per-dimension breakdown included. Profiling technique adapted from [fabric-carpet](https://github.com/gnembon/fabric-carpet).
- Added `--lazy-only` flag to `item locate` — filters results to items in lazy (non-ticking) chunks, useful for tracking accumulated drops in unmanned farms or far-from-spawn areas
