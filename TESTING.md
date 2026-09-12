# Testing CoreMC

CoreMC is verified at three levels:

1. **Unit tests** — JUnit 5, run by Maven Surefire during `mvn verify`.
2. **Headless Paper boot** — the plugin enables/disables cleanly on a real
   Paper 1.21.11 server (covered by the live journey below).
3. **The automated 30-step live player journey** — two real Minecraft
   clients (mineflayer bots) plus RCON drive Paper headlessly and assert
   every headliner flow, including hostile GUI interactions, combat,
   purchases, persistence across a full server restart, and a clean-log
   audit.

## Building and running the unit tests

Requirements: JDK 21, Maven 3.8+, network access to
`repo.papermc.io` and Maven Central on first build.

```bash
mvn verify          # compile + 100+ JUnit tests + target/CoreMC-<version>.jar
```

`paper-api` is a `provided` dependency. The jar is built under `target/`;
copy it into a Paper server's `plugins/` directory. The plugin is the
**single** deployable artifact — there are no companion plugins.

## The live journey (`journey/`)

`journey/run.mjs` is a fully automated end-to-end run against Paper
1.21.11. GitHub Actions runs it on every push (the
`journey-live-player-journey` workflow); all evidence (journey log,
server log, plugin data files) is uploaded as a workflow artifact named
`journey-evidence`.

### What it covers

| Phase | Coverage |
|---|---|
| 1 | First-join welcome, `/is create` world teleport, island ground/grid allocation, `/role` GUI and Omni-Tool grant |
| 2 | Economy admin grants land exactly; `/shop` gear/food buy + sell with exact balances; `/tokenshop` exchange |
| 3 | `/is upgrades`: all six categories render, border tier purchase debits exactly and persists |
| 4 | `/is buffs`: all 12 buffs render; mining-boost purchase debits exactly |
| 5 | `/spawners`: 15 mob lanes, locked I–IV + Ancient submenus, 25 real kills (summoned, hunted), unlock fanfare, hostile-GUI theft sweep, tier-I purchase + placement, **real spawner emission**, kill pays Core money + Sky Tokens |
| 5b | Ancient tier unlock (kill gate), purchase, placement; the spawner **awakens a named, glowing Ancient zombie**; killing it grants **+3 progress toward the next lane as one event** |
| 6 | Shift-right-click Omni-Tool panel, 15-slot miner enchant grid, treasure-miner purchase, platform mining mints exactly 2 Sky Keys |
| 7 | `/crates`: six-crate lineup, preview OPEN slot, two Sky crate opens (rolled reward then deterministic pity = 50 credits), each open consumes exactly one key, no-key refusal |
| 8 | Outsider cannot dig island blocks; block remains intact; branded protection denial |
| 9 | `/gens` market, cobble generator purchase/placement/harvest |
| 10 | Void rescue teleports the player home and prevents death |
| 11 | Graceful stop + clean boot: balances, role, kill stats, pity counter, dotted enchant id, island world/upgrades/buffs all persist byte-for-byte |
| 12 | Full-session audit: zero server `ERROR` lines and zero CoreMC warnings/exceptions |

### Running locally

```bash
# 1. build the plugin
mvn -B package

# 2. fetch a Paper 1.21.11 paperclip jar into journey-server/
mkdir -p journey-server
#    (the CI workflow .github/workflows/journey.yml shows the exact,
#     checksum-verified download via ci/resolve-paper.py)

# 3. assemble server dir, install harness deps, write deterministic config
#    (mirroring the workflow steps), then:
cd journey-server
node ../journey/run.mjs
```

Notes / vanilla semantics the harness accounts for:

- **Monster spawners obey the darkness rule** (like dungeon spawners and
  torches): they do not emit zombies in daylight. The journey pins both
  worlds to midnight with the daylight cycle frozen; the islands world
  has natural spawning disabled at creation (`setSpawnFlags`), so no wild
  mobs leak.
- Paperclip downloads/verifies the vanilla server jar and its libraries
  on first boot (needs normal internet; on GitHub runners this works
  out of the box).
- Offline-mode servers still fetch Mojang's yggdrasil public keys at
  boot. On a fully offline host this produces an unrelated `ERROR` log
  line; point authlib's hosts elsewhere (or run with normal internet).

## Configuration / data compatibility

- Bundled `config.yml`, `messages.yml`, `shop.yml`, `crates.yml`,
  `enchants.yml` and `themes.yml` are **merged** into any existing disk
  file on load: new defaults appear, admin-set values are never
  overwritten.
- Player profiles (`plugins/CoreMC/profiles/`), islands
  (`plugins/CoreMC/islands/`) and placeable registries
  (`plugins/CoreMC/data/`) are YAML on disk with atomic temp-file writes;
  schema evolution is additive and legacy files continue to load
  (covered by the profile/datastore unit tests and by phase 11).
