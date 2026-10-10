# CoreMC external launch acceptance checklist

## Before opening the beta
- [ ] Save a consistent encrypted off-host backup and prove staging restore works.
- [ ] Confirm Paper 1.21.11, Java 21, correct current CoreMC JAR and hosting port.
- [ ] Confirm exact plugin versions, licenses and ItemsAdder generation without console errors.
- [ ] Independent `coremc_spawn` world exists; hub map and `islands` world remain isolated.
- [ ] New player joins with valid online UUID, spawns safely, understands `/role` then `/is create`.
- [ ] Two normal players can use `/hud` without a TAB duplicate scoreboard, glitchy teams, or chat duplication.
- [ ] Core Hour event bossbar visible and unobstructed; TAB bossbar is OFF.
- [ ] TAB header/footer, player counts and rank colors accurate; not imaginary values.
- [ ] LuckPerms donor groups cannot use op/admin commands, change other islands, mint money, bypass PvP or outperform equals.
- [ ] Core/+/++ display correctly in TAB **and** in CoreMC chat; old VIP/MVP groups handled safely.
- [ ] New players can reject/miss the pack without losing their inventory identities (vanilla fallbacks).
- [ ] ItemsAdder pack comes from **one** compatible host/URL and doesn't conflict with server.properties.
- [ ] NPCs open real CoreMC commands rather than unprotected third-party inventories.
- [ ] Spawn has lighting, readable rules, no fall-through, no staff-only secrets, no exposed server coordinates.
- [ ] Store has accurate prices, clear disclaimers, refund/contact information; legal/payment review complete.
- [ ] Two-player, 20-minute full start → island → role → first quest → shop → quit/rejoin journey recorded.
- [ ] Live performance measured at 8+ concurrent players; no startup ERROR and no blank placeholders.
- [ ] Discord rules, support, changelog, moderation appeal and critical outage procedures published.
- [ ] Rollback tested and available; do not deploy directly into the production worlds.

## Go/no-go
A release blocker in identities, economy duplication, data persistence, backend exposure, personal-data leak, competitive sales, or broken onboarding means NO-GO. Visual polish can improve post-beta but cannot compromise basic reliability.
