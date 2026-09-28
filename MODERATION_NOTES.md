# CoreMC Staff + Moderation Integration Notes

Implemented on Arena session branch `arena/01a0e492-coremc1` without merging other branches. Repository inspection found no existing vanish, spectate, scoreboard, tab-list, CoreBan, chat-pipeline, party-chat, or island-chat implementation in `src/main/java`; moderation therefore integrates through Bukkit/Paper visibility, chat, command, login, join and quit events.

## Runtime data

CoreMC moderation uses UUID-based CoreMC-owned records only and does not read, mutate, revoke, or mirror Bukkit/other-plugin ban lists.

- `plugins/CoreMC/moderation/moderation.yml` — atomic full-state YAML snapshot containing punishment records, tier counters and configured persistent freezes.
- `plugins/CoreMC/moderation/audit.log` — append-only human-readable audit trail.
- Existing player profile data under `plugins/CoreMC/profiles/` is not migrated or rewritten by this system except normal profile loads for rank/name lookup.

## Integration seams

- **Vanish/tab/scoreboard:** no CoreMC tab or scoreboard service exists. Vanish uses `Player#hidePlayer`/`showPlayer`, reapplied on join, teleport, world change, respawn and reload. If a future tab/scoreboard system is added, it should call `plugin.moderation().visibility().refreshAll()` after rebuilding entries and exclude vanished players for viewers without `vanish.see`.
- **Chat:** no CoreMC chat formatter/router exists. Mutes enforce Paper public chat plus common private/island/party command aliases configured under `moderation.mute.blocked-command-aliases`. Future chat channels should check `plugin.moderation().activeMute(playerUuid)` before delivery.
- **Bans:** bans are enforced at `AsyncPlayerPreLoginEvent` from CoreMC records. `/unban` only revokes active CoreMC ban records.
- **Ranks:** staff ranks are configurable under `moderation.staff-ranks`. Runtime rank comes from the highest matching `coremc.staff.rank.<id>` permission or the persisted profile `rank` field. Command permissions are enforced in the command handlers so profile-backed ranks can grant capabilities without requiring plugin.yml command gates.
- **Tier counters:** counters are per UUID and per tier, not per rule, and are never decremented by expiration or unban. `/tiercorrect` is the audited correction path.
- **T5 exception workflow:** `inappropriate_skin` and `inappropriate_ign` default to `requires-acknowledgement: true`. Without `--ack`, `/t5` records a warning/change-opportunity workflow entry instead of the permanent ban. With `--ack`, staff acknowledge that the opportunity was provided and the configured T5 action applies.
