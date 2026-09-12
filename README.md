# CoreMC

CoreMC is the core plugin for the CoreMC Skyblock server. This repository
contains the complete from-scratch rebuild of the plugin.

- **Platform:** Paper (currently targeting Paper 1.21.x — built and tested against Paper 1.21.11)
- **Language:** Java 21
- **Build:** Maven (`mvn package`) — one consistent build system for the project

## Current feature set (v0.11.0)

| Area | Details |
|---|---|
| Plugin lifecycle | Service-based bootstrap: task registry, config, messages, player data. Clean enable/disable ordering, safe shutdown. |
| Persistent player profiles | One YAML file per player (`plugins/CoreMC/profiles/<uuid>.yml`), loaded asynchronously at pre-login, saved on quit/autosave/shutdown with atomic writes and additive schema evolution. |
| Skyblock islands (`/island`, `/is`) | Per-player spiral-grid islands in a dedicated void world (`minecraft:islands`, natural spawning disabled), themed platforms, home/info/two-step delete, members/teams, settings & permissions GUIs. Per-owner YAML under `plugins/CoreMC/islands/`. |
| Island upgrades & buffs | Six upgrade categories (mining, fishing, farming, slaying, logging, island — border, member slots, mining cube, crop regrowth…) and 12 purchasable island buffs; all costs config-only, persisted, applied live. |
| Roles & Omni-Tool | Role select GUI (Miner/Lumberjack/etc.) grants a soulbound Omni-Tool; role XP/levels, per-role enchant grids (15 enchants each, PDC ids), and Omni-Tool upgrades (efficiency, fortune, auto-smelter) with full theft/dupe guards. |
| Spawners | 15 mob lanes × five variants (tiers I–IV **plus an Ancient variant**: named, glowing, toughened mobs; an Ancient kill grants **triple progress toward the next lane as one event**). Kill-gated unlocks, token purchases, real block spawners with correct NBT and a liveness watchdog. |
| Generators | Configurable generator blocks (market purchase, place, timed harvest, break returns exactly one core item, piston protection). |
| Crates | Six config-driven crates with physical PDC-tagged keys, weighted rolls, pity counters, preview GUIs; keys are consumed exactly once per open. |
| Shop & economy | Three currencies (Core money, Credits, Sky Tokens), `/shop` category GUIs, `/tokenshop` exchange, admin grant commands; every purchase is withdraw-then-deliver with overflow/refund safety. |
| Protection & security | Island build/break/bucket/hanging/entity protection, ownership checks on registered blocks, GUI click/drag theft sweeps, soulbound item guards, kill-cap anti-abuse, economy overflow checks. |
| First-join welcome, `/coremc`, `/profile`, `/heal` | Branded welcome; `/coremc info|reload|help`; profiles; self/other heal. |
| Branding | Standard Minecraft `&` colour codes only (MiniMessage is intentionally not used). Bundled YAML defaults merge into existing config files, preserving admin values. |

Note on command permissions: on Paper, commands whose permission you lack
(e.g. `/heal` for non-ops) are removed from the client command tree and
report as "Unknown or incomplete command" — standard EssentialsX-style
behaviour; Spigot instead sends the configured permission message.

## Building

```bash
mvn package          # produces target/CoreMC-<version>.jar
```

`paper-api` is a `provided` dependency; tests run via JUnit 5 + Surefire.

## CI

Two workflows run from `.github/workflows/` (no artifacts are ever committed
back to the repository):

- **build** — `mvn verify` on every push/PR; the plugin jar and Surefire
  reports are published as Actions artifacts.
- **journey-live-player-journey** — builds the jar, downloads Paper 1.21.11,
  and runs the automated mineflayer/RCON live journey; logs and plugin data
  are published as the `journey-evidence` artifact.

## Configuration

- `config.yml` — values that may need balancing (autosave interval, welcome toggle).
- `messages.yml` — every user-facing string and the brand prefix, `&` codes.

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `coremc.command.coremc` | everyone | `/coremc [info\|help]` |
| `coremc.command.coremc.reload` | op | `/coremc reload` |
| `coremc.command.profile` | everyone | `/profile` |
| `coremc.command.profile.others` | op | `/profile <player>` |
| `coremc.command.heal` | op | `/heal` |
| `coremc.command.heal.others` | op | `/heal <player>` |
| `coremc.command.island` | everyone | `/island [create\|teleport\|info\|delete\|help]` |
| `coremc.*` / `coremc.command.*` | op / children | parent nodes |

## Architecture

```
com.coremc.core          CoreMCPlugin — bootstrap & service wiring (no static access)
com.coremc.core.config   CoreConfig (typed config.yml), MessageService (messages.yml + branding)
com.coremc.core.player   PlayerProfile model, PlayerDataStore (interface),
                         YamlPlayerDataStore, PlayerDataService (lifecycle/cache/dirty
                         tracking/executor), PlayerListener
com.coremc.core.command  CoreMCCommand, ProfileCommand (executors + tab completers)
com.coremc.core.scheduler TaskService — every repeating task tracked and cancelled on disable
com.coremc.core.util     ColorUtil, DateTimeUtil
```

Design rules enforced from day one: single-responsibility services,
constructor injection, no static state, disk I/O off the main thread,
player-scoped state evicted after quit-save, all tasks tracked and
cancelled on disable, data flushed synchronously in `onDisable`.

## Testing

- **140+ JUnit unit tests** run under Maven Surefire (`mvn verify`):
  profile model and every schema migration, YAML persistence round-trips
  and corrupt-file handling, island grid/buff math, economy and GUI
  purchase logic, enchant/catalog parsing, spawner tier/progression math
  and crate roll/pity rules.
- **Live journey on Paper 1.21.11** — `journey/run.mjs` drives two
  offline-mode bots plus RCON through 12 phases (economy, shop, island
  upgrades/buffs, spawner unlocks + real spawner kills incl. Ancient
  triple-progress, enchants/Sky Keys, crates incl. key consumption and
  pity, outsider protection, generators, void rescue, and persistence
  across a clean server restart), finishing with a zero-ERROR log audit.
  CI runs it headlessly; see `TESTING.md` for details and the full phase
  table.
