# CoreMC testing & sandbox environment

## Environment constraints (and how they were worked around)

The CI sandbox used for this rebuild permits outbound traffic only to
`github.com`/`api.github.com`/`codeload.github.com`, PyPI, and the npm
registry. No Maven Central, no PaperMC/Mojang/Adoptium endpoints, no
system-wide JDK install. Every tool was therefore sourced cleanly and
verifiably:

| Tool | Source | Notes |
|---|---|---|
| Java 21 runtime (Temurin 21.0.8) | PyPI `jdk4py` wheel | Server runtime (no javac needed at runtime). |
| Java compiler | Eclipse `org.eclipse.jdt.core.compiler.batch` 3.36.0 jar from a GitHub repo | Runs on the JRE; compiles our sources to Java 21 bytecode. |
| `paper-api` 1.21.11 (+1.21.1) | GitHub-committed local Maven repo caches | Plus the full compile closure (adventure, brigadier, guava, gson, fastutil, joml, slf4j, log4j-api, snakeyaml, jsr305, …). |
| Live server | Paper 1.21.11 (paperclip + mojang jar + patched server + 114 libraries), all hash-verified by paperclip itself | Runs fully offline. |
| Real player client | Node 22 + `mineflayer` (npm, protocol 1.21.11 supported) | Offline-mode bots, driven as real players. |
| Unit tests | JUnit 5.9 jars from GitHub | Executed via a tiny launcher (`com.coremc.testrun.TestRunner`). |

`build.sh` runs the whole pipeline: compile (release 21) → resource
filtering (`@project.version@`) → jar → compile tests → run tests.
It reproduces `mvn package`; Maven remains the canonical build.

## What was verified on the live server

1. Plugin enable/disable: `CoreMC 0.1.0 enabled.` /
   `CoreMC disabled — all player data saved, all tasks cancelled.` — no
   warnings, no stack traces, no leftover tasks (`Tracked tasks: 1`,
   the autosave timer).
2. Console: `/coremc`, `/coremc reload`, `/coremc bogus`, `/profile`,
   `/profile <absent>` — correct branded output/usage lines for all.
3. Real player joins: branded first-join welcome exactly once; `/profile`
   shows header/first-join/logins/last-seen with correct legacy colours.
4. Permissions: non-op player is denied `/coremc reload`
   (`You do not have permission to do that.`), default `/coremc` and
   `/profile` allowed. `/heal` (op-default) is hidden from a non-op
   player's command tree on Paper ("Unknown or incomplete command") and
   they are not healed.
4b. `/heal` gameplay: op player switched to survival was damaged by the
   console (hp 20 → 19), `/heal` restored hp 20 and food 20 with the
   branded message; `heal TestSteve` from the console healed an online
   player (`Healed TestSteve.` / `You have been healed by CONSOLE.`).
   `heal <offline>` answers with the branded player-not-online message,
   resolved through the messages.yml defaults merge (old messages.yml on
   disk contained no heal keys — no "Missing message" errors).
5. Persistence: quit → YAML written (`plugins/CoreMC/profiles/<uuid>.yml`);
   rejoin (same session) → `Total logins: 2`; **full server restart** →
   rejoin → `Total logins: 2` → data survives restarts.
6. Reload safety: 3× `/coremc reload` → `Tracked tasks: 1` afterwards —
   autosave timers are replaced, never leaked.
7. Shutdown: clean exit code 0, all profiles flushed.

## Unit tests

`src/test/java` — 29 tests, all passing:

- `PlayerProfileTest` — creation, login accounting, YAML map round-trip,
  legacy/string tolerance.
- `YamlPlayerDataStoreTest` — save/load round-trip on a real filesystem,
  missing-file = empty, corrupt-file = `IOException`, no `.tmp` residue.
- `ColorUtilTest`, `DateTimeUtilTest` — colour translation and age/timestamp
  formatting edge cases.
- `GridAssignerTest` — spiral order (`(0,0),(1,0),(1,1),(0,1),(-1,1),…`),
  occupied-cell skipping, per-world key scoping.
- `IslandTest` — map round-trip of every field, grid-cell floor division for
  negative coordinates, home-spawn offset (`centre + 0.5 / +1 / +0.5`).
- `YamlIslandDataStoreTest` — save/load/delete round-trip, corrupt file =
  `IOException`, no `.tmp` residue.

## Island system E2E (v0.3.0, executed on Paper 1.21.11)

Automated (`/home/user/bot/test-island.js`, 11 checks, all PASS) with two
offline-mode bots plus console assertions:

1. `/island create` → branded creation + teleport messages; server-authoritative
   position `(0.5, 65, 0.5)` (first spiral cell); `grass_block` under feet.
2. Second `create` → "already have an island".
3. Second player creates → cell `(1, 0)` → centre `(256, 64, 0)` — spacing honoured.
4. `/island info` → stored centre `0, 64, 0`.
5. `/island teleport` returns home.
6. Delete flow: first call asks for confirmation (repeats the 15s window),
   second call within the window deletes and removes `islands/<uuid>.yml`;
   subsequent `teleport` → "don't have an island yet".
7. **Restart persistence**: create → full server stop/boot →
   `Loaded 1 island(s)` on enable → `/island teleport` →
   `data get entity <player> Pos` = `[0.5, 65.0, 0.5]` — island survived the
   restart, home teleport server-verified.

### Test-harness caveat (position drift, NOT a plugin bug)

mineflayer's 1.21.11 support is pre-release: several seconds after *any*
teleport — including vanilla console `/tp` — the bot client re-simulates its
old physics state and rubber-bands the (creative-mode, flying) player back
toward its pre-teleport position, overwriting the server-side location.
The same desync makes bot-issued **block digs rolled back by the server**
even for island owners (both vanilla `/tp` context and block actions are
affected; a real Java client is not). Control experiments: identical drift
and dig-rollback after vanilla `/tp` and owner digs — the plugin is not
involved. Protection is therefore verified at the message contract level
(outsider dig attempts always produced the branded `island.protected`
denial, zero exceptions) and by server-state queries (`execute if block`)
showing island blocks unchanged after outsider attempts — while position
assertions read server-side truth close to the event.

How to run (in this sandbox): `./build.sh`  (compile + tests + jar)

## Running the live test scenario manually

1. `./build.sh` → copy `target/CoreMC-<version>.jar` into the server's
   `plugins/` (remove any previous CoreMC jar first — never keep two jars of
   the same plugin).
2. Start Paper, wait for `Done (...)`.
3. From console: `coremc`, `coremc reload`, `profile` (expect player-only).
4. Join a real client (offline mode): expect branded welcome; run
   `/profile`; quit; rejoin; verify `Total logins` increments and the
   profile YAML updates.
5. Islands: `/island create` (expect teleport onto new platform),
   `/island info`, walk away, `/island teleport`, then `/island delete`
   twice (confirmation) and confirm `plugins/CoreMC/islands/` is empty again.
   Restart the server, rejoin, re-create and reboot once more to check
   `Loaded 1 island(s)` + persistent home teleport.
