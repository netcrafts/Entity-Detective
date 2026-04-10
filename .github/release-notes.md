<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- Added `--radius <chunks>` flag (1–32) to `mob <category>`, `entity locate`, `entity profile`, `item`, and `item locate` — scopes the search to a sphere of N chunks around your position instead of scanning the whole world. Requires a player source; mutually exclusive with `--world`.
- New command: `entity summary --radius <chunks>` — instant census of every entity type within the radius, sorted by count. No tick window; results are immediate.
- New command: `entity profile all [<ticks>] --radius <chunks>` — profiles every entity type at your location over a tick window and reports MSPT cost sorted descending, with a TOTAL row. Useful for diagnosing the overall tick cost of a base or farm.
