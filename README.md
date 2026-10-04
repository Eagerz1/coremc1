# CoreMC

CoreMC is the core plugin for the CoreMC Skyblock server. This repository
contains the active rebuild. It is not yet the complete planned server; the
honest feature-by-feature release gate is in [`docs/RECOVERY_MATRIX.md`](docs/RECOVERY_MATRIX.md).

- **Platform:** Paper (currently targeting Paper 1.21.x — built and tested against Paper 1.21.11)
- **Language:** Java 21
- **Build:** Maven (`mvn package`) — one consistent build system for the project

## Current feature set (v0.12.0)

| Area | Details |
|---|---|
| Plugin lifecycle | Service-based bootstrap: task registry, config, messages, player data. Clean enable/disable ordering, safe shutdown. |
| Persistent player profiles | One YAML file per player (`plugins/CoreMC/profiles/<uuid>.yml`), loaded asynchronously at pre-login, saved on quit/autosave/shutdown with atomic writes and additive schema evolution. |
| Skyblock islands (`/island`, `/is`) | Per-player spiral-grid islands in a dedicated void world (`minecraft:islands`, natural spawning disabled), themed platforms, home/info/two-step delete, members/teams, settings & permissions GUIs. Per-owner YAML under `plugins/CoreMC/islands/`. |
| Island upgrades & buffs | Six upgrade categories (mining, fishing, farming, slaying, logging, island — border, member slots, mining cube, crop regrowth…) and 12 purchasable island buffs; all costs config-only, persisted, applied live. |
| Roles & Omni-Tool | Role select GUI (Miner/Lumberjack/etc.) grants a soulbound Omni-Tool; shift-right-click opens the enchant menu directly, with role/tool progress, 15 role enchants and all three upgrades on one screen. PDC ids and theft/dupe guards preserve identity and progression. |
| Spawners | 30 regular mob spawners in a polished paginated GUI. Each has a kill requirement, direct Sky Token purchase, clear `✔`/`✖` states, real block-spawner configuration and a liveness watchdog. No tier or Ancient variants. |
| Generators | 24 material/crop generator blocks in a paginated market (purchase, place, timed harvest, break returns exactly one core item, piston protection). |
| Companions (`/companions`, `/pets`) | Six earnable companions bought with Sky Tokens, a collection/summon GUI, visible followers, 20 levels and category XP abilities. Ownership, equipped state and progress persist. |
| Daily missions (`/quests`, `/missions`) | Three deterministic per-player objectives drawn daily from ten mining, logging, farming, fishing and slaying missions. Live event progress, persistent claim state and earned Credit/Sky Token rewards. |
| Crates | Six config-driven crates with physical PDC-tagged keys, weighted rolls, pity counters, preview GUIs; keys are consumed exactly once per open. |
| Animated skins (`/skins`) | 30 animated tool skins (5 collections — Emberforge, Riftbound, Astral, Tidecaller, Overgrown — × one distinct 3D model per role: Miner, Logger, Fisher, Slayer, Farmer, Universal OmniTool) + 3 animated hats (Ember Crown, Rift Halo, Moonlit Cap), all original artwork. A skin is a **visual layer only**: it changes `custom_model_data` plus one cosmetic PDC marker — damage, enchants, upgrades, levels and identity PDC are never touched. Ownership persists in the profile by stable id; grant/revoke hooks feed crates (`type: SKIN` rewards), events and the store; nothing is auto-granted and purchased skins survive season resets by config. Hats are worn as a client-visible overlay entity riding the head (real helmet + armour fully preserved — vanilla has no cosmetic armour slot). |
| Shop & economy | Three currencies (Core money, Credits, Sky Tokens), a 219-item paginated `/shop` across Blocks, Food, Redstone, Misc and Ores, `/tokenshop` exchange, affordability markers and admin grant commands; every purchase is withdraw-then-deliver with overflow/refund safety. |
| Protection & security | Island build/break/bucket/hanging/entity protection, ownership checks on registered blocks, GUI click/drag theft sweeps, soulbound item guards, kill-cap anti-abuse, economy overflow checks. |
| Chat tags (`/tags`) | 20 config-driven cosmetic tags (`grinder`…`legend`) in a CoreMC GUI with owned/locked/selected/clear states. Ownership and selection persist by **stable id** (never display name); unlock hooks for crates (`TAG` reward), store and events; staff `/tags grant|revoke|check|clear|reload`. |
| Chat colours (`/chatcolour`, `/chatcolor`) | Eight solid colours + five gradients (legacy `§x` hex — **no MiniMessage**), bold toggle, live preview and reset. Only stable style ids + a bold flag are persisted; ownership is grantable by crates (`CHAT_STYLE` reward), store or staff. |
| Chat format | `<RANK> <TAG> Player: Message`, fully config-driven (`chat.yml`: format string, separators, rank table, GUI slots, safety limits). Rendered via Paper's async chat **renderer** at a configurable priority with `ignore-cancelled` — mutes/moderation always win, and chat is never duplicated. |
| Scheduled events | Core Hour runs for one hour every four hours, persists its schedule across restarts, shows a four-step boss bar and `/event` countdown, and doubles island XP, slaying money and Omni-Tool XP through their normal reward paths. |
| Player sidebar | Live balances, role and Omni-Tool levels, island level, quest shortcut and Core Hour state; toggle with `/hud`. |
| First-join welcome, `/coremc`, `/profile`, `/heal` | Branded welcome; `/coremc info|reload|help`; profiles; self/other heal. |
| Branding | Standard Minecraft `&` colour codes and legacy hex only (MiniMessage is intentionally not used). Bundled YAML defaults merge into existing config files, preserving admin values. |

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
| `coremc.command.skins` | everyone | `/skins` menu |
| `coremc.skins.admin` | op | `/skins grant\|revoke\|set\|clear\|resetseason\|reload` |
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

- **250+ JUnit unit tests** run under Maven Surefire (`mvn verify`):
  profile model and every schema migration, YAML persistence round-trips
  and corrupt-file handling, island grid/buff math, economy and GUI
  purchase logic, enchant/catalog parsing, spawner unlock/progression math
  and crate roll/pity rules.
- **Live journey on Paper 1.21.11** — `journey/run.mjs` drives two
  offline-mode bots plus RCON through 12 phases (economy, shop, island
  upgrades/buffs, regular spawner unlocks + real spawner kills
  triple-progress, enchants/Sky Keys, crates incl. key consumption and
  pity, outsider protection, generators, void rescue, and persistence
  across a clean server restart), finishing with a zero-ERROR log audit.
  CI runs it headlessly; see `TESTING.md` for details and the full phase
  table.
