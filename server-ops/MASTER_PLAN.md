# CoreMC external server master plan — Season One

**Scope**: the playable world around the existing CoreMC plugin, not a new Minecraft plugin or new gameplay systems. Decisions below deliberately privilege finishing one excellent SkyBlock experience over claiming to operate a network of empty games.

## 1. Experience pillar
Identity: **COREMC | Build Your Legacy**. The fiction is a ruined sky citadel that players rebuild through their islands. The season is six–eight weeks. Visual quality, immediate clarity, responsive menus, island identity and a genuine community should distinguish it from crowded, pay-to-win SkyBlock clones.

Entry loop: see spectacular but lightweight spawn → understand five roles → choose /role → /is create → perform first harvest/mine/fish/kill → claim /quests reward → see next goal → voluntarily return to spawn for leaderboards and community. The plugin handles gameplay, the external layer makes it legible.

## 2. Architecture and costs
**Launch (recommended):** one Paper 1.21.11/Java 21 game backend on already-paid Ravenport. Spawn can occupy an isolated dedicated lobby world **on that same backend**, but world creation and teleport logic must be configured with a trusted compatible world manager or built by ops; none is implemented here. Keep the existing `islands` void world intact. Players directly connect to Paper in online mode. Use no proxy and no extra monthly hosting until active demand proves it necessary.

**Scale-up (conditional, not launch requirement):** Velocity front end (online-mode=true), lobby Paper server, SkyBlock Paper server, independent ports, firewall/private network, shared identity and modern forwarding. See `velocity/`. Backend online-mode=false is safe ONLY when only a properly secured Velocity proxy can connect. Do not enable proxy forwarding without a firewall/private port. This is not a 2x performance upgrade: it has overhead.

**Budget planning estimates, NOT market quotes:** Stage 1 additional cash: €0–€30 for optional purchased artwork/build assets; already-paid hosting excluded. Paid assets require license review. Stage 2 incremental hosting: estimate €15–€50/month after obtaining quotes (proxy/lobby and backups). Stage 3 professional commission: roughly €100–€500+ for custom hub/artwork depending on scope, not justified for beta. Set aside funds for emergency backups and server continuation instead of a giant map commission.

## 3. Map worlds, navigation and construction
- Hub world `coremc_spawn` (separate from `islands`), visual walkable diameter ~90–110 blocks, static void surroundings, spawn at 0,100,0; always gated, protected, no economy farming there.
- Main plaza: 21×21 steps/portal nucleus; immediate view to sky horizon. Two obvious exits: start island and five role locations.
- Five spokes: Miner basalt/oxidized copper; Farmer moss/wheat; Fisher cyan prismarine/water; Slayer deep slate/red lanterns; Logger spruce/azalea. All cosmetic/training, **not replacement progression**.
- Season gallery/leaderboard hall: max 3 current podiums plus history. Pull authoritative ranking only from CoreMC, do not fabricate hologram placeholders as live data.
- Crate and skins gallery: visible themed displays, no high-frequency particles, one short click-to-preview path to native CoreMC crate GUI (requires confirmed NPC routing).
- Builds must provide WorldEdit schematics (`.schem`), coordinates, spawn/hotspot markers, daylight/night lighting, clipping proof and movement routes. A script in `tools/` generates only a basic prototype shell.
- **Do not add a PvP/minigame arena at launch**: the economy/roles are the actual game. A small seasonal event arena can come later only when CoreMC supports it and existing Core Hour stays owner of event timing.

## 4. Brand and resource pack
Use navy #0C1824, bright cyan #27B8D0, parchment #F5F4EE, gold #E7B453 and near-black #06121A. Pale/saturated elements serve information hierarchy, not permanent neon gradients. Header/footers should fit two or three lines. Keep the game's Minecraft bitmap readability.
Assets: 64x64 icon, transparent CoreMC wordmark, 2 banner crops, 5 district emblems, 3 ranks' marks, rule/season cards, 4 thumbnail templates, 1 progress diagram, Discord avatar/banner. Every asset needs a license note; no redistribution of third-party packs without rights.
Existing ItemsAdder pack is source of truth. Update its `contents/coremc` only through its existing checked-in asset pipeline. Regenerate `/iazip` once; use **one merged pack URL** rather than competing pack systems. Never overwrite model IDs or create a second `coremc` namespace. Test clients 1.21.11 first; cross-version promises need actual testing.

## 5. Ranks and permissions
Player groups: `default`, `core`, `coreplus`, `coreplusplus`; hierarchy Core++ → Core+ → Core → default. Staff groups `helper`, `mod`, `admin`, `owner` are separate. Rank logos can use three color/weight treatments, not more than one decoration around a name.
Donor package focus: prefix, chat color selection if unlocked, icon/badge and vanity particles only if implemented, optional cosmetic-only skin grants using existing CoreMC IDs. Do **not** attach money multipliers, keys with performance items, PvP power, extra islands, storage progression or season scoreboard boosts to paid donor groups.
Paid currency/lootbox packaging must be reviewed against competition, prize rules and Minecraft guidelines **before sales**, especially because CoreMC has **rewarded island rankings**. No random paid competitive advantages.
Permission design in `luckperms/`; additive CoreMC chat rank mapping in `coremc/`. Test TAB rank and in-chat rank display independently.

