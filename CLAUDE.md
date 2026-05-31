# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

**Pre-implementation.** This repo currently contains only `docs/DESIGN.md` (a complete, reviewed design) — no source, build files, or git history yet. `docs/DESIGN.md` is the authoritative spec; read it in full before writing code. Repo will be `mindfulent/SlashAnnounce`; package/group `dev.blockacademy.slashannounce`.

The parent `../CLAUDE.md` (Projects-wide) governs cross-project rules — notably: **never deploy to production without explicit approval**, no `Co-Authored-By: Claude` trailer on commits, Java 21 via Prism's bundled JDK, and the slashAI MCP tools for Discord posts.

## What this is

A **server-side Fabric mod** that makes in-game events the vanilla console never prints (slept-through nights, sign placements, future mod/datapack events) visible to the existing TBA/TBS Discord bridge. Each event is an **intercept**: when it fires, the mod writes one structured line to the server console, and the *existing* pipeline carries it to Discord. The mod holds no secrets and makes no network calls — it only logs.

```
Intercept ─► Emitter.emit(type, payload) ─► "[SlashAnnounce/v1] <type> <json>" (log4j INFO)
   ─► Pterodactyl console WS ─► theblockacademy backend parseLogLine()
   ─► dispatchEvent({type:'announce', payload:{kind,…}}) ─► mc_audit INSERT
   ─► slashAI POST /server/announce ─► renderer registry (keyed by `kind`) ─► DeanBot embed
```

The mod is one of **three coordinated changes** across repos; the design's whole point is that the backend and slashAI changes are *generic* (added once), so most new intercepts touch only a single slashAI renderer:

- **This mod** — emits the console line. (`mindfulent/SlashAnnounce`)
- **theblockacademy backend** — ONE generic regex in `minecraftConsole.patterns.ts` + a route/limit in `minecraftDispatcher.ts` + `mc_audit` persist. (`../theblockacademy`)
- **slashAI** — ONE generic `handle_mc_announce` handler + an `ANNOUNCE_RENDERERS` entry per `kind`. (`../slashAI`)

See DESIGN.md §7–§8 for the exact backend/slashAI edits.

## Architecture invariants (don't break these)

- **Zero secrets, zero egress in the mod.** Console emission only — no API keys, no HTTP. (An HTTP sink is a documented *future* per-intercept seam, not built in v1.)
- **All formatting in one place.** `Emitter.emit` does JSON encode, newline-escape, ANSI/control-char strip, and size cap (~1500 chars → `"truncated":true`). Intercepts only build a payload `Map` and call it.
- **Wire format is contractual.** One physical line: `[SlashAnnounce/v1] <type> <compact-json>`. `type` matches `^[a-z][a-z0-9_]*$` and equals the slashAI renderer key. The `/v1` schema version is pinned by the backend regex — bump deliberately. No `server` field (the backend attaches the label).
- **Never block the tick, never crash from a mixin.** Emit is string-format + one `LOG.info`, inline on the server thread. Every intercept handler wraps its body in try/catch and swallows failures — an announce bug must never affect gameplay.
- **Config-gated emits.** Mixins always load (can't be unloaded); the enable check is an early return in the emit path. Toggling an intercept = edit `config/slashannounce.json` + restart, never a rebuild.

## v1 intercepts

- **`sleep`** — mixin `@Inject(HEAD)` on `ServerLevel#wakeUpAllPlayers` (verified stable & single-call-site 1.18→1.21.10 = "server is skipping the night"; manual single-player wake uses a different path and is correctly not caught). Read `GameRules.RULE_DAYLIGHT` → report as `timeAdvanced`. Overworld-only + debounce, both config.
- **`sign`** — mixin on `SignBlockEntity#updateSignText`. **Placement-only via the editor-UUID gate, NOT emptiness:** vanilla clears `setAllowedPlayerEditor(null)` after the first write, so only the placement write has a non-null `getPlayerWhoMayEdit()` equal to the editor. Inject before the write; emit only when `!isWaxed()` && editor gate is about to pass. ⚠️ Verify `getPlayerWhoMayEdit`/`setAllowedPlayerEditor` spelling per band via Linkie (a javadoc tool mis-reported `getEditor`/`setEditor`).

Adding an intercept (DESIGN.md §6.3): implement `Intercept` + mixin and emit; add a config default section; add a slashAI renderer keyed by the `type`.

## Multi-version build (planned — clone the SlashLootr skeleton)

Targets **MC 1.20.1 → 26.1.2**, priority **26.1.2** (TBS server), then **1.21.1** (TBA server). **Key result: the mixin targets are stable across the whole range** — so unlike SlashLootr, the MC-facing source set is *identical across all bands* and lives in ONE shared location (`srcDirs +=` into each band), with no per-band mixin forks, no exclude lists. Band boundaries are driven by toolchain, not by our code.

Band toolchain breaks: Java 17→21 at Band B; at **Band I (26.1.2)** Java 21→25, Loom 1.13.6→1.15.5, Gradle 8.14→9.4.1, mappings→**none** (26.1.x is unobfuscated — omit `loom.officialMojangMappings()`). Band I is a **quarantined sibling build**: its own `gradlew`/`settings.gradle`, NOT in the root `settings.gradle`, invoked from a root `build26` `Exec` task. See DESIGN.md §11 for the full band table.

Intended commands (mirror SlashLootr/TipSign once scaffolded):
```bash
# Per CLAUDE.md Java-21 rule — set JAVA_HOME to Prism's bundled JDK 21 first:
export JAVA_HOME="/c/Users/slash/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew buildAll                      # all root bands (1.20.1–1.21.11)
./gradlew :versions:1.21.1:build        # single band (TBA target)
./gradlew :versions:1.21.1:runServer    # dev server for a band
./gradlew build26                       # quarantined 26.1.2 build (needs JDK 25)
```

## Testing the full path (DESIGN.md §13)

1. **Per band:** `:versions:<ver>:runServer`, sleep through a night and place a sign, confirm exactly one well-formed `[SlashAnnounce/v1] …` line per event. (Use the mod's own dev servers for version-correct internals — TBS is 26.1.2, LocalServer is 1.21.1.)
2. **Pipeline (local):** run `../theblockacademy` backend + `../slashAI` with `MC_WEBHOOK_HANDLERS_ENABLED=true`; replay the console lines; confirm the Discord embed renders and an `mc_audit` row is written.
3. **Prod:** ship the band-matched jar as a packwiz raw override in the pack (`TBS-server` → Band I jar) — deploy to the live server **only with explicit approval**.

## Open items before coding (DESIGN.md §15)

1. Whether widened `depends.minecraft` ranges let one jar cover several patch versions (load-test; safe default = one jar per band).
2. `getPlayerWhoMayEdit`/`setAllowedPlayerEditor` exact spelling per band (Linkie).
3. Confirm `wakeUpAllPlayers` / `SignBlockEntity#updateSignText` names against unobfuscated **26.1.2** source (research scoped web work to ≤1.21.x).
