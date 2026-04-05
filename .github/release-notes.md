<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **Breaking:** Commands restructured under `mob` and `item` namespaces for consistency — `/entitydetective <category>` → `/entitydetective mob <category>`, `/entitydetective entity <type>` → `/entitydetective mob locate <type>`, `/entitydetective mobcap` → `/entitydetective mob cap`, `/entitydetective item_summary` → `/entitydetective item`, `/entitydetective item_locate <id>` → `/entitydetective item locate <id>`
- Removed `misc` mob category from `mob` commands — it is a Minecraft engine catch-all (projectiles, XP orbs, falling blocks) with no mob cap; item entities are handled by `item` commands
