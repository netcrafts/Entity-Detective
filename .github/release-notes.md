<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **Bug fix**: `mob <category> --lazy-only` no longer counts persistent mobs (name-tagged, leashed, riding a vehicle) — they don't contribute to mob cap and are now correctly excluded from lazy results.
- **Improvement**: `entity profile all` now shows the total MSPT line at the **top** of the output as well as the bottom — no more scrolling to find the total on a busy server.
- **New**: `--range 0` is now valid and targets the single chunk the player is standing in.
