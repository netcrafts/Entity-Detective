<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- `--radius` renamed to `--range` across all commands — behaviour changed from a Euclidean sphere to a **(2N+1)×(2N+1) chunk square** matching Minecraft's own simulation distance semantics (e.g. `--range 10` = 21×21 chunks).
- `entity summary --range` and `entity profile all --range` now respect the chunk-square boundary consistently.
- Internal dead code removed: scaffold `ExampleMixin`, unused query methods, and unused method overloads.
