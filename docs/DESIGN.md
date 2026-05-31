# SlashAnnounce — Design Document

**Status:** Draft for review (v2 — multi-version, research-informed)
**Type:** Server-side Fabric mod, multi-band **MC 1.20.1 → 26.1.2** (priority: 26.1.2)
**Repo:** `mindfulent/SlashAnnounce`
**Package / group:** `dev.blockacademy.slashannounce`

---

## 0. Resolved decisions

| Decision | Choice |
|---|---|
| Transport | **Console emission** (mod logs structured lines; backend's existing Pterodactyl-WS observer parses them). HTTP sink documented as a future per-intercept option, not built in v1. |
| Repo | New `mindfulent/SlashAnnounce` (generic; can serve TBA's 1.21.1 server later too). |
| Sign scope | **Placement only** — the first write on a freshly-placed sign, detected via the editor-UUID gate. Re-edits are not announced. |
| Audit | **Persist** every announce to the backend `mc_audit` table (in addition to the Discord post). |
| Routing | **Single generic** slashAI route `/server/announce`; a renderer registry keyed by `kind`. |
| Versions | 1.20.1 → 26.1.2, multi-band (see §11). Priority on 26.1.2. |

---

## 1. Motivation

The TBA/TBS Discord bridge (DeanBot + the theblockacademy backend) surfaces
Minecraft activity in `#server-chat` by **parsing the server console** that the
backend reads over the Pterodactyl WebSocket:

```
MC console ─► Pterodactyl WS ─► parseLogLine() ─► dispatchEvent() ─► slashAI ─► DeanBot embed
```

This works for everything vanilla *prints to the console*: chat, joins, leaves,
advancements, `Done (x.xs)!`. But a large class of interesting events **never
touch the console**:

- **Night-skip** (a slept-through night). On TBS the "Better Server Sleep" mod
  is a 2-line datapack — it sets `players_sleeping_percentage` to 1 and the skip
  is then *pure vanilla*, which logs nothing. (Confirmed by reading the jar.)
- **Sign contents** when a player places a sign.
- Most mod-/datapack-driven happenings (custom raids, container locks, trades…).

These are invisible to the bridge because the console has nothing to parse.

**SlashAnnounce** is a small, server-side mod that makes any chosen in-game
event **visible** to the bridge. Each event is an *intercept*; when it fires, the
mod writes one structured line to the server console, and the existing pipeline
carries it to Discord. Sleep is the first intercept, sign placement the second;
the framework makes a third a small, local change.

---

## 2. Goals / Non-goals

### Goals
- A reusable **intercept framework**: event hook + structured emit, per-intercept
  enable/config. Adding an intercept is a self-contained unit.
- **Zero secrets, zero egress.** The mod never holds an API key and never makes
  network calls. It only writes to the console/log.
- **Reuse the existing pipeline** end to end. A *single* generic backend pattern
  + a *single* generic slashAI route serve all intercepts; new types usually need
  only a small slashAI renderer.
- **Multi-version: 1.20.1 → 26.1.2**, priority 26.1.2. Built on the proven
  multi-band skeleton from SlashLootr/TipSign/StreamCraft.
- **Safe by construction:** never block the tick, never crash from a mixin,
  debounce/dedupe, bounded payload size.

### Non-goals (v1)
- Not a chat bridge — DeanBot already relays chat both ways.
- Not client-side; no GUI, no commands required.
- Does **not** re-implement events vanilla already logs (joins/leaves/chat/
  advancements/death messages already flow through the bridge).
- Not a moderation/profanity filter (a downstream slashAI/Discord concern).

---

## 3. Architecture

```
┌──────────────────────── SlashAnnounce (server-side, all bands) ────────────────────────┐
│  Intercept (sleep) ─┐                                                                    │
│  Intercept (sign)  ─┼─► Emitter.emit(type, payload) ─► [SlashAnnounce/v1] <type> <json>  (log4j INFO)
│  Intercept (…)     ─┘            (config-gated)                                           │
└──────────────────────────────────────────────────────────────────────────────────────────┘
                                              │  (stdout / latest.log)
                                              ▼
                                 Pterodactyl console WebSocket
                                              │
              theblockacademy backend  minecraftConsole.ts ─► parseLogLine()
                                              │   (ONE generic SlashAnnounce pattern)
                                              ▼
                  dispatchEvent({type:'announce', payload:{kind,…}}, server)
                                              │   (rate-limit + server-label, existing)
                                              ├─► mc_audit INSERT (persist)
                                              ▼
                        slashAI  POST /server/announce ─► handle_mc_announce
                                              │   (renderer registry, keyed by `kind`)
                                              ▼
                              DeanBot embed in #server-chat
```

**Why console emission (chosen):**
- No API key in the mod or on the Bloom.host server; nothing secret to leak.
- No outbound network from the game server; works regardless of host egress.
- The mod is *fully decoupled* — it doesn't know the backend exists.
- Lines are human-visible in the console, making debugging trivial.
- ANSI stripping, rate limiting, and dispatch plumbing already exist.

**HTTP alternative (documented, not built):** an intercept could instead POST to
`theblock.academy/api/mc/announce` behind `authenticateServer`. That buys
guaranteed delivery (independent of WS liveness) and bigger payloads, at the cost
of a secret on the server and egress. The framework keeps a per-intercept "sink"
seam so this can be added later for any intercept that needs it; v1 ships the
console sink only.

---

## 4. Wire format (the console line)

One line, emitted at INFO through a dedicated logger named `SlashAnnounce`:

```
[SlashAnnounce/v1] <type> <compact-json>
```

Examples:
```
[SlashAnnounce/v1] sleep {"sleepers":3,"players":["alex","sam","jo"],"dimension":"overworld","timeAdvanced":true}
[SlashAnnounce/v1] sign {"player":"alex","x":12,"y":64,"z":-30,"dimension":"overworld","side":"front","lines":["Welcome","to Spawn","",""]}
```

Rules:
- **One physical line.** Newlines in content escaped (`\n`); JSON compact.
- **ANSI-free / control-char-free.** The mod writes plain text; backend also
  strips ANSI defensively (`parseLogLine` already does).
- **`type`** matches `^[a-z][a-z0-9_]*$` (the renderer key = `kind`).
- **Schema version** in the tag (`/v1`); backend pattern pinned to it.
- **Bounded size.** Payload JSON capped (~1500 chars); oversize → truncate with
  `"truncated":true`.
- **Collision-safe.** The tag is distinctive and the backend pattern is anchored;
  a player typing the tag in chat (logged as `<name> …`) cannot be misread. The
  chat pattern stays first in the parser.

No `server` field in the line — the backend's `dispatchEvent` attaches the server
name/label when multiple servers are configured, exactly like the other events.
Keeps the mod server-agnostic.

---

## 5. The intercept framework (mod internals)

### 5.1 Core types
```java
public interface Intercept {
    /** stable wire type, e.g. "sleep", "sign". Must match ^[a-z][a-z0-9_]*$ */
    String type();
    /** Wire up the mixin/event callback. Called once on server start if enabled. */
    void register(MinecraftServer server, SlashAnnounceConfig.Section cfg);
}

public final class Emitter {
    private static final Logger LOG = LoggerFactory.getLogger("SlashAnnounce");
    /** Cheap, main-thread-safe, never throws. Encodes JSON, escapes, caps size. */
    public static void emit(String type, Map<String, Object> payload) { … }
}
```
- Each intercept owns its mixin(s), turns the raw game event into a `payload`,
  and calls `Emitter.emit(...)`.
- Mixins compile in and can't be unloaded, **but every emit is config-gated**, so
  a disabled intercept is an early `return` (near-zero cost).
- All formatting/escaping/capping lives in the `Emitter` — one place.

### 5.2 Registration
A central `SlashAnnounce` `ModInitializer`:
1. Loads `config/slashannounce.json` (creates a default on first run).
2. Builds the registry of known intercepts.
3. On `ServerLifecycleEvents.SERVER_STARTED`, calls `register(...)` for each
   intercept whose config section is enabled. (Mixins always load; the gate is in
   the emit path, so toggling = config edit + restart, never a rebuild.)

### 5.3 Config — `config/slashannounce.json`
```json
{
  "enabled": true,
  "intercepts": {
    "sleep": { "enabled": true, "debounceSeconds": 10, "includePlayerNames": true, "overworldOnly": true },
    "sign":  { "enabled": true, "includeBackSide": false, "maxLineLength": 120 }
  }
}
```
- `enabled` (global) is a master kill-switch; per-intercept sections carry that
  intercept's options. Missing sections default to enabled; unknown keys ignored.
- **No secrets** in this file (console design). Safe to ship a template.

---

## 6. v1 intercepts

### 6.1 `sleep` — night skipped
- **Hook:** mixin `@Inject(method="wakeUpAllPlayers", at=@At("HEAD"))` on
  `net.minecraft.server.level.ServerLevel`.
  - **Verified stable** (private, no-arg, byte-identical 1.18.1→1.21.10) with a
    **single call site** — the `areEnoughSleeping(i) && areEnoughDeepSleeping(...)`
    branch in `ServerLevel#tick`. HEAD inject = exactly "the server is skipping the
    night." Manual single-player wake routes through `ServerPlayer#stopSleepInBed`
    (a different path), so it is **not** caught — exactly what we want.
  - Mixin sees private members; no access-widener needed.
- **Guards:** overworld-only (config); debounce window (config) to collapse
  duplicate fires into one announcement.
- **`doDaylightCycle` edge:** `wakeUpAllPlayers` also runs when
  `RULE_DAYLIGHT=false` (players wake but time doesn't advance). The handler reads
  `GameRules.RULE_DAYLIGHT` and reports it as `timeAdvanced` so the renderer can
  phrase correctly. (With default rules, always `true`.)
- **Payload:** `{ sleepers:int, players:[name…], dimension:string, timeAdvanced:bool }`
  (`players` omitted if `includePlayerNames=false`).
- **Discord render:** `🌅 The night was skipped (3 sleeping)`.

### 6.2 `sign` — sign placed (first write only)
- **Hook:** mixin on `net.minecraft.world.level.block.entity.SignBlockEntity#updateSignText(Player, boolean, List<FilteredText>)`
  (the worker called from `ServerGamePacketListenerImpl#handleSignUpdate(ServerboundSignUpdatePacket)`).
  Both names **verified stable** since the 1.20 sign rework; the front/back
  `SignText` + `isFrontText` + waxing model is identical 1.20.1→1.21.x.
- **Placement vs re-edit (the key mechanic):** detect placement via the
  **editor-UUID gate**, not emptiness. Vanilla sets `allowedPlayerEditor` to the
  placer on placement and **clears it after the first successful write**
  (`setAllowedPlayerEditor(null)`), locking the sign. So only the placement write
  passes `getPlayerWhoMayEdit()`. Inject **before** the write and emit only when
  the gate is about to pass:
  - `!isWaxed()` **and** `getPlayerWhoMayEdit()` is non-null **and** equals the
    editing player → this is the placement write. Re-edits (gate already cleared)
    are naturally excluded → satisfies "placement only".
  - ⚠️ **Verify the exact spelling** of `getPlayerWhoMayEdit()` /
    `setAllowedPlayerEditor(...)` per band via Linkie before writing the mixin —
    a javadoc tool reported `getEditor`/`setEditor`; the decompiled official source
    uses `getPlayerWhoMayEdit`/`setAllowedPlayerEditor`. This is the single spot
    most likely to need a per-band tweak (though no rename was observed in range).
- **Payload:** `{ player:string, x, y, z, dimension:string, side:"front"|"back",
  lines:[l0,l1,l2,l3] }`. Empty trailing lines kept as `""`; the renderer trims.
  `includeBackSide=false` by default → only the front placement write is emitted.
- **Discord render:** an embed authored by the player (mc-heads avatar) with the
  sign text as the body and coords in a footer:
  > **alex** placed a sign at (12, 64, -30)
  > ```
  > Welcome
  > to Spawn
  > ```
- **Privacy:** sign text now appears in Discord **and** in the server
  console/logs. Documented for operators; disableable via config.

### 6.3 Adding a future intercept (the recipe)
1. Implement `Intercept` + its mixin; emit `type` + payload.
2. Add a config default section.
3. Add a **renderer** in slashAI keyed by that `type` (often the *only*
   downstream change — the backend pattern and route are generic).

---

## 7. Backend changes (theblockacademy)

Added once, generic; most new intercepts need nothing here.

- **`backend/src/services/minecraftConsole.patterns.ts`**
  - Add `'announce'` to the `EventType` union.
  - Add **one** pattern (after the chat pattern), capturing tag/type/JSON:
    ```ts
    new RegExp(`^${PREFIX}\\[SlashAnnounce/v1\\]\\s+([a-z][a-z0-9_]*)\\s+(\\{.*\\})\\s*$`)
    ```
    `build`: guarded `JSON.parse` (bad JSON → `null` + warn) →
    `{ type:'announce', payload:{ kind:m[1], ...parsed } }`.
- **`backend/src/services/minecraftDispatcher.ts`**
  - `ROUTE`: `announce: '/server/announce'`.
  - `LIMITS`: `announce: 30` (per-`kind` sub-limiting can be added later).
- **Persist to `mc_audit`** (decision: yes). The dispatcher (or a thin hook on the
  announce path) writes a row `event:'announce'` with `metadata:{kind,...payload}`.
  Table already exists (migration `058_mc_audit.sql`); no new migration. For sign
  placements, `minecraft_username` = the placer; coords/lines go in `metadata`.

No new env var. (`dispatchEvent` adds the `[label]` prefix only when
`listServers().length > 1`, identical to the other events.)

---

## 8. slashAI changes

A single generic handler + a renderer registry.

- **`src/discord_bot.py`** — register `/server/announce` (inside the
  `if self.dean_bot is not None:` block) and add:
  ```python
  async def handle_mc_announce(self, request):
      if not self._mc_auth_ok(request):
          return web.json_response({"error": "Unauthorized"}, status=401)
      if not self._mc_handlers_enabled():
          return web.json_response({"success": True, "skipped": "handlers_disabled"})
      data = await request.json()
      kind = data.get("kind")
      renderer = ANNOUNCE_RENDERERS.get(kind)
      if renderer is None:
          return web.json_response({"success": True, "skipped": f"no renderer for {kind}"})
      embed = renderer(self, data)        # returns a discord.Embed
      return await self._mc_post_embed(embed)
  ```
- **`ANNOUNCE_RENDERERS`** — one small function per kind, reusing `_mc_prefix`
  (server label), `_mc_avatar`, `_mc_post_embed`. v1 ships `sleep` and `sign`.
  Adding a kind = adding one function.
- Gated by the same `MC_WEBHOOK_HANDLERS_ENABLED` kill-switch as every MC event.

---

## 9. Config, secrets & privacy

- **No secrets in the mod** (console design) — nothing to manage on Bloom.host
  beyond the non-secret `config/slashannounce.json`.
- The pack (`TBS-server`, and later `TBA`) ships the **band-matched jar** as a raw
  override (packwiz bundles non-`.pw.toml` files). TBS-server runs **26.1.2** →
  ships the Band I jar.
- **Privacy:** the `sign` intercept puts player text into Discord and server logs.
  Documented; disableable via config.

---

## 10. Safety & performance

- **Never block the tick:** emit = string-format + one `LOG.info`. No I/O, no
  network. Cheap inline on the server thread.
- **Never crash from a mixin:** each intercept handler wraps its body in
  `try/catch`; failures log and are swallowed — an announce bug must never affect
  gameplay.
- **Debounce / dedupe:** sleep debounced per config; sign is one-shot per
  placement write (editor gate guarantees it).
- **Rate-awareness:** backend dispatcher drops past its window; the mod also
  avoids floods (debounce; placement-only signs).
- **Bounded output + sanitization:** payload cap + truncation flag; strip control
  chars / ANSI; escape newlines before logging.

---

## 11. Multi-version banding (1.20.1 → 26.1.2)

**Key research result:** SlashAnnounce's hooks (`wakeUpAllPlayers`,
`SignBlockEntity#updateSignText` / `handleSignUpdate`, `RULE_PLAYERS_SLEEPING_PERCENTAGE`)
are **stable across the whole 1.20.1→1.21.x range** — no rename, no signature
change, sign model unchanged since 1.20. **Therefore band boundaries are driven
by the toolchain, not by our mixin targets.** Unlike SlashLootr (which forks
mixins per band for loot/SavedData API churn), **SlashAnnounce shares one
identical MC-facing source set across all bands** — no per-band mixin forks. Each
band is just a recompile against a representative MC version + that generation's
Fabric API.

### Band table (clone of the SlashLootr/StreamCraft skeleton)

| Band | MC | Java | Loom | Gradle | Loader | Mappings | Build | Our-target notes |
|---|---|---|---|---|---|---|---|---|
| A | 1.20.1 (–1.20.4) | 17 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | targets stable; only Java 17 differs |
| B | 1.20.5–1.20.6 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | — |
| C | 1.21–1.21.1 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | TBA server target (1.21.1) |
| D | 1.21.2–1.21.4 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | — |
| E | 1.21.5 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | — |
| F | 1.21.6–1.21.8 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | — |
| G | 1.21.9–1.21.10 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | — |
| H | 1.21.11 | 21 | 1.13.6 | 8.14 | 0.16.x | Mojang | root | — |
| **I** | **26.1.2** | **25** | **1.15.5** | **9.4.1** | **0.18.6** | **none (unobf.)** | **quarantined** | **priority**; verify target names from intermediary/source |

**Toolchain breaks:** Java 17→21 at Band B; Java 21→25 + Loom 1.13.6→1.15.5 +
Gradle 8.14→9.4.1 + mappings→none at Band I (26.1.x is unobfuscated; omit
`loom.officialMojangMappings()`; Loom 1.15.5 handles it). Band I is a
**quarantined sibling build** with its own wrapper, **not** included in the root
`settings.gradle`; built via a root `build26` `Exec` task. (Exactly the
SlashLootr pattern.)

### Layout (copy SlashLootr)
```
SlashAnnounce/
├─ settings.gradle              # include "common" + versions:1.20.1 … 1.21.11 (NOT 26.1.2)
├─ build.gradle                 # root config + buildAll + build26(Exec) + collect to build/release/
├─ gradle.properties           # mod_version, maven_group, loader_version, archives_base_name
├─ common/                      # Java 17, MC-agnostic: config parsing, JSON emit, data records
│  └─ src/main/java/dev/blockacademy/slashannounce/common/…
├─ versions/<ver>/             # one Loom subproject per band; shares MC source via srcDirs +=
│  ├─ build.gradle  gradle.properties
│  └─ src/main/java/dev/blockacademy/slashannounce/…  (Intercepts + mixins — SHARED, identical)
│  └─ src/main/resources/  fabric.mod.json  slashannounce.mixins.json
└─ versions/26.1.2/            # quarantined: own settings.gradle + gradlew (9.4.1), Java 25, Loom 1.15.5
```
Because the MC-facing source is identical across bands, it lives in **one shared
location** (`srcDirs +=` into each band, à la SlashLootr/TipSign) with **no
exclude lists / no per-band override files** — sidestepping the "band-fork drift"
failure mode that bites StreamCraft. The only per-band files are
`build.gradle` + `gradle.properties` (toolchain + MC/Fabric-API versions) and the
expanded `fabric.mod.json`.

### Possible simplification (validate, don't assume)
Since the referenced symbols are unchanged across the Java-21 span, a single jar
built against (say) 1.21.1 with a **widened `depends.minecraft` range** in
`fabric.mod.json` may load across several patch versions, reducing the number of
jars actually shipped. This is an optimization to confirm with a load test per
span — **the safe default is one jar per band.** Priority order for build/QA:
**Band I (26.1.2)** first (TBS), then Band C (1.21.1, TBA), then the rest.

### Toolchain pin (Band I, from StreamCraft's 26.1 band)
JDK **25**, Fabric Loom **1.15.5**, Gradle wrapper **9.4.1**, Loader **0.18.6**,
Fabric API `…+26.1.2`, **no mappings** (unobfuscated). Build via quarantined
wrapper: `gradlew build` in `versions/26.1.2/`, invoked from root `build26`.

---

## 12. Failure modes / observability
- Backend WS down → announce lines sit in the log and are lost (**at-most-once**;
  acceptable for announcements). No retry/queue in v1.
- Bad JSON or unknown `kind` → backend/slashAI skip gracefully (logged), never
  500 the pipeline.
- Everything visible in console for debugging; slashAI has a global kill-switch;
  each intercept has a config toggle; `mc_audit` gives durable history.

---

## 13. Testing
1. **Dev server per band:** `./gradlew :versions:<ver>:runServer` (and the
   quarantined `runServer` for 26.1.2). Sleep through a night and place a sign;
   confirm exactly the expected `[SlashAnnounce/v1] …` lines appear, once each,
   well-formed. (Note: TBS is 26.1.2; LocalServer is 1.21.1 — use the mod's own
   dev servers for version-correct internals.)
2. **Pipeline (local):** run theblockacademy backend + slashAI locally with
   `MC_WEBHOOK_HANDLERS_ENABLED=true`; feed the dev-server console (or replay the
   lines); confirm embeds render in a test channel and an `mc_audit` row is written.
3. **Prod:** publish backend + slashAI, add the band-matched jar to the pack,
   deploy to the live server **only with explicit approval** (repo prod rule).

---

## 14. Rollout phases
- **Phase 1 — Framework + `sleep`, Band I (26.1.2) first.** Mod scaffold +
  intercept framework + sleep intercept; backend generic pattern + route +
  `mc_audit`; slashAI generic handler + sleep renderer. End-to-end on the 26.1.2
  dev server, then add Band C (1.21.1) and the rest.
- **Phase 2 — `sign` (placement).** Sign intercept (editor-gate detection) +
  slashAI sign renderer + config.
- **Future.** More intercepts (container locks, raids, trades, deaths-with-
  context…); optional HTTP sink for any needing guaranteed delivery; widen
  `depends` ranges to trim jar count if the load test supports it.

---

## 15. Remaining open items
1. **Band-count optimization** — confirm by load test whether widened
   `depends.minecraft` ranges let one jar cover multiple patch versions, vs one
   jar per band. (Safe default: one per band.)
2. **`getPlayerWhoMayEdit`/`setAllowedPlayerEditor` exact spelling** per band
   (Linkie check before writing the sign mixin).
3. **26.1.2 target names** — confirm `wakeUpAllPlayers` /
   `SignBlockEntity#updateSignText` against the unobfuscated 26.1.2 source/
   intermediary (research deliberately scoped web work to ≤1.21.x).
4. **Sign back side / waxed signs** — back side is off by default; confirm we
   never want it. Waxed-on-placement is impossible (waxing happens after), so the
   editor gate already excludes it.
