# Changelog

All notable changes to SlashAnnounce are documented here. Versions follow the
mod's `gradle.properties` `mod_version`; each release builds every MC band.

## [0.2.0] - 2026-05-31

### Added
- **`death` intercept** — announces player deaths with structured data instead of
  fragile console string-matching of the dozens of vanilla death-message variants.
  Hooks `ServerPlayer#die(DamageSource)` (players only) and emits
  `{player, message, cause, killer?, x, y, z, dimension}` — the verbatim vanilla
  death message plus a machine-readable `cause` (`DamageSource#getMsgId`, e.g.
  `"fall"`) and the killing entity when there is one. Config:
  `death.enabled`, `death.includeMessage`. No `Compat` seam needed — every
  referenced symbol is stable 1.20.1 → 26.1.2.

## [0.1.0] - 2026-05-31

### Added
- Initial release: a server-side **intercept framework** that surfaces
  console-invisible Minecraft events to a console-parsing Discord bridge by
  emitting one structured line per event — `[SlashAnnounce/v1] <type> <json>`.
  No secrets, no network egress.
- **`sleep` intercept** — announces night skips (`ServerLevel#wakeUpAllPlayers`),
  with `overworldOnly`, `debounceSeconds`, `includePlayerNames`, and a
  `timeAdvanced` flag (reads the daylight gamerule).
- **`sign` intercept** — announces sign **placements** only (first write, detected
  via the editor-UUID gate on `SignBlockEntity#updateSignText`).
- **Multi-version build** — MC 1.20.1 → 26.1.2 across 9 classic bands plus a
  quarantined 26.1.2 sibling build (JDK 25 / Loom 1.15.5 / Gradle 9.4.1). The
  MC-facing source is shared verbatim across all bands except a single
  per-generation `Compat` seam isolating the 26.1.x "Tiny Takeover" gamerule and
  `ResourceKey` changes.
