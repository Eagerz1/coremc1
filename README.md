# CoreMC

CoreMC is the core plugin for the CoreMC Skyblock server. This repository
contains the complete from-scratch rebuild of the plugin.

- **Platform:** Paper (currently targeting Paper 1.21.x — built and tested against Paper 1.21.11)
- **Language:** Java 21
- **Build:** Maven (`mvn package`) — one consistent build system for the project

## Current feature set (v0.2.0)

| Area | Details |
|---|---|
| Plugin lifecycle | Service-based bootstrap: task registry, config, messages, player data. Clean enable/disable ordering, safe shutdown. |
| Persistent player profiles | One YAML file per player (`plugins/CoreMC/profiles/<uuid>.yml`). Loaded asynchronously at pre-login, saved on quit, on a scheduled autosave, and on shutdown. Atomic writes (temp file + move). Survives restarts. |
| First-join welcome | Branded welcome message on a player's first ever join (messages.yml). |
| `/coremc` | `info` (default): version, server, loaded profiles, tracked tasks, uptime. `reload`: reloads config + messages and re-applies the autosave interval without leaking tasks. `help`. |
| `/profile [player]` | Shows your own profile; `coremc.command.profile.others` for viewing an online player's profile. |
| `/heal [player]` | Restores health, hunger and saturation, extinguishes fire. Self-heal: `coremc.command.heal`; targeted (incl. console): `coremc.command.heal.others`. |
| Branding | Messages use standard Minecraft `&` colour codes (MiniMessage is intentionally not used). Brand prefix: `&b&lCOREMC &8» &f`, configurable in messages.yml. messages.yml merges bundled defaults, so upgrades never show "missing message" for your old config file. |

Note on command permissions: on Paper, commands whose permission you lack
(e.g. `/heal` for non-ops) are removed from the client command tree and
report as "Unknown or incomplete command" — standard EssentialsX-style
behaviour; Spigot instead sends the configured permission message.

## Building

```bash
mvn package          # produces target/CoreMC-<version>.jar
```

`paper-api` is a `provided` dependency; tests run via JUnit 5 + Surefire.

### Sandbox/CI note

The development sandbox used for this rebuild has no Maven Central access,
so `build.sh` reproduces the Maven build offline (Eclipse batch compiler +
locally mirrored dependencies) and runs the same JUnit tests. It is not a
second build system — `pom.xml` remains the single source of truth and
`build.sh` only mirrors it. See `TESTING.md`.

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

See `TESTING.md` for the full verification story. Summary:

- 17/17 unit tests pass (profiles model, YAML store round-trip incl.
  corrupt-file handling, colour/message utilities, time formatting).
- End-to-end tested on a **live Paper 1.21.11 server** with real players
  (offline-mode protocol bots): join → branded welcome → `/profile` →
  quit-save → rejoin persistence → full server restart persistence →
  permission denial paths → triple-reload leak check → clean shutdown.

## Roadmap

Skyblock gameplay (islands, teams, upgrades, economy) builds on top of
this core in the next iterations.
