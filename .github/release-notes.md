<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **Bug fix**: `entity profile all` (and single-type `profile`) now correctly counts and times passenger entities (e.g. villagers in minecarts) — previously they were invisible to the profiler because Minecraft bypasses `guardEntityTick` for passengers.
