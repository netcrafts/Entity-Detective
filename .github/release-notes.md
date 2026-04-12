<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- Add `--detail` flag to all `mob`, `entity`, `item`, `entity locate`, and `item locate` commands. Expands output to a chunk-grouped entity list with clickable `/tp` on chunk headers and entity lines. Persistence reason shown per entity when combined with `--persistent`. Must be the last flag.
- `--lazy-only` and `--persistent` now always return a type-count table. Add `--detail` to see individual entities.
- `--debug` on `entity locate` retired — replaced by `--detail` (now available on every command).
- `entity profile all` no longer requires `--range`. Bare `all` profiles all dimensions; add `--world <dim>` or `--range <chunks>` to scope.
- Profiler output redesigned: `count / short-name / X.XXXmspt / avg: X.XXXms`. Vanilla `minecraft:` prefix stripped from type names.
- `entity summary --range` promoted to `entity --range <chunks>` (direct flag on the `entity` command).
