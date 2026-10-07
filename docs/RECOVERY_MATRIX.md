# CoreMC recovery matrix

This is the release gate after the incomplete baseline was described as a finished rebuild.
A row is **Complete** only when it has player-facing code, persistence where needed, tests,
and a normal-player journey. Config keys, profile fields and resource-pack assets alone do
not count as a feature.

| System | Acceptance target | Current state | Status |
|---|---|---|---|
| Shop | 219 regular items, five categories, pagination, buy/sell progression, three token exchanges | Catalogue, GUI, migration and count/balance tests implemented | Complete |
| OmniTool menu | Shift-right-click opens enchants; no redundant overview | Direct enchant routing implemented and tested | Complete |
| Generators | 20+ useful material/crop generators, uncapped GUI, purchase/place/harvest loop | 24 generators and paginated GUI; catalogue test added | Complete |
| Spawners | 30 distinct regular mobs, direct purchase, paginated GUI and kill unlock loop | 30 regular spawners with polished `✔`/`✖` lore; variants removed; catalogue test added | Complete |
| Animated skins | 30 role tool skins + 3 hats, ownership/apply loop and resource-pack validation | Implemented; all locked until earned by design | Complete |
| Skin unlock loop | Obtain cosmetics naturally through rewards/events/store, not only staff grants | `/store` now sells existing tool-skin and hat IDs for configurable Credits; crate/event grant hooks remain available; live purchase journey added | In progress — needs live run |
| Enchants | 90 definitions, visible from OmniTool, purchasable progression | Engine and direct GUI implemented; full play-balance audit remains | In progress |
| Companions | Summon/equip GUI, abilities, progression and persistence | Six earnable companions, visible followers, 20-level category XP abilities, persistence, tests and journey coverage | Complete |
| Equipment sets | Real set items, bonuses, acquisition and GUI/help surface | Not implemented | Missing |
| Missions / quests | Daily/weekly objectives, progress, claim flow, reset and persistence | Daily and weekly assignment/progress/claim GUIs use separate persisted records; UTC resets and weekly rotation; weekly selection test and live GUI journey added | In progress — needs live run |
| Events | Scheduled gameplay events with visible state and rewards | One-hour Core Hour every four hours; persisted schedule, four-step boss bar, `/event` status, and 2x island XP/slaying money/Omni-Tool XP wired into normal reward paths and checked in the live journey | Complete |
| Lootboxes | Non-pay-to-win reward definitions, preview/open flow and persistence | Physical keys work through the key listener and crate rewards/preview exist; animation and one-click-anywhere journey still need full end-to-end validation | In progress |
| Store | Bundles/subscriptions/cosmetics delivery with explicit non-P2W rules | Existing Credit products retained; added paginated catalog unlocks for tool skins, hats, tags and chat styles with configurable prices and refund-on-grant-failure | In progress — needs live run |
| Scoreboard | Live currencies, role/tool progress, island and objective context | Configurable sidebar updates every two seconds; `/hud` toggle; live values include balances, role/tool levels, island level, quests and Core Hour | In progress — awaiting release build + live journey |
| Island top seasons | Exclude active DQ islands; show and settle top rewards once per season | `/is top rewards` previews configured Credits; `/is top settle` snapshots eligible top three and persists per-rank claims plus a season ledger | In progress — needs live run and season admin check |
| Master player journey | Normal-player audit of every menu and unlock loop | Existing journey validates important mechanics, not the full specification | In progress |

## Release rule

The README and release notes must use this matrix as their source of truth. Missing and
in-progress rows cannot be advertised as complete. Every new system must add a regression
test for catalogue/completeness and a live journey step for the real player flow.
