# Backups, maintenance and incident response

## Backup scope
- Make a complete backup **before every deployment** and daily: `islands/`, the actual overworld/lobby folders, `plugins/CoreMC/` (profiles, islands, config, economy and season state), `plugins/LuckPerms/`, `plugins/ItemsAdder/`, `plugins/TAB/`, `server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`.
- Include SQL dump **if** LuckPerms/other services use an external database.
- Backups must be consistent: **stop the server cleanly** or coordinate a safe save and filesystem snapshot. Random copying while the economy is saving is unsafe.
- Keep at least 7 daily, 4 weekly and 2 monthly recovery points where storage allows; encrypt off-host copies. Don't store IP lists/credentials in a public repo.
- Require a monthly drill to restore on isolated staging and verify ownership, credits, money, island claims, cosmetics and rank entitlements.

## Rollback in a problem
1. Freeze logins or enable maintenance mode. Preserve crash logs and timestamp.
2. Stop Paper, archive the broken state separately.
3. Restore the last VERIFIED complete point-in-time data snapshot, configs and matching plugin JAR.
4. Compare required Java and Paper versions; don't downgrade a world to an incompatible version.
5. Join as ordinary account and test island teleport, profile, balance, missions and skins.
6. Only then reopen logins and publish a concise incident explanation.

## Moderation
- Prefer CoreMC's existing /freeze /ban etc. if available and verified; don't deploy competing punishment plugins by default.
- Staff roles: helper may take reports, mod reviews sanctions, admin handles incidents, owner config. Separate authority from colored rank name.
- Define /report workflow and ticket response policy; keep reasons/time/evidence for punishments. Document ban appeal route.
- Server moderation and data access should be logged; minimize private player data exported to Discord.

## Observability
- Watch uptime, player count, rolling MSPT, TPS, GC/memory, backup age, startup errors, join failures and resource pack acceptance.
- `/spark profiler` is in modern Paper, do not add duplicate spark jar without need.
- Target stable peak tick under 50 ms and adequate GC headroom; visual decoration must not have 100+ moving armorstands/NPCs per spawn.
- Test with genuine multi-user scenarios, not just an OP at spawn.