## 6. Visual UI ownership and hierarchy
**CoreMC exclusively owns**: sidebar `/hud`, chat renderer, Core Hour bossbar, quests, role menus, crates, lootboxes, cosmetics store, islands, coin balances, progression. Do not install plugins offering duplicate features.
**TAB exclusively owns**: TAB header/footer and player-list prefix/suffix (and optional, separately tested nametags). On launch disable TAB sidebar and TAB bossbar. The existing plugin creates a per-player scoreboard; TAB scoreboard teams are disabled in initial deployment to avoid a team/board collision.
**LuckPerms exclusively owns**: permission groups, rank prefix metadata, staff role inheritance; no Essentials-style generic permission wildcard.
**ItemsAdder exclusively owns**: generated resource pack hosting/merging and model dispatch. Confirm live ItemsAdder binary compatibility with 1.21.11 before deployment.

Text samples:
TAB header: `&b&lCOREMC  &8• &fSKYBLOCK`; footer: `&7Season One  &8•  &f/quests  &8•  &b/store`; sidebar: keep existing CoreMC money/Credits/Tokens/role/island/event/quests.
MOTD: `&b&lCOREMC &8| &fSkyblock Reimagined` + `&7Season One &8• &fBuild Your Legacy`.
No artificial announcement spam, flashing animations, intrusive scoreboard changes, or four overlapping boss bars.

## 7. Recommended external dependencies
**P0**: LuckPerms for permission groups, TAB for tablist header/footer, Paper built-in spark profiler for diagnostics, existing ItemsAdder/PlaceholderAPI only where used. **P1**: FAWE (staff build tool only), CoreProtect (if block audit is needed in shared spawn worlds), Chunky (only if ordinary generated worlds require pregeneration), a credible anticheat after false-positive testing, backups. **P2**: Citizens NPCs or DecentHolograms only after verified 1.21.11 compatibility and an explicit safe binding from NPC click to actual CoreMC commands. PlaceholderAPI expansions are a dependency check, not an automatic install-all.
Many features are already in CoreMC: avoid competing crate/skyblock/discord/scoreboard/chat/missions/economy plugins. Audit dependency licensing and plugin exact target versions before installing on production.

## 8. Website, Discord, shop and operations
Website pages: Home (play address and screenshots), How to Play (one-screen onboarding), Season, Ranks/Cosmetics (complete prices), Rules & Appeals, Support/Refunds, Privacy, Changelog. Avoid fake testimonials and dummy stats.
Discord: start-here, rules, announcements, updates, changelog, known-issues, questions, suggestions, help tickets, reports, community media and giveaways. Discord bot already exists in another repo; coordinate rather than adding duplicate bots. Staff tools and Discord relays should never trust in-game display names as identity.
Operations: RCON private/disabled by default; unique panel password + 2FA if provider offers, **no secrets in GitHub**, 3-2-1 backup principle with off-host encrypted copies, every-week restore drill, per-release rollback procedure, moderation logs visible only to staff, incident response protocol, change freeze 24h pre-launch.
Privacy: logs contain IPs and usernames; limit retention and access and publish a policy. Avoid exposing player private data in public metrics.

## 9. Engagement and launch
Alpha: 5–10 hand-picked testers; Beta: 15–30 opt-in community players, no paid rank pressure. The first 10 minutes should produce a visible role, island creation and first goal. Track: 2-minute onboarding completion, 24h return, 7-day retention, TPS/MSPT, crash rate, player feedback. Never invent successful metrics.
Recruit creators with short authentic progress reels: island before/after, five role districts, economy versus cosmetics fairness, how a season winner is crowned. Offer recorded material and clear sponsor disclosures; avoid mass spam.
Use one predictable weekly event, clear known-issues channel, regular recap. Only add second game mode when SkyBlock has stable core retention.

## 10. Sequence
- **Phase 0 (now)**: validate existing plugin branch, determine lobby world manager, rank/shop compliance, upload backup, complete branding.
- **Phase 1 (beta)**: install LuckPerms + TAB with no duplicate scoreboard, make starter hub world, generate icon, test join path, publish short website/rules.
- **Phase 2**: replace hub shell with commissioned/custom-built polished schematics; five role districts, leaderboards connected to CoreMC, ItemsAdder merged pack QA, Discord integration.
- **Phase 3**: scalable Velocity network ONLY if necessary, secured forwarding + backend restrictions, promoted events, automated restore drills.
Release gates: no free-fly kick, no duplicate sidebar, no unsigned paid advantage, no leaked backend, no broken pack, no crate-loss support blockers, 20+ minute two-player journey tested and evidenced.

Sources: Paper Velocity security https://docs.papermc.io/velocity/security/ ; TAB docs https://github.com/NEZNAMY/TAB/wiki/ ; LuckPerms https://luckperms.net/wiki/Weight ; ItemsAdder https://wiki.itemsadder.com/adding-content/merge-resourcepacks/ ; Minecraft Usage Guidelines https://www.minecraft.net/en-us/usage-guidelines
