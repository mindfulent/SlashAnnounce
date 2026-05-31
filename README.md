# SlashAnnounce

A small, **server-side** Fabric mod that makes console-invisible in-game events
visible to a console-parsing Discord bridge (the TBA/TBS DeanBot pipeline). Each
event is an *intercept*: when it fires, the mod writes one structured line to the
server console —

```
[SlashAnnounce/v1] <type> <compact-json>
```

— and the existing bridge carries it to Discord. The mod holds **no secrets** and
makes **no network calls**; it only logs.

## Why

Vanilla prints chat, joins, advancements, etc. to the console, so the bridge
already relays them. But a slept-through **night skip** is pure-vanilla and logs
nothing, **sign contents** never hit the console, and most mod/datapack events are
invisible too. SlashAnnounce surfaces any chosen event with a tiny, local change.

## v1 intercepts

| `type` | Fires when | Payload |
|--------|-----------|---------|
| `sleep` | the server skips a night (`ServerLevel#wakeUpAllPlayers`) | `{sleepers, players?, dimension, timeAdvanced}` |
| `sign`  | a sign is **placed** (first write only, via the editor-UUID gate) | `{player, x, y, z, dimension, side, lines[4]}` |

Each intercept is config-gated in `config/slashannounce.json` (created on first
run); the global `enabled` flag is a master kill-switch.

## Building

Multi-band: MC **1.20.1 → 26.1.2**. The MC-facing source is shared verbatim across
all bands; the only per-band divergence is a single `Compat` seam (26.1.x reworked
gamerules / `ResourceKey#identifier`). Band I (26.1.2) is a quarantined sibling
build (JDK 25 / Loom 1.15.5 / Gradle 9.4.1) shelled out via `build26`.

```bash
# JDK 21 (e.g. Prism's bundled runtime) for the classic bands:
./gradlew :versions:1.21.1:build      # one band
./gradlew buildAll                    # all bands → build/release/   (build26 needs JDK 25)

# Band I (MC 26.1.2) standalone:
cd versions/26.1.2 && ./gradlew build

# Dev server for a band:
./gradlew :versions:1.21.1:runServer
```

## Architecture

```
Intercept ─► Emitter.emit(type, payload) ─► [SlashAnnounce/v1] <type> <json>  (log4j INFO)
   ─► Pterodactyl console WS ─► backend parseLogLine() ─► dispatch (+ mc_audit)
   ─► slashAI POST /server/announce ─► renderer registry (keyed by kind) ─► DeanBot embed
```

- `common/` — pure Java 17 (`Emitter`, config). No Minecraft references.
- `mc-src/` — shared MC-facing source (ModInitializer, intercepts, mixins).
- `mc-compat-classic/` + `versions/26.1.2/.../compat/` — the per-generation `Compat` seam.
- `versions/<ver>/` — one Loom subproject per band (toolchain + resources only).

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design.

## License

CC-BY-4.0
