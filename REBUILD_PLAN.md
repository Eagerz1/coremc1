# CoreMC Rebuild Plan

## Goal and guardrails

Rebuild CoreMC as a reliable, player-facing Paper plugin while preserving the current gameplay feature set and existing player/island data. Work in small vertical slices on `codex/coremc-dev` in the existing `Eagerz1/coremc1` repository. Keep this branch isolated from other in-progress checkouts.

Do not change live server data, publish releases, or discard existing data. Before changing behavior, capture the current config and persistence contracts and define a safe migration path. Keep player saves, island state, economy transactions, GUI item identity, and Bukkit main-thread rules as critical invariants.

## Starting point

The repository is already a substantial single-plugin implementation: Java 21, Maven, Paper 1.21.11, service-based startup, YAML player and island stores, configuration-driven content, JUnit sources, GitHub Actions, and a Mineflayer/RCON journey. Existing systems include player profiles and currencies, islands, roles and Omni-Tool progression, generators, spawners, enchantments, shops, crates, GUI handling, and protection.

The checkout contains 111 production Java files and 26 test source files, with 143 test annotations found by static source inspection. The docs previously claimed different test counts. A static source scan found 143 JUnit test methods, and the Maven workflow runs `mvn -B verify`; this rebuild has not executed that suite. The live Paper journey is configured as 12 phases.

## Phases

| Phase | Scope | Acceptance criteria |
|---|---|---|
| 0. Baseline and contract | Record supported Paper/Java versions, commands and permissions, config defaults, player/island schemas, and current CI/journey behavior. Reconcile documentation claims with executable evidence. | Baseline build and journey results are recorded; schemas and gameplay guarantees are documented; no unexplained mismatch between docs and checks. |
| 1. Core foundation | Tighten lifecycle/shutdown ordering, config validation and reload behavior, persistence boundaries, and actionable diagnostics. | Enable/disable/reload leaves no tracked tasks or cached players; malformed config reports actionable errors; old player/island data loads without losing fields. |
| 2. Economy and player progression | Make currency, XP, Omni-Tool, role, and island reward rules explicit and consistent. | Purchases are atomic from the player's perspective; failed delivery refunds correctly; XP and stats persist; repeated events cannot double-award. |
| 3. Island gameplay | Consolidate island creation/grid assignment, membership, permissions, protection, upgrades/buffs, progression, and void rescue invariants. | Create/invite/accept/leave/delete and return flows persist; owner/member/outsider protection is covered; upgrades survive restart; rescue does not strand or kill players. |
| 4. Items and progression systems | Standardize custom item identity, placement/break behavior, reward accounting, and unlock rules for Omni-Tool, enchantments, generators, and spawners. | Forged or moved items cannot bypass checks; placed items restore exactly once; progression is deterministic under stated rules. |
| 5. Crates, shop, and GUI security | Harden reward references, key consumption, purchase handling, and hostile click/drag paths. | Each crate open consumes one key; pity and rewards persist; invalid references fail safely; unauthorized GUI actions cannot steal items or trigger purchases. |
| 6. Compatibility and release readiness | Define supported upgrades and validate the packaged artifact and release workflow. | A prior-data fixture upgrades without loss; packaged resources and `plugin.yml` are correct; clean Paper boot and full journey pass; releases use a freshly built artifact. |

## First vertical slice

Implement and document **island creation → persistence → clean restart → return to island**. This crosses command/UI entry, world and grid allocation, player/island storage, teleport safety, shutdown, and startup. It should produce one observable, end-to-end player outcome and establish patterns reused in other systems.

## Visual and interaction conventions

Keep item lores readable in Minecraft's pixel font: uppercase text, bold high-contrast headings, clear ability lines, and spacing between sections. Use a class-colored heading with white ability lines and a red warning/cooldown line, or colored category headings with white uppercase bullets and blank lines between sections.

## Collaboration and repository

