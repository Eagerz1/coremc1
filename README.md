# CoreMC

CoreMC is a Paper plugin (Java 21, Paper 1.21.x): the Skyblock core for the
CoreMC server. This is a ground-up rebuild — the current scope is the island
foundation: islands in a dedicated void world, schematic-based generation,
borders and the core island commands.

## Feature set (v1.0.0)

| Area | Details |
|---|---|
| Island world | Plugin-created void world (`coremc_islands`) — every chunk is empty void, islands are pasted block-by-block at grid positions. Persists across restarts. |
| Island grid | Islands spiral around the origin on a 200-block grid, each claiming a 100×100 square centred on its position. Slots are never reused, so a deleted island's leftovers never get pasted over. |
| Schematics | Hand-editable char-grid format (`plugins/CoreMC/schematics/<name>.yml`) — palette + layered rows. Ships a default starter island (pedestal, oak tree, torches, starter chest). Broken schematics fail validation loudly at load time, never half-paste. |
| Starter chest | Configurable `MATERIAL:AMOUNT` list, filled into the schematic's chest on creation. |
| Borders | Per-player vanilla world border centred on the island, sized to the claim; re-applied on join/world-change, cleared when leaving the island world. |
| Protection | Only the owner and members can build/interact inside a claim (blocks, containers, doors, buckets, fire, hanging entities, passive mobs). The void between islands is wilderness — nobody builds there. `coremc.island.bypass` (op) overrides. |
| Persistence | One YAML file per island under `plugins/CoreMC/islands/`, atomic writes, corrupt files are skipped with a warning instead of breaking the plugin. |
| One island per player | A player either owns an island or is a member of (at most) one. |

## Commands

| Command | Details |
|---|---|
| `/is create` | Claims the next grid slot, pastes the schematic, fills the starter chest and teleports you home. Fails if you already own or share an island. |
| `/is go` | Teleports you to your island (own, or the one you are a member of). |
| `/is invite <player>` | Owner-only. Invites an online player who doesn't already have an island. |
| `/is join` | Accepts your pending invite (expires after 5 min) and teleports you to the island. |
| `/is leave` | Members leave the island they joined. Owners must `/is delete` instead. |
| `/is delete` | Owner-only, two-step: the first call arms a 30 s confirmation, `/is delete confirm` deletes the claim, evicts everyone to the main world spawn and frees the slot. Pasted blocks remain in the void. |
| `/is help` | Command overview. |

Aliases: `/island`, `/isle`, `/block`. Everyone may use `/is` (`coremc.command.island`).

## Configuration

- `config.yml` — island world name, Y level, spacing, border size, border
  visibility, schematic name, delete-confirm and invite-expiry windows,
  starter chest contents.
- `messages.yml` — every user-facing string, `&` colour codes, with the
  bundled defaults as fallback for missing keys. Every message goes out
  prefixed with cyan-bold `COREMC >>>` (the `prefix` key); unknown
  subcommands answer `This command does not exist.` followed by the help
  list. New commands and features must reuse the same prefix.
- `schematics/default.yml` — the island layout (copied out on first run).

## Building

The canonical build is Maven (single source of truth):

```bash
mvn package          # produces target/CoreMC-<version>.jar
```

`paper-api` is a `provided` dependency; tests run via JUnit 5 (Surefire on
Maven, or the curated `TestRunner` used by the offline/CI builds).

CI (`.github/workflows/build.yml`) compiles, tests and packages on every
push to `arena/**` branches, then commits build status and artifacts back
to `sandbox/`. `build.sh` mirrors the Maven build offline for the
development sandbox; it is not a second build system.

## Layout

| Path | Purpose |
|---|---|
| `src/main/java/com/coremc/core/island` | Islands: model, grid, store, schematics, service, command, protection |
| `src/main/java/com/coremc/core/world` | Void world generator + island world service |
| `src/main/java/com/coremc/core/config` | Typed config + message service |
| `src/test/java` | JUnit tests + the offline `TestRunner` harness |
| `ci/` | CI build script and Paper/vanilla server-jar resolvers |
| `sandbox/` | CI-committed build status, packaged jars, and Paper test-server bits |
