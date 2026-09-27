# CoreMC

CoreMC is a Paper plugin (Java 21, Paper 1.21.x): the Skyblock core for the
CoreMC server. This is a ground-up rebuild — islands in a dedicated void
world, schematic-based generation, borders and the core island commands,
a coin economy with a chest-GUI shop, the mob-spawner progression system
(essences, unique drops, relics, four spawner variants, island luck), a
connected Island Level + Island Mastery backbone, and chest-GUI menus for it
all — the double-chest island menu with mastery, buffs, members and invites,
plus the double-chest spawner menu, and the Core / Core+ / Core++ rank
ladder.

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
| Shop | 7-section chest GUI (`/shop`): Building, Farming, Mob Drops, Minerals, Food, Redstone, Nether & End — ~550 priced vanilla materials (gear is never sold; you craft it). Buy/sell with coins, shift-click for stacks/all, balance display, inventory-injection protection. Sections are either flat paginated pages, or grouped — Building is a category picker (Stone, Deepslate & Tuff, Dirt/Sand/Mud, Bricks/Quartz/Amethyst, Copper, Wood, Glass, Wool & Carpets, Terracotta & Concrete, Ocean & End, Nether Stone, Lights & Decor — up to 14 groups, two rows of seven) with each category paginating on its own. Coin balances persist (`balances.yml`). |
| Island top | `/is top` opens a category picker (small chest) with the three leaderboards — Solos, Duos and Teams (by team size) — each opening a 54-slot board with the top ten islands as owner heads (rank, points, team size and the season reward in the lore). Points: blocks broken or placed on your island 0.2 each, 1 per 5 minutes of play time, +500 per island upgrade, +5,000 per island core level (the core arrives soon). Points persist (`island-points.yml`) and die with the island. Each season change pays the leaders in webstore gift cards (GC) — see Island top rewards. |
| Tebex giftcards | With a Tebex webstore configured (`tebex.yml`, Plugin API secret key), `/giftcard` (`/gc`) links a webstore gift card and shows its number — click it to copy — with its live balance beside it, straight from the Tebex API. Island top season rewards arrive as freshly created gift cards through the same API. |
| Island top rewards | When a new season starts (`/season set <n>`), the island top leaders are paid in webstore gift cards — always to the island owner. Teams pay five places (100 / 75 / 50 / 35 / 25 GC), Duos three (100 / 75 / 50 GC), Solos five (100 / 75 / 50 / 30 / 25 GC). Each card is created through the Tebex Plugin API, linked to the owner (`/gc` shows it) and messaged to online owners immediately; a season is only ever paid once (`island-rewards.yml` remembers the last paid season), and an unconfigured Tebex leaves the season unpaid with a loud log line instead of eating the rewards. |
| Sell window | `/sell` opens an empty double chest: drop anything in and close it to get paid. Every listed item pays its shop sell price (times the rank multiplier), any other ordinary block pays `default-sell-price`, and custom progression items (unique drops, relics, spawner items) are returned untouched. A restart settles open windows, so items are never lost. |
| Vault economy | With [Vault](https://github.com/MilkBowl/VaultAPI) installed, CoreMC registers its coins as a Vault economy provider: any Vault-aware plugin (placeholders, shops, scoreboards, …) reads and spends the same `balances.yml` coins through the standard ServicesManager. Without Vault everything works unchanged (soft-depend; graceful no-op with a log line). |
| Placeholders | With [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) installed, CoreMC registers two expansions: `coremc` (`%coremc_slayer_essence%`, `%coremc_mining_essence%`, `%coremc_farming_essence%`, `%coremc_essence_total%`, `%coremc_mob_kills%`, `%coremc_coins%` — all comma-formatted) and `x` (`%x_currency%`, configurable via `x-currency` in config.yml). Without PAPI everything works unchanged (soft-depend). |
| Essence | Three virtual account currencies earned through active play (never items, never negative, persisted in `essence-balances.yml`): **Slayer** for mobs you kill yourself (melee or your own projectiles — passive deaths, Mythic auto-kill and `/kill` pay nothing; Normal 1 / Advanced 2 / Ancient 3 / Mythic 5 per mob, and a killed "4x Pig" stack pays 4x), **Mining** for natural ores, deepslate ores, obsidian and generator blocks (player-placed blocks are tracked in `placed-blocks.yml` and never pay — no place-mine loops), **Farming** for fully-grown wheat, carrots, potatoes, beetroot and nether wart, plus sugar cane, melons and pumpkins. Every kill also feeds a lifetime mob-kill counter. Rates live in `essences.yml`. |
| Island Level + Mastery | `progression.yml` defines a 30-level season backbone. Active farming, mining, fishing, slayer and spawner/industry actions award Island XP with rolling soft/hard caps, so passive systems do not dominate. Level-ups grant limited Mastery Points and Sky Tokens. `/is upgrades` opens the Island Mastery GUI: Farming, Mining, Fishing, Slayer, Industry, Island Core and active modules with live ✔/✖ affordability lines. Mastery nodes cost Island Level + points + money + Sky Tokens and unlock mechanics/capacity such as crop mutation hooks, Mining Cube layers, fishing caches, Elite Slayer hooks, generator bays and bigger spawner stacks. Island Core module slots unlock at levels 5/15/25; module earning/activation hooks are stored in `island-progression.yml` for the next gameplay layer. |
| Spawner variants | normal → advanced → ancient → mythic. Each variant spawns faster and in bigger bursts (rate 1–4×, count 2–6, nearby cap 8–24); Mythic spawners auto-kill their spawns and credit the drops to the island owner (but pay no Slayer Essence — that needs a player's own hand). `/spawner upgrade` opens the Upgrade GUI: a checklist of that tier's requirements with live ✔ / ✖ ticks and `(You have N)` shortfalls; clicking the button with every ✔ consumes money → essence → unique drops in that order (mob kills are a lifetime threshold, never spent), applies the upgrade and refreshes the hologram with the stack preserved. A ✖ click plays an error sound and names exactly what's missing. Requirements are lists of typed entries (`money`, `kills`, `essence`, `drop`) in `spawners.yml` — `upgrade-defaults`, with optional per-group and per-mob overrides — and scale per spawner in the stack, so a 2x stack pays twice. |
| Spawner items | Spawners are real items (BlockStateMeta + PDC): recoverable by breaking, re-placeable, variant preserved. Custom items match by PDC tag with a display-name fallback, and are never sellable in the shop as raw materials. |
| Spawner stacking | Identical spawners stack on one block: sneak-click a held spawner onto a placed one (same mob + variant, island members only, up to `settings.spawner.max-stack` = 64). The stack label floats above the cage as a persistent text-display hologram ("32x Pig Spawner [Normal]"), the spawn count and upgrade cost scale with the stack, and `/spawner info` shows the stack line. Sneak-break takes the whole stack as one "N x" item straight into your inventory (never a ground drop that can bounce into the void); a normal break takes exactly one spawner out and leaves the rest placed. Placing an "N x" item registers an N-stack in one go. Spawners only place on your own island (a clear message says so otherwise). |
| Mob stacking | Spawner spawns merge into counted entities — "4x Pig" — so big farms stay light on entities (`settings.mob-stack`: radius 5, max 1024). Killing a stack drops and credits the whole count: loot, XP and kill-reward rolls all multiply, and a count-1 replacement appears an instant later. Mythic auto-kill fodder dies as a whole stack with no replacement. |
| Island luck | `/spawner luck upgrade` buys island-wide luck levels (coins): the unique-drop chance rises from 10% to 50%. Survives restarts; removed with the island. |
| Island menu | `/is` opens the island menu — a double chest (54 slots) for island holders only (everyone else gets the deny line + help). Buttons: island info, go home, invite, members, border toggle, mastery, buffs, spawner progression, delete (two-click confirm), close. |
| Sub-menus | Island sub-menus protect their inventories and route clicks through services. Mastery uses a double-chest root/branch GUI; buffs, members, invites and core use compact chest menus. Purchases re-use service flows, so rules and messages never fork. |
| Legacy island upgrades | `upgrades.yml` still supplies the persisted claim-size/member-slot fields and timed buffs. The old generic upgrade GUI has been replaced by Island Mastery; Industry mastery nodes can now drive claim/member infrastructure effects without resetting old island data. |
| Core Buffs | Persistent Island Core module choices configured in `island-buffs.yml`, opened with `/is buffs` or from the Core GUI. Islands can unlock many buffs but only equip the active slots unlocked at Island Levels 5 / 15 / 25; swaps have a configurable cooldown (12h by default) and persist in `island-progression.yml`. The nine shipped buffs are Rich Veins, Slayer Frenzy, Deep Waters, Generator Overdrive, Core Discovery, Role Synergy, Momentum, Fortune Cycle and Core Surge. They use gameplay hooks rather than passive flat +5% stats: Rich Veins is Mining Cube-only, Slayer Frenzy doubles only safe kill progression/compatible OmniTool outputs, Momentum/Role Synergy require recent active play, and passive AFK automation does not trigger Core Discovery unless enabled in config. |
| Hourly server events | `events.yml` schedules one temporary server event per hour: exactly 15 minutes active, then 45 minutes normal gameplay by default. Rotation avoids immediate repeats, prevents overlap, recovers active/next state after restart where practical, shows an active BossBar with remaining time, and sends start / 5-minute / 1-minute / end announcements. Admins can use `/coreevent start <event>`, `/coreevent stop`, `/coreevent status` and `/coreevent next`; manual starts do not corrupt the automatic schedule. Event effects flow through the same centralized modifier service as Core Buffs, so overlapping Slayer/OmniTool sources are maxed/clamped instead of x4/x8. |
| Legacy island buffs | Timed island-wide boosts, owner-only (`upgrades.yml`): Green Thumb (crops grow ×2), Spawner Overdrive (spawner delays ÷2 — re-tunes placed spawners the moment it starts) and XP Surge (kill XP ×2), 30 min each for $1,500/$2,500/$1,000. Active buffs persist with their remaining time; expiry reverts lasting effects. |
| Member caps + kick | The member limit counts everyone on the island (owner included). A full island keeps pending invites alive, so a rejected joiner can join once a slot frees up. `/is kick <player>` and the members menu's two-click kick evict the member to the main world. |
| Spawner menu | `/spawner` opens a double chest (54 slots): the guide book, the island's luck upgrade, and all 15 mob spawners laid out by group. Clicking a mob buys it through the same flow as `/spawner buy`. |
| Rank ladder | Core → Core+ → Core++ (`ranks.yml`, order = worst to best, the ladder is validated to only improve as it climbs). Bought with coins via `/rank buy` — you always pay only the upgrade step. |
| Rank perks | Any rank: `/fly` (survival flight toggle) and `/echest` (your real ender chest, anywhere). Shop sells pay the rank multiplier. Every new season pays out the rank's season money — online players immediately, offline players on their next join (`/season`, admins start a season with `/season set <n>`). River keys (for the upcoming crates) are granted on first acquiring each rank and tracked per player. The chat-colour perks (dye colours, gradients, bold) are declared per rank and arrive with the upcoming chat system; skytokens (for island upgrades) build on this later. |
| Rank pricing | Core $75k (x1.05 sells, $100k/season, 2 keys), Core+ $250k (x1.2, $250k/season, 5 keys), Core++ $750k (x1.5, $500k/season, 12 keys) — each rank pays for itself within a season or two. Admins grant/clear with `/rank set <player> <rank\|none>`. |

## Commands

| Command | Details |
|---|---|
| `/is` | Opens the island menu GUI (double chest). Requires an island — otherwise the deny line plus the help list. |
| `/is create` | Claims the next grid slot, pastes the schematic, fills the starter chest and teleports you home. Fails if you already own or share an island. |
| `/is go` | Teleports you to your island (own, or the one you are a member of). |
| `/is invite <player>` | Owner-only. Invites an online player who doesn't already have an island. |
| `/is join` | Accepts your pending invite (expires after 5 min) and teleports you to the island. |
| `/is leave` | Members leave the island they joined. Owners must `/is delete` instead. |
| `/is delete` | Owner-only, two-step: the first call arms a 30 s confirmation, `/is delete confirm` deletes the claim, evicts everyone to the main world spawn and frees the slot. Pasted blocks remain in the void. |
| `/is kick <player>` | Owner-only: removes a member from your island and evicts them to the main world. |
| `/is help` | Command overview. |
| `/shop` | Opens the shop GUI (root: section picker + your balance). |
| `/shop <section> [group]` | Jumps straight into a section (by id or name, e.g. `/shop building stone`); grouped sections open their category picker, adding a group opens that category. |
| `/sell` | Opens the sell window — drop items in, close to get paid. |
| `/giftcard` `/gc` | Your webstore giftcard: number (click to copy) + balance; `link <code>` links one. |
| `/is upgrades` `/is mastery` | Opens Island Mastery: level progress, Sky Tokens, available Mastery Points, Farming/Mining/Fishing/Slayer/Industry branches and Island Core slots/modules. |
| `/is core` | Opens the Island Core overview, showing module slots unlocked by Island Level. |
| `/is buffs` | Opens the persistent Core Buff GUI: slot capacity, equipped/unlocked/locked states, swap cooldown and live ✔/✖ unlock requirements. |
| `/is top` | The island top category picker: Solos / Duos / Teams, each opening its top-ten leaderboard board. |
| `/spawner` | Opens the spawner menu GUI (double chest): guide, luck upgrade, every mob by group. |
| `/spawner list` | Every group, mob and spawner price. |
| `/spawner buy <mob>` | Buys a Normal spawner — coins plus the mob's unlock materials (essence + earlier mobs' drops). |
| `/spawner info` | Describes the spawner you look at: variant, spawn stats, stack size, next upgrade cost. |
| `/spawner upgrade` | Opens the Upgrade GUI for the spawner you look at (owner/member of its island only): current stack, target variant with the live ✔/✖ requirement checklist, your progress book, and the upgrade button. |
| `/spawner luck` | Shows your island's luck level and unique-drop chance. |
| `/spawner luck upgrade` | Buys the next luck level with coins. |
| `/rank` | Shows your rank: multiplier, season payout, River keys, perks, and the next rank with its price. |
| `/rank list` | The whole ladder with prices and perks. |
| `/rank buy` | Buys the next rank with coins (upgrade steps only), granting its River keys and perks. |
| `/rank set <player> <rank\|none>` | Admin: grant or clear a player's rank (clearing also strips flight). |
| `/fly` | Toggles flight (any rank). |
| `/echest` | Opens your ender chest anywhere (any rank). Aliases `/ec`, `/enderchest`. |
| `/season` | Shows the current season. |
| `/season set <number>` | Admin: starts a new season — pays out ranked players and sends the island top gift card rewards. |
| `/spawner give <player> <mob> [variant]` | Admin: hand out a spawner item. |
| `/spawner giveitem <player> drop\|relic <id> [amount]` | Admin: hand out unique drops and relics (essence is virtual — use `/essence give`). |
| `/essence` `/essences` `/ess` | Your essence balances: Slayer, Mining, Farming and lifetime mob kills. `/essence balance [player]` views another player. |
| `/essence give\|take\|set <player> <slayer\|mining\|farming\|kills> <amount>` | Admin (`coremc.admin.essence`): adjust virtual essence; `take` never drives a balance negative. |
| `/spawner setluck <player> <level>` | Admin: set a player's island luck. |
| `/coreevent status` `/coreevent next` | Shows the active server event timer or the next automatic event window. |
| `/coreevent start <event>` `/coreevent stop` | Admin (`coremc.event.admin` / `coremc.command.coreevent`): manually start or stop a configured hourly event without corrupting the automatic rotation. |

Aliases: `/island`, `/isle`, `/block`; `/store` for `/shop`; `/sp` for
`/spawner`; `/ranks` and `/tier` for `/rank`; `/ec` and `/enderchest` for
`/echest`; `/gc` for `/giftcard`; `/cevent` for `/coreevent`. Everyone may use `/is`, `/shop`, `/sell`, `/giftcard`,
`/spawner`, `/rank`, `/fly`, `/echest`, and read-only `/coreevent status` / `/coreevent next`
(`coremc.command.*`). The spawner admin tools need `coremc.spawner.admin` (op),
rank admin `coremc.rank.admin` (op), season admin `coremc.season.admin` (op),
and event start/stop needs `coremc.event.admin` (op).

## Configuration

- `config.yml` — island world name, Y level, spacing, border size, border
  visibility, schematic name, delete-confirm and invite-expiry windows,
  starter chest contents, and `x-currency` — what `%x_currency%` shows
  (`total`, `slayer`, `mining` or `farming`).
- `messages.yml` — every user-facing string, `&` colour codes, with the
  bundled defaults as fallback for missing keys. Every message goes out
  prefixed with cyan-bold `COREMC >>>` (the `prefix` key); unknown
  subcommands answer `This command does not exist.` followed by the help
  list. New commands and features must reuse the same prefix.
- `schematics/default.yml` — the island layout (copied out on first run).
- `tebex.yml` — Tebex webstore integration: the Plugin API secret key
  (creator panel: Integrations > Plugin API) turns `/giftcard` on; an
  overridable API base covers proxies and tests.
- `island-points.yml` — the island top points ledger (written at most
  once a minute).
- `island-rewards.yml` — the last island-top season that was paid out
  (guards `/season set` against double-paying a season).
- `shop.yml` — coin economy: starting balance, currency symbol, the
  default sell price paid by `/sell` for unlisted blocks, and buy/sell
  prices per section (validates against infinite-money loops, the same
  material appearing twice anywhere, mixed shapes, and bad group icons).
  Section pages are double chests: 36 items per page with back /
  previous / page / next / close controls. A section is either flat
  (`items:`) or grouped (`groups:` of up to 14 subcategories — the
  picker shows two rows of seven, each category paginates like a
  section); Building ships ~425 blocks across twelve categories.
- `spawners.yml` — the whole spawner progression: groups, mobs, drop
  items, spawner costs and per-tier upgrade requirements (lists of
  `{type: money|kills|essence|drop, amount, ...}` entries, via
  `upgrade-defaults` with optional per-group and per-mob overrides),
  variant behaviour (rate/count/nearby-limit/auto-kill), relic chance
  and island-luck maths. Broken entries refuse to load the system
  (every problem listed in the log) rather than half-work.
- `essences.yml` — the essence earning rules: Slayer Essence per mob
  by spawner variant, and the Mining/Farming block payout maps.
  Validated at startup like every other config.
- `progression.yml` — Island Level, active XP sources/caps, the 30-level
  season curve, Mastery Point/Sky Token level rewards, five Island Mastery
  branches, Industry capacity effects and Island Core module slot/module
  definitions. Broken entries disable mastery loudly rather than half-work.
- `island-buffs.yml` — persistent Island Core Buff definitions: unlock
  requirements, swap/fortune cooldowns, Momentum windows, buff durations,
  chances, reward tables, materials and modifier clamps. The loader validates
  all nine required buff ids and rejects bad clamps/materials loudly.
- `events.yml` — hourly server-wide event rotation: interval, 15-minute
  default duration, immediate-repeat avoidance, BossBar colours, messages and
  modifier values for the nine shipped temporary events. Invalid timing or
  missing required event ids disables the event service instead of half-loading.
- `essences-data` — balances live in `plugins/CoreMC/essence-balances.yml`
  (per player: slayer, mining, farming, lifetime kills), written
  atomically on every change, corrupt files start fresh. The store file
  is deliberately not named `essences.yml` — that name belongs to the
  earning rules above, and sharing it made the first balance save
  overwrite the rules and disable earning on the next boot.
- `placed-blocks.yml` — remembered player placements of mining-eligible
  blocks (the anti place-mine loop), atomic writes.
- `spawners-data.yml` — placed spawners + island luck (written atomically
  on every change).
- `island-progression.yml` — Island XP, Sky Tokens, purchased mastery
  nodes, active/owned core modules, discovery counters, source-cap windows,
  Core Buff swap time, Fortune focus, Momentum state, active buff expiries and
  per-buff cooldowns. Removed when an island is deleted.
- `server-events.yml` — active/next/last hourly event state, written atomically
  so restart recovery can resume an unexpired event and keep the next automatic
  start time stable.
- `upgrades.yml` — island upgrades (claim size, member slots) and timed
  buffs (crop growth, spawner boost, XP boost): icons, level prices,
  durations and multipliers. The whole file is validated at startup —
  unknown materials, bad prices, multipliers below 1, or a fully grown
  claim reaching the grid spacing disable the upgrade system with one
  loud log line (menus show "unavailable", member caps lift to the old
  uncapped behaviour) rather than half-working.
- `buffs.yml` — active island buffs and their expiries (written atomically
  on every change).
- `ranks.yml` — the rank ladder (order, prices, multipliers, season money,
  River keys, coming-soon chat perk flags). Validated as a whole at
  startup: a broken file disables ranks loudly (no perks, x1.0 sells) with
  every problem listed.
- `ranks-data.yml` — the current season plus every ranked player's rank,
  River keys and last payout season (written atomically on every change).

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
| `src/main/java/com/coremc/core/shop` | Coin economy + chest-GUI shop |
| `src/main/java/com/coremc/core/spawner` | Spawner progression: config, service, command, listener, store, menu GUI |
| `src/main/java/com/coremc/core/progression` | Island Level/Mastery, persistent Core Buffs, centralized gameplay modifiers, integration seams and hourly server events |
| `src/main/java/com/coremc/core/rank` | Rank ladder: config, store, service, commands, join listener |
| `src/main/java/com/coremc/core/island` (menus) | Island menu GUI, upgrade + buff services, buff listener, buff store |
| `src/main/java/com/coremc/core/config` | Typed config + message service |
| `src/test/java` | JUnit tests + the offline `TestRunner` harness |
| `ci/` | CI build script and Paper/vanilla server-jar resolvers |
| `sandbox/` | CI-committed build status, packaged jars, and Paper test-server bits |
