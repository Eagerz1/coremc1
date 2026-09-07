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

## v0.5.0 / v0.6.0 — Roles, Omni-Tool, Generators, Spawners

**Result: PASS** — 56/56 unit tests; live E2E on Paper 1.21.11-test server all green.

- Roles + Omni-Tool: GUI role select, soulbound drop/storage cancels, no-dup grant, death/respawn retention, restart persistence of per-role XP and Omni-Tool level, 14/14 checks; plus 45+36-slot anti-theft sweep of both role GUIs.
- Generator market: GUI purchases withdraw Credits exactly once per click, minting PDC-tagged deployment items; locked/insufficient paths denied with branded messages; 15/15 checks incl. theft sweep.
- Spawners: kill-progress tracked per entity via `EntityDeathEvent` killer attribution (verified with console-attributed kills), unlock boundary message, menu purchase for Sky Tokens, locked purchases refused, 15/15 checks incl. 9-slot spaced menu sweep.
- Placeable lifecycle (generators): place → registry → right-click harvest (server-authoritative inventory received product) → break → exactly one core item returned (no dupe), verified end-to-end on a pristine chunk with `execute if block` ground truth.

Harness caveats (environmental, non-plugin):
- mineflayer clients on Paper 1.21.11: server-side block-state truth lags/disagrees inside previously-probed chunks; all world assertions must run on pristine coordinates or via `execute if block` from console.
- Creative-gamemode test clients have client-authoritative inventories: purchased items appear to vanish across sessions. Survival mode mirrors production behavior.
- Console `execute if block` against unloaded chunks fails **silently**; block assertions require a player nearby.
- Long-running `tail -f console.in` pipelines can die mid-turn, silently dropping console lines; use send-and-ack (see server12111/send.sh).

## v0.7.0 / v0.8.0 — Shop + GUI quality pass

**Result: PASS** — 56/56 unit tests; full live verification on the 1.21.11 test server.

### Shop (v0.7.0)
- `/shop` hub (27), four 54-slot category pages, `/shop <category>` direct jump, `/tokenshop` exchange desk.
- All prices/items defined in `shop.yml` exclusively (no Java-side prices); disk file upgrade-safe like `config.yml`.
- Purchases verified: withdraw-then-deliver with zero-balance refusal, branded messages, ender-chest overflow safety, currency balance checks after every step (iron sword 250 Money; ender pearls 120 Money; 10,000 Money → 1 Sky Token exchange; 50-credit refusal at zero credits).
- `/money` admin command added (parity with credits/skytokens; previously Money had no grant path at all).

### GUI quality pass (v0.8.0) — inventory of all 10 GUIs
| GUI | Size | Slots | Theft sweep | Notes/fixes |
|---|---|---|---|---|
| RoleSelect | 45 | ✓ constants | ✓ clean | backdrop added |
| OmniTool panel | 54 | ✓ constants | ✓ clean | already filled |
| Gens market | 27 | ✓ constants | ✓ clean | backdrop added |
| Spawners overview | 27 | ✓ constants | ✓ clean | backdrop added |
| Spawner menu | 9 | ✓ constants | ✓ clean | air spacing by design |
| Shop hub | 27 | ✓ constants | ✓ clean | backdrop added |
| Shop category | 54 | ✓ constants | ✓ clean | amber grid + pane fill |
| TokenShop | 27 | ✓ constants | ✓ clean | backdrop added |
| IslandMain | 27 | ✓ constants | ✓ clean | backdrop added; action-slot set exported |
| IslandUpgrades | 27 | ✓ constants | ✓ clean | backdrop added |

### Bugs found & fixed in the pass
1. **Drag theft vector**: no `InventoryDragEvent` handler existed — drags could smuggle cursor items INTO any CoreMC GUI (and back out → dupe). Fixed: drags touching any top-inventory slot are cancelled; player-inventory-only drags untouched.
2. **Resource packaging hole**: the offline build script copied an explicit file list, shipping a jar missing new resources (0.7.0 shipped without `shop.yml` → enable-time crash). Fixed: whole `src/main/resources` directory is packaged, matching `mvn package`.
3. **Config upgrade blindness**: new `config.yml` keys never reached existing server files on upgrades. Fixed: jar defaults are merged into the disk file (user values preserved) on every load.
4. **IslandMain GUI exposed click targets**: exported `ACTION_SLOTS` so audits/tests can't drift out of sync with the layout.
5. **Missing admin command**: `/money` added to complete the three-currency admin toolset (shop economy untestable/unmanageable without it).

### Verification totals for the pass
Quiet-click sweep over every empty/decorative slot of all 10 GUIs (n=271 slots), navigation round-trips (hub→category→back, tokenshop, upgrades), purchase & refusal flows — all deterministic with zero inventory mutations outside intended purchases.

