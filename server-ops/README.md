# CoreMC: external server operations

This directory contains **deployment templates, artwork specifications, a starter hub generator, rank setup, safety checks, and operational procedures** for the real CoreMC Java server.

**It is not a plugin and ships no JAR.** The game's custom plugin, Java source, economy, chat handler, sidebar and original ItemsAdder namespace remain owned by the existing project.

## Verified integration target
- Active source branch at design time: `arena/progression-0.12.1`, Paper 1.21.11, Java 21.
- CoreMC chat: `plugins/CoreMC/chat.yml`; rank permissions are configurable.
- CoreMC sidebar: `plugins/CoreMC/config.yml`, `scoreboard.enabled: true`, toggle `/hud`.
- CoreMC has a Core Hour bossbar; **do not enable another recurring bossbar**.
- ItemsAdder: already includes the `coremc` namespace, item models and textures. Do not replace its generated pack.
- Islands world is `islands`, with spiral placement. **Never generate lobby terrain or import WorldEdit builds into that world.**

## Deploy the low-cost, one-server release first
1. Use an isolated **staging Paper 1.21.11** server. Backup live data before touching host files.
2. Install only appropriately licensed, compatible versions of **LuckPerms**, **TAB (Bukkit)**, **PlaceholderAPI if required** and an editor such as **FastAsyncWorldEdit** only while building. CoreMC and ItemsAdder already do substantial work.
3. Review `standalone/server.properties.example`. Merge its choices into the generated server.properties; this is NOT a complete replacement.
4. After first TAB boot, merge `standalone/TAB/config.yml` into TAB's generated config. Apply `standalone/TAB/groups.yml` only if group resolution is correct. Leave `scoreboard.enabled: false` and `bossbar.enabled: false` in TAB.
5. Review and run `luckperms/roles.commands.txt` **manually**, once, through the server console. **Never grant `*`, `coremc.*` or `luckperms.*` to donors.**
6. Add the three donor ranks from `coremc/chat-ranks-overlay.yml` to the live `plugins/CoreMC/chat.yml` ranks map (do not replace all chat configuration). Reload as supported, then verify displays.
7. Use `python3 tools/generate_lobby_commands.py` to prepare a **starter shell** for an independent lobby world; read the safety warning and deploy to a disposable empty lobby world first. The generated commands are NOT a finished artistic map.
8. Use `python3 tools/build_server_icon.py` to generate an original 64x64 `server-icon.png`.
9. Run `python3 tools/validate.py`, test two normal players and one staff player, then follow `launch/RELEASE_GATE.md`.
10. Do not switch DNS or run the full Velocity deployment until a separate proxy, private backend ports, and robust forwarding are available.

## Files
- `MASTER_PLAN.md` — full design spec, staged priorities, research links, budget and live dependencies
- `BUILD_BRIEFS.md` — spawn, halls, role districts, portal and map build measurements
- `brand/style.yml` — restrained CoreMC visual token sheet
- `standalone/TAB/*` — safe tab presentation defaults that don't take sidebar/bossbar ownership
- `luckperms/roles.commands.txt` — reviewable, one-time permissions setup
- `coremc/chat-ranks-overlay.yml` — additive configuration for existing CoreMC rank resolver
- `velocity/*` — **future network example only**; no proxy is required to launch
- `runbooks/*` — backups, rollback, monitoring, moderation and launch gates
- `tools/*` — deterministic local generators and basic validation

## Design rules
Keep rank benefits cosmetic, disclosed and accessible. Do not invent a fake online player count. Avoid a second scoreboard, chat formatter, bossbar owner, cosmetics plugin, crate engine, or resource-pack generator. All examples are staging templates, not a claim that Ravenport has been configured.

Research sources: https://docs.papermc.io/velocity/security/ ; https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Scoreboard ; https://luckperms.net/wiki/Weight ; https://wiki.itemsadder.com/adding-content/merge-resourcepacks/ ; https://www.minecraft.net/en-us/usage-guidelines
