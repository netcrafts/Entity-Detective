<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- Updated for Minecraft 26.1.2 (security fix release). Built against Fabric API 0.145.4+26.1.2 and Fabric Loader 0.19.1.
- `--lazy-only` and `--persistent` now always return a type-count table. Add `--detail` to see individual entities.
- `--debug` on `entity locate` retired — replaced by `--detail` (now available on every command).
- `entity profile all` no longer requires `--range`. Bare `all` profiles all dimensions; add `--world <dim>` or `--range <chunks>` to scope.
- Profiler output redesigned: `count / short-name / X.XXXmspt / avg: X.XXXms`. Vanilla `minecraft:` prefix stripped from type names. Values right-aligned so decimal points line up in the MC console.
- `entity summary --range` promoted to `entity --range <chunks>` (direct flag on the `entity` command).
- **Singleplayer compatible**: the world owner can now use all commands in a singleplayer world without enabling cheats.