Hermes audited the clean baseline read-only and recommended the phases and first slice above. The canonical remote already exists at [github.com/Eagerz1/coremc1](https://github.com/Eagerz1/coremc1); do not create a duplicate. Publish the finished work to this repository after the GitHub account connection is restored; the user has authorized publication as part of this objective.




## Current progress

- Baseline audit completed with Hermes against the clean `codex/coremc-dev` checkout; the project already uses the existing `Eagerz1/coremc1` repository.
- First phase 1 slice underway: island creation now journals a recovery marker before world generation, retries incomplete creation during startup before publishing the registry, keeps island-world protection closed while saved data is loading or unavailable, and refuses grid allocation until the persisted index is ready.
- Recovery replays now replace the starter chest inventory before inserting starter items, preventing duplicate kits when startup retries generation after a crash.
- Startup validates persisted island IDs, grid cells, owners, and member assignments before recovery or index publication; conflicting data fails closed rather than silently overwriting lookups.
- Profiles for players who joined during island loading are reconciled after the registry publishes, and associations are retained only when the authoritative island record lists that player.
- Batched island saves now snapshot mutable model data on the main thread and write the detached YAML snapshot asynchronously, avoiding concurrent iteration of live membership/progression maps.
- Player profile methods now guard shared mutable state during serialization and return detached collection views. Autosave work is serialized through the player I/O queue, dirty flags survive edits during writes, and the shutdown snapshot is queued after older writes.
- Economy balance deposits, withdrawals, and sets now hold the profile lock across validation and mutation so concurrent updates cannot overwrite one another.
- Crate opening holds the same profile lock across currency-cap validation, pity updates, key consumption, and payout, preventing a concurrent balance change from consuming a key without a reward.
- Item shop purchase/sale, token exchange, generator purchase, and spawner purchase flows hold the profile lock over debit, delivery, payout, and any refund.
- CoreMC GUI actions now ignore clicks cancelled by other plugins and dispatch at the highest event priority; existing menu click cancellation and top-slot drag protection remain in place.
- Token-shop entries priced at zero now exchange without calling the positive-amount withdrawal API; payout overflow checks remain active.
- Island records gain one optional `creation-pending` YAML field. Older records default to `false`; existing island fields remain unchanged.
- Production packaging succeeded with Maven on JDK 24 targeting Java 21 after island recovery, ordered persistence, transactional reward flows, and GUI event hardening. Test sources and test execution were skipped for this build; no tests were added or run.
- GitHub CLI authentication is currently unavailable, so changes cannot yet be pushed to the existing repository. Recheck access before publishing.
- Generator/spawner placement registry entries are now written immediately when a registered block is placed or removed, reducing the crash window where a saved world block could outlive its CoreMC ownership/drop record.
- Placeable break payouts now validate the next-tick block state and are resolved before shutdown cancels tracked tasks; cancelled-break restoration retains its in-memory record when a disk write fails, and shutdown retries the full atomic registry save up to three times.
- Placeable registry keys now encode world names into YAML-safe IDs. The loader reads legacy dotted-path keys and rewrites recoverable entries into the new format; watchdog and island upgrade consumers read the world name from each placement record.
- Malformed YAML and invalid, conflicting, partial, or unknown placeable rows now fail plugin startup before the registry can be treated as empty; shutdown writes are blocked after an unreadable registry so the original file remains intact.
- Latest production package compiled all 111 Java sources for release 21 and produced `target/CoreMC-0.11.0-SNAPSHOT.jar`; Surefire and test-source compilation were skipped.
- Player profiles now round-trip unknown top-level YAML fields and preserve a higher schema version so an older plugin will not silently strip fields introduced by a newer build.
- Island creation error text now explains that a durable pending creation is retried at restart and tells players how to request an earlier admin retry.
- Placeable registry writes now report failure, roll back their in-memory mutation, and prevent placement, pickup, or affected explosions from proceeding when persistence fails; explosion cleanup uses one batched write.
- Player profile loading now treats an invalid optional island UUID as a missing association, allowing other saved balances and progression to remain available for authoritative island reconciliation.
- Island deletion now commits retired-cell and island-ID tombstones atomically before removing live indexes; startup discards stale files by island ID even when configuration changes. Retired-cell read/write failures fail closed, and failed island snapshots are re-queued for retry.
- Island shutdown now places a single bounded retry behind queued saves, using main-thread snapshots, and logs any dirty records that still cannot be persisted.
- Offline profile mutations now retain uncached profiles in a pending-write map, so soft-currency admin changes and island-association cleanup are persisted immediately and retried after write failures; shutdown snapshots include pending offline profiles.
- Profile persistence now coordinates offline admin changes, island cleanup, reconnect loads, autosaves, and shutdown retries through pending offline profiles and the serialized writer; timed-out pre-login loads roll back safely while preserving any queued mutations.
- Enchant and Omni-Tool upgrades now serialize their level check, debit, and grant on the player profile; island upgrades and buffs serialize their tier check, debit, and grant on the island record.
- Registered placeable breaks now suppress vanilla drops at HIGHEST priority and defer the CoreMC item until the following tick. Cancelled breaks restore the registry; if that disk write fails, the in-memory placement remains available for shutdown persistence.

- Pending island creation areas are now included in protection lookup and reject build/interact actions until durable publication; failed marker writes clear the pending protection entry.
- Island kicks now remove the member from live access immediately, persist the detached island snapshot asynchronously, and only confirm after save; failed saves restore the member and report the failure. The command and members GUI handle the asynchronous result.
- Hermes reviewed the pending-area flow and found it correctly connected. The async kick follow-up review is pending.

- The asynchronous kick now reserves the target against island creation and invite acceptance until persistence completes. The members GUI reports synchronous errors, waits for saved completion, and avoids reopening for an offline owner.
- Hermes reviewed the pending-area protection and the kick flow, including the pending-target reservation and GUI result handling.

- Protection keeps pending creation areas closed through the final-save interval by checking pending registry membership, even after the persisted creation marker is cleared for the commit snapshot.

- Hermes reviewed the final-save protection fix and confirmed pending registry membership remains fail-closed until publication, including the successful commit boundary.

- Production packaging succeeded after selecting the installed JDK directly: all 111 Java sources compiled with release 21 and 	arget/CoreMC-0.11.0-SNAPSHOT.jar was rebuilt. Test-source compilation and tests were skipped; no tests were added or run.


- Hermes' final review found the purchase journal recovery ordering coherent. If an in-flight purchase save outlasts the island shutdown wait, the journal remains for recovery on the next startup; this is intentionally fail-closed and should be visible in shutdown diagnostics.

- Island records now write schema version 6 and preserve unknown top-level YAML fields when loaded and saved, matching the profile compatibility policy while keeping legacy missing-version files readable.

- Architecture remake started with IslandPersistence: the existing IslandService remains the compatibility facade, while the island store and ordered I/O executor now have a replaceable owner. Existing YAML storage and queue ordering are unchanged; the production package compiled all 113 Java sources.

- Island creation now delegates from the existing service facade to IslandCreationCoordinator; it owns owner/cell reservations and preserves marker save, main-thread world generation, final save, index publication, reservation release, and profile association order. IslandPlatformBuilder is shared by normal creation and startup recovery. Hermes reviewed the extraction and the original association ordering has been restored.

- Island ownership, member, ID, and cell maps now live in IslandRegistry; IslandService delegates lookup and writes while retaining the effective-border policy. Hermes identified and the extraction fix preserved upgraded-border lookups, as well as durable tombstone-before-removal ordering.

- Membership/invite operations now delegate to IslandMembershipCoordinator; it owns invite and pending-write reservations while IslandService retains the public methods and result enums. Leave/kick still persist detached snapshots and profile-association cleanup on the ordered island queue, then complete or roll back on the main thread.

- Upgrade/buff purchase commit and startup journal replay now share IslandPurchaseCoordinator. The existing facade validations and journal YAML schema stay in place; the coordinator retains exact-balance replay, profile-before-island durability, journal clearing last, and the delete lock until a failed transaction is recovered.

- Retired-cell and deleted-island tombstones now live in IslandTombstoneStore, retaining the existing deleted-cells.txt line format and atomic write/rollback behavior. Startup filtering stays in the facade for now and uses the extracted store's retirement checks.

- Island deletion now delegates to IslandDeletionCoordinator; it persists tombstones before registry removal, then purges invites/activity, rescues online players, and queues profile-association cleanup before file deletion on the island I/O lane.

- The custom-enchant block and kill drop lifecycle now lives in EnchantDropPipeline. The existing EnchantEngine methods remain as compatibility facades; smelting, bonus rolls, magnet collection, depth/material filters, and cloned kill extras retain their prior ordering and behavior. Clean production packaging compiled all 125 sources; tests were skipped.

- Cooldowns, combo stacks, random rolls, temporary boosts, and quit cleanup now live in EnchantRuntimeState shared by the engine and drop pipeline. The Bukkit recursion guard remains in the engine, and existing public engine methods and profile enchant IDs are unchanged. Clean production packaging compiled all 126 sources; tests were skipped.

- Enchant currency, souls, configured reward tables, and item delivery now live in EnchantRewardPipeline. Existing EnchantEngine entry points delegate to it, preserving multiplier order, reward IDs, overflow drops, and dirty-profile behavior. Hermes reviewed the extraction; clean production packaging compiled all 127 sources, with tests skipped.

- Shared combo, temporary boost, haste, recovery, and overdrive effects now live in EnchantEffectPipeline. EnchantEngine keeps its public facade signatures, including legacy unused parameters, and proc/cooldown order is unchanged. Hermes reviewed the extraction; clean production packaging compiled all 128 sources, with tests skipped.

- Player profile cache, pre-login rollback, online/offline dirty handling, username indexing, autosave, ordered shutdown, and the single I/O queue now live in PlayerProfileLifecycleCoordinator. PlayerDataService remains the constructor-compatible public facade. Hermes reviewed lifecycle ordering; PlayerProfile schema v7 and YAML storage are untouched. Clean production packaging compiled all 129 sources; tests were skipped.

- CoreMCRuntime now owns the complete service graph and the existing enable/reload/shutdown sequence; CoreMCPlugin remains the Bukkit entry point and getter facade. Listener, command, data-load, and shutdown order were reviewed by Hermes and kept intact, including post-shutdown getter state. Clean production packaging compiled all 130 sources; tests were skipped.

- Deployable identity and placement persistence now have dedicated PlaceableIdentity and PlaceableRegistryStore owners behind PlaceableService. The `coremc:placeable` `GEN:`/`SPW:` payloads, placeables.yml v1 schema, legacy key parsing, atomic writes, and fail-closed rollback behavior are unchanged. Hermes reviewed the extraction; clean production packaging compiled all 132 sources, with tests skipped.

- PlaceableListener now acts as the Bukkit adapter for PlaceableLifecycleCoordinator, which owns placement, break recovery, shutdown resolution, explosions, pistons, spawner setup, and generator harvest. Hermes reviewed priorities, cancellation behavior, one-item recovery, and buff ordering; clean production packaging compiled all 133 sources, with tests skipped.

- SpawnerWorldRuntime now owns vanilla tile configuration, entity snapshot caching, and the loaded-chunk liveness watchdog behind SpawnerService's existing API. Spawn potentials, tier aliases, delay clamping, and watchdog timing remain unchanged. Hermes reviewed the extraction; clean production packaging compiled all 134 sources, with tests skipped.

- CrateOpeningCoordinator now owns the open transaction behind CrateService.open: profile lock, weighted/pity selection, item pre-resolution, currency cap check, pity persistence, key consumption, and reward grant. The `crate-pity:<id>` profile stat and `coremc:crate-key` identity are unchanged. Hermes reviewed transaction ordering; clean production packaging compiled all 135 sources, with tests skipped.

- ShopTransactionCoordinator now owns purchases and sales behind ShopService's existing methods. Catalogue YAML, public service signatures, withdrawal/refund/delivery ordering, payout rounding, island sell multiplier, and plain-stock filters are unchanged. Hermes reviewed transaction ordering and found no semantic drift; clean production packaging compiled all 136 sources, with tests skipped.

- SpawnerProgressionCoordinator now owns kill caps, wild/spawner kill progression, ancient bonuses, and kill rewards behind SpawnerService's existing API. Kill keys, profile stats, reward ordering, island XP eligibility, and tier fanfares are unchanged. Large admin kill grants use a synchronized bulk profile update instead of incrementing once per kill; Hermes reviewed compatibility. Clean production packaging compiled all 137 sources, with tests skipped.

- RoleProgressionCoordinator now owns the shared role XP award path behind RoleService. Category and Universal routing, multiplier order, role/tool XP parity, profile fields, cap/curve behavior, dirty marking, and level-up messages are unchanged; all category listeners still use the same main-hand OmniTool gate. Hermes reviewed the flow; clean production packaging compiled all 138 sources, with tests skipped.

- GeneratorLifecycleCoordinator now owns generator purchases, harvesting, cooldowns, island yield/rare rewards, and recovery-item minting. GeneratorService remains the catalogue/item facade; PlaceableLifecycleCoordinator retains generic registry, ownership-on-break, piston, explosion, and spawner behavior. `GEN:<id>` identity, `placeables.yml` storage, purchase refunds, ownership/bypass, cooldown key/order, and yield/buff order remain unchanged. Hermes reviewed construction order and flow; clean production packaging compiled all 139 sources, with tests skipped.

- SpawnerLifecycleCoordinator now owns tier item minting, purchases, placement-time world configuration, and break recovery behind SpawnerService. Kill progression and tile/watchdog behavior remain in their dedicated coordinators; legacy plain-mob aliases, `SPW:<tier-id>` identity, purchase/refund ordering, and delay/buff calculations are unchanged. Hermes reviewed the lifecycle; clean production packaging compiled all 140 sources, with tests skipped.

- IslandMobSpawnCoordinator now owns natural mob-rate boosts, cap admission and census repair, death counts, and deletion cleanup. The existing `NATURAL`/`CHUNK_GEN` scope, tier-zero vanilla behavior, twin-cap rule, island census, and listener priorities are unchanged. Hermes reviewed the event flow; clean production packaging compiled all 141 sources, with tests skipped.

- IslandProgressCoordinator now owns team-action admission, lifetime island stats, weighted XP, level updates, and island XP multipliers behind IslandProgressService's event adapter. Schema v6 fields and all stat/config keys, member-location checks, placeable exclusion, event priorities, and one-minute dirty flush remain unchanged. Hermes reviewed the flow; clean production packaging compiled all 142 sources, with tests skipped.

- IslandActivityRewardCoordinator now owns shared chance/buff calculations, mining and logging reward procs, currency grants, and configured reward-table parsing/delivery. Track IDs, reward-table schema, independent entry rolls, multiplier ordering, cache reload, and the spawner-safe ITEM/XP-only restriction are unchanged. Hermes reviewed the flow; clean production packaging compiled all 143 sources, with tests skipped.

- Farming upgrade procs now use IslandActivityRewardCoordinator after the existing mature-crop and team-island checks. Crop mappings, fortune/crop-yield/currency/haste order, buff scaling, and config IDs remain unchanged. Hermes reviewed the flow; clean production packaging compiled all 143 sources, with tests skipped.
