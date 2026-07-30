<!--
  Edit this file before tagging a release.
  Bullet points here appear in the GitHub Release body above the Full Changelog link.
  One line per feature or fix. Keep entries short and user-facing.
-->

- **New command**: `/entitydetective hotspot` — runs a 20-tick scan across all loaded dimensions and reports the top entity-cost chunks, showing chunk coordinates (clickable `/tp`), dominant entity type, average entity count, and MSPT. Results are colour-coded green (<5 ms), yellow (5–15 ms), red (≥15 ms). Chunks below 2 ms are filtered out.
