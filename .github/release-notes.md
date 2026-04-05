<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- Added `/entitydetective item_summary` — lists all loaded item entities grouped by item type with entity count, total quantity, and colour-coded severity (green < 100, yellow < 1 000, red ≥ 1 000 items)
- Added `/entitydetective item_locate <item_id>` — shows which chunks contain a specific item entity type, sorted by concentration; supports tab-complete limited to types currently loaded in the world
- Both new commands support `--world <dim>` to scope results to a single dimension