## v0.9.0 — Island upgrades go live
- `/is upgrades` now performs real purchases with Sky Tokens: `border` (tier × 25 blocks of effective protection, applied immediately to `/is info` and the protection containment check) and `member-slots` (+1 member each, wired into invite capacity).
- Costs/tiers are config-only (`island.upgrades.*`, upgrade-safe merge). Failure modes verified: max-tier refusal, insufficient-funds refusal; success flushes the island file instantly.
- Live verified: 10-token tier-1 border purchase → `/island info` reports 75x75.

## v0.9.1 — Full production-readiness audit

### Bugs discovered (and fixed)
| # | Category | Bug | Fix |
|---|----------|-----|-----|
| 1 | Inventory exploit (critical) | OmniTool shift-click stash: the container rule checked the CLICKED inventory, so shift+click moved the soulbound tool into open chests | Destination-based rules: insertion into any foreign container cancelled (click paths, number-key, F-key swap, drag) |
| 2 | Inventory soft-lock | OmniTool overflow into ender chest could never be retrieved (retrieval clicks were also cancelled) | Retrieval from containers explicitly allowed; only *insertion* is denied |
| 3 | Item loss / soulbound leak | Dying and logging out at the death screen left the tool in the respawn-trust map forever (lost on rejoin; slow in-memory leak) | PlayerQuitEvent restores trust immediately into the (post-death empty) inventory |
| 4 | Currency exploit (critical) | Pay-for-nothing: purchases withdrew currency, overflowed into the ender chest, and if that was full too the items were silently destroyed | New `ItemDelivery` helper: inventory→ender-chest delivery with full snapshot rollback + automatic refund ("purchase.no-space") — applied to shop, generators, spawners, generator harvest |
| 5 | Runtime exception | Free (0-price) catalogue entries/upgrades called `withdraw(0)` which throws by economy contract | All four purchase paths + upgrade path treat price 0 as free |
| 6 | Item dupe | Pistons could push a registered generator/spawner — registry desynced from the world, old spot would mint a second core item when reoccupied/broken | Piston extend/retract involving registered blocks is cancelled |
| 7 | Theft | Generators outside islands were harvestable/breakable by anyone (break returned a fresh core item to a stranger) | Placer-ownership enforced on break + harvest (coremc.island.bypass overrides) |
| 8 | Island grief | Buckets (lava/water), item frames/armor stands, and passive-entity damage were unprotected inside islands | Bucket empty/fill, hanging place/break (incl. projectiles), and EntityDamageByEntity for non-Monster victims now respect island protection |
| 9 | Config edge | `border-size + tiers * step` could exceed spacing/2 | Effective border is clamped to the grid cell at load with a warning |
| 10 | Race condition | Pre-login loads ran off the I/O worker; a quick reconnect could beat the pending quit-save and reload stale data | Pre-login load now runs *on* the I/O worker (4s timeout with safe fallback), serialised behind the quit-save |
| 11 | NPE robustness | Username index entries with null values escaped as NPE past the IllegalArgumentException guard | Null-guarded |
| 12 | Permissions | `coremc.command.money` unregistered → `/money` de-facto op-only; `coremc.admin.money` missing from the wildcard children | Both registered with correct defaults |
| 13 | Messages | Entire `shop:*` message group was orphaned under `placeable:` (missing `shop:` header) → "Missing message: shop.bought" etc. at runtime (found live during journey testing) | `shop:` header restored; full used-vs-defined key audit now shows 0 missing |

### Regression check
Every fix was reviewed against its neighbouring paths (GuiService click cancellation order, GUI classes, island listeners, purchase call-sites) and the whole pack re-compiled through CI. The player journeys below exercise: island create/team/delete, role select, OmniTool grant, currency admin, shop purchase, island upgrade purchase, restart persistence.

### Live journey verification (Paper 1.21.11, mineflayer bots)
- NEW PLAYER: welcome → create island → role select (+OmniTool granted) → /money visible to non-op → grant 50000/500/100 via console → buy Iron Sword in /shop gear (250 Money, delivered) → buy border upgrade tier 1 (10 Sky Tokens → 75×75 live) → balances assert (90 tokens, 49,750 money) ✓
- RESTART: graceful stop ("all player data saved, all tasks cancelled") → rejoin → island at 75×75, role Miner (level 1), 90 Sky Tokens, OmniTool in inventory ✓
- ADMIN: console grants to online *and* offline players via username index ✓
- MULTIPLAYER: create island → invite → accept (`Members (1/3)` in /is info) → leave → delete (two-step) → all island files/associations cleaned, no ghost data ✓
- No CoreMC exceptions logged across ~10 bot sessions and 3 server restarts.

### Limitations documented
- Platform blocks of a deleted island remain in the world (by design).
- Invites are in-memory only (standard; expire on restart).
- Protection denial for entity damage shares the 2s "protected" throttle with build denial.
