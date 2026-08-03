<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **New command**: `/ed nark <playerName>` — profiles all entity types in a 21×21 chunk area centred on another online player’s position for 100 ticks. Useful for diagnosing lag near a specific player without being in their vicinity. Tab-completes online player names. Works from console.
- **New**: `--persistent` sub-type filter — after `--persistent`, select a persistence cause to narrow results: `all` · `name-tagged` · `holding-item` · `structure-spawn` · `in-vehicle` · `leashed` · `custom`.
- **New**: `/ed chunks loaders` and `/ed chunks stasis` — filter the chunk loader view to portal loaders or ender pearl stasis chambers only.
- **Improvement**: clickable `/tp` links are now self-contained (show the caller’s name instead of `@s`).
- **Fix**: persistent mob classification corrected (`structure spawn` and `riding vehicle` now correctly identified instead of `holding item`).
