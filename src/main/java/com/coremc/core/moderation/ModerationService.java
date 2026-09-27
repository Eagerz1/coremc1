package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.DateTimeUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Coordinates staff tools, active punishments, tier counters and persistence. */
public final class ModerationService {

    private final CoreMCPlugin plugin;
    private final ModerationConfig config;
    private final YamlModerationStore store;
    private final ExecutorService io;

    private final CopyOnWriteArrayList<PunishmentRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Integer>> tierCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FreezeRecord> activeFreezes = new ConcurrentHashMap<>();

    private final StaffVisibilityService visibility;
    private final SpectateService spectate;
    private final CpsService cps;
    private final ModerationListener listener;

    public ModerationService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.config = new ModerationConfig(plugin);
        this.store = new YamlModerationStore(plugin.getDataFolder().toPath().resolve("moderation"), plugin.getLogger());
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "CoreMC-Moderation");
            thread.setDaemon(true);
            return thread;
        });
        this.visibility = new StaffVisibilityService(plugin, this);
        this.spectate = new SpectateService(plugin, this, visibility);
        this.cps = new CpsService(plugin, this);
        this.listener = new ModerationListener(plugin, this);
    }

    public void load() {
        config.load();
        final ModerationSnapshot snapshot = store.load();
        records.clear();
        records.addAll(snapshot.records());
        tierCounters.clear();
        for (final Map.Entry<UUID, Map<Integer, Integer>> entry : snapshot.tierCounters().entrySet()) {
            tierCounters.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
        }
        activeFreezes.clear();
        if (config.freezePersist()) {
            activeFreezes.putAll(snapshot.activeFreezes());
        }
        plugin.getLogger().info("Loaded " + records.size() + " moderation record(s), "
                + tierCounters.size() + " counter subject(s), " + activeFreezes.size() + " active freeze(s).");
    }

    public void reload() {
        config.load();
        visibility.refreshAll();
    }

    public void shutdown() {
        spectate.restoreAll("server shutting down");
        visibility.restoreAll();
        persistNow();
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Moderation executor did not terminate in 5s.");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public ModerationConfig config() {
        return config;
    }

    public StaffVisibilityService visibility() {
        return visibility;
    }

    public SpectateService spectate() {
        return spectate;
    }

    public CpsService cps() {
        return cps;
    }

    public ModerationListener listener() {
        return listener;
    }

    public ActorContext actor(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return ActorContext.console(sender);
        }
        return ActorContext.player(player, rankOf(player));
    }

    public boolean hasCapability(final CommandSender sender, final String capability) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.hasPermission("coremc.moderation." + capability)
                || player.hasPermission("coremc.moderation.*")
                || player.hasPermission("coremc.*")
                || actor(player).has(capability);
    }

    public boolean require(final CommandSender sender, final String capability) {
        if (!hasCapability(sender, capability)) {
            prefixed(sender, "&cYou do not have permission to do that.");
            return false;
        }
        return true;
    }

    public StaffRank rankOf(final Player player) {
        StaffRank best = plugin.playerData().profileOf(player.getUniqueId())
                .map(PlayerProfile::rankId)
                .filter(config::hasRank)
                .map(config::rank)
                .orElse(StaffRank.member());
        for (final StaffRank rank : config.ranksByWeightDescending()) {
            if (rank.weight() <= best.weight()) {
                break;
            }
            if (player.hasPermission("coremc.staff.rank." + rank.id())) {
                best = rank;
                break;
            }
        }
        if ((player.hasPermission("coremc.moderation.*") || player.hasPermission("coremc.*"))
                && config.rank("manager").weight() > best.weight()) {
            best = config.rank("manager");
        }
        return best;
    }

    private StaffRank offlineRankOf(final UUID uuid) {
        return plugin.playerData().cachedOrLoad(uuid)
                .map(PlayerProfile::rankId)
                .filter(config::hasRank)
                .map(config::rank)
                .orElse(StaffRank.member());
    }

    public Optional<PunishmentRecord> activeMute(final UUID uuid) {
        return activePunishment(uuid, PunishmentType.MUTE, System.currentTimeMillis());
    }

    public Optional<PunishmentRecord> activeBan(final UUID uuid) {
        return activePunishment(uuid, PunishmentType.BAN, System.currentTimeMillis());
    }

    public Optional<PunishmentRecord> activePunishment(
            final UUID uuid, final PunishmentType type, final long nowMillis) {
        return records.stream()
                .filter(record -> record.targetUuid().equals(uuid))
                .filter(record -> record.type() == type)
                .filter(record -> record.activeAt(nowMillis))
                .max(Comparator.comparingLong(PunishmentRecord::createdAtMillis));
    }

    public boolean isFrozen(final UUID uuid) {
        return activeFreezes.containsKey(uuid);
    }

    public Optional<FreezeRecord> freeze(final UUID uuid) {
        return Optional.ofNullable(activeFreezes.get(uuid));
    }

    public void executeKick(final CommandSender sender, final String targetName, final String reason) {
        final ActorContext actor = actor(sender);
        if (!require(sender, "kick")) {
            return;
        }
        final Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            prefixed(sender, "&cNo online player named '&f" + targetName + "&c'.");
            return;
        }
        final ResolvedTarget resolved = new ResolvedTarget(target.getUniqueId(), target.getName(), target, rankOf(target));
        if (!canAct(sender, actor, resolved)) {
            return;
        }
        final String finalReason = reasonOrDefault(reason, "Kicked by staff");
        io.execute(() -> {
            final PunishmentRecord record = PunishmentRecord.create(PunishmentType.KICK, resolved.uuid(), resolved.name(),
                    actor, finalReason, "direct", 0, "", 0, System.currentTimeMillis(), 0L);
            records.add(record);
            audit("KICK actor=" + actor.name() + " target=" + resolved.name() + " reason=\"" + finalReason + "\"");
            persistNow();
            main(() -> {
                target.kickPlayer(kickMessage("Kicked", finalReason, -1L));
                prefixed(sender, "&aKicked &f" + resolved.name() + "&a. Reason: &f" + finalReason);
            });
        });
    }

    public void executeMuteOrBan(
            final CommandSender sender,
            final PunishmentType type,
            final String targetInput,
            final String durationInput,
            final String reason) {
        final String capability = type == PunishmentType.MUTE ? "mute" : "ban";
        final ActorContext actor = actor(sender);
        if (!require(sender, capability)) {
            return;
        }
        final Optional<java.util.OptionalLong> parsedMaybe = parseDuration(durationInput);
        if (parsedMaybe.isEmpty()) {
            prefixed(sender, "&cInvalid duration. Use &f5m&c, &f1h&c, &f7d&c or &fpermanent&c.");
            return;
        }
        final long durationMillis = parsedMaybe.get().orElse(0L);
        final String finalReason = reasonOrDefault(reason, type == PunishmentType.MUTE ? "Muted by staff" : "Banned by staff");
        resolveTarget(targetInput, target -> {
            if (!canAct(sender, actor, target)) {
                return;
            }
            io.execute(() -> {
                if (activePunishment(target.uuid(), type, System.currentTimeMillis()).isPresent()) {
                    main(() -> prefixed(sender, "&c" + target.name() + " already has an active "
                            + type.name().toLowerCase(Locale.ROOT) + ". Revoke it first if this is intentional."));
                    return;
                }
                final PunishmentRecord record = PunishmentRecord.create(type, target.uuid(), target.name(), actor,
                        finalReason, "direct", 0, "", 0, System.currentTimeMillis(), durationMillis);
                records.add(record);
                audit(type.name() + " actor=" + actor.name() + " target=" + target.name()
                        + " duration=" + DurationParser.formatMillis(durationMillis)
                        + " reason=\"" + finalReason + "\"");
                persistNow();
                main(() -> applyPunishment(record, target.onlinePlayer(), sender, false));
            });
        }, sender);
    }

    public void executeUnpunish(
            final CommandSender sender, final PunishmentType type, final String targetInput, final String reason) {
        final String capability = type == PunishmentType.MUTE ? "unmute" : "unban";
        final ActorContext actor = actor(sender);
        if (!require(sender, capability)) {
            return;
        }
        resolveTarget(targetInput, target -> {
            if (actor.uuid() != null && actor.uuid().equals(target.uuid())) {
                prefixed(sender, "&cYou cannot revoke your own punishment records.");
                return;
            }
            io.execute(() -> {
                final Optional<PunishmentRecord> active = activePunishment(target.uuid(), type, System.currentTimeMillis());
                if (active.isEmpty()) {
                    main(() -> prefixed(sender, "&c" + target.name() + " has no active "
                            + type.name().toLowerCase(Locale.ROOT) + "."));
                    return;
                }
                active.get().revoke(actor, reasonOrDefault(reason, "Revoked by staff"), System.currentTimeMillis());
                audit("UN" + type.name() + " actor=" + actor.name() + " target=" + target.name()
                        + " record=" + active.get().id());
                persistNow();
                main(() -> {
                    prefixed(sender, "&aRevoked active " + type.name().toLowerCase(Locale.ROOT)
                            + " for &f" + target.name() + "&a.");
                    final Player online = Bukkit.getPlayer(target.uuid());
                    if (online != null) {
                        prefixed(online, "&aYour " + type.name().toLowerCase(Locale.ROOT) + " has been revoked.");
                    }
                });
            });
        }, sender);
    }

    public void executeTier(
            final CommandSender sender,
            final int tier,
            final String ruleKeyInput,
            final String targetInput,
            final List<String> customReasonParts) {
        final ActorContext actor = actor(sender);
        if (!require(sender, "tier." + tier)) {
            return;
        }
        final Optional<RuleDefinition> maybeRule = config.rule(tier, ruleKeyInput);
        if (maybeRule.isEmpty()) {
            prefixed(sender, "&cUnknown T" + tier + " rule '&f" + ruleKeyInput + "&c'.");
            return;
        }
        final RuleDefinition rule = maybeRule.get();
        if (!rule.enabled()) {
            prefixed(sender, "&cRule '&f" + rule.key() + "&c' is disabled in config.");
            return;
        }
        final List<String> reasonParts = new ArrayList<>(customReasonParts);
        final boolean acknowledged = reasonParts.removeIf(part -> part.equalsIgnoreCase("--ack") || part.equalsIgnoreCase("-ack"));
        final String overrideReason = String.join(" ", reasonParts).trim();
        final String displayedReason = rule.displayedReason(overrideReason);
        resolveTarget(targetInput, target -> {
            if (!canAct(sender, actor, target)) {
                return;
            }
            io.execute(() -> {
                final int offense = tierCounters
                        .computeIfAbsent(target.uuid(), ignored -> new ConcurrentHashMap<>())
                        .merge(tier, 1, Integer::sum);
                TierSchedule.TierAction chosen = config.schedule(tier).actionFor(offense);
                String source = "tier";
                String finalReason = displayedReason;
                if (rule.requiresAcknowledgement() && !acknowledged) {
                    chosen = new TierSchedule.TierAction(ModerationAction.WARN, 0L);
                    source = "tier-warning-workflow";
                    finalReason = displayedReason + " (warning/change opportunity required before T" + tier + " ban)";
                }
                final PunishmentType type = PunishmentType.fromAction(chosen.action());
                final PunishmentRecord record = PunishmentRecord.create(type, target.uuid(), target.name(), actor,
                        finalReason, source, tier, rule.key(), offense, System.currentTimeMillis(),
                        chosen.durationMillis());
                records.add(record);
                audit("T" + tier + " actor=" + actor.name() + " target=" + target.name()
                        + " rule=" + rule.key() + " offense=" + offense + " action=" + chosen.action()
                        + " duration=" + chosen.durationText() + " reason=\"" + finalReason + "\"");
                persistNow();
                final TierSchedule.TierAction finalChosen = chosen;
                final String finalReasonCopy = finalReason;
                main(() -> {
                    applyPunishment(record, target.onlinePlayer(), sender, true);
                    prefixed(sender, "&bT" + tier + " &7" + rule.key() + " &8» &f" + target.name()
                            + " &7offense &f#" + offense + " &7action &f" + describeAction(finalChosen)
                            + "&7. Reason: &f" + finalReasonCopy);
                    if (rule.requiresAcknowledgement() && !acknowledged) {
                        prefixed(sender, "&eThis rule uses the warning/change workflow. Use &f--ack&e after staff confirm the opportunity was given.");
                    }
                });
            });
        }, sender);
    }

    public void executeFreeze(final CommandSender sender, final String targetInput, final String reason) {
        final ActorContext actor = actor(sender);
        if (!require(sender, "freeze")) {
            return;
        }
        resolveTarget(targetInput, target -> {
            if (!canAct(sender, actor, target)) {
                return;
            }
            io.execute(() -> {
                final long now = System.currentTimeMillis();
                final FreezeRecord existing = activeFreezes.remove(target.uuid());
                if (existing != null) {
                    final PunishmentRecord record = PunishmentRecord.create(PunishmentType.UNFREEZE, target.uuid(),
                            target.name(), actor, reasonOrDefault(reason, "Unfrozen by staff"), "freeze", 0,
                            "", 0, now, 0L);
                    records.add(record);
                    audit("UNFREEZE actor=" + actor.name() + " target=" + target.name());
                    persistNow();
                    main(() -> {
                        prefixed(sender, "&aUnfroze &f" + target.name() + "&a.");
                        final Player online = Bukkit.getPlayer(target.uuid());
                        if (online != null) {
                            prefixed(online, "&aYou have been unfrozen by staff.");
                        }
                    });
                    return;
                }
                final String finalReason = reasonOrDefault(reason, "Frozen by staff");
                final FreezeRecord freeze = FreezeRecord.create(target.uuid(), target.name(), actor, finalReason, now);
                activeFreezes.put(target.uuid(), freeze);
                final PunishmentRecord record = PunishmentRecord.create(PunishmentType.FREEZE, target.uuid(),
                        target.name(), actor, finalReason, "freeze", 0, "", 0, now, 0L);
                records.add(record);
                audit("FREEZE actor=" + actor.name() + " target=" + target.name() + " reason=\"" + finalReason + "\"");
                persistNow();
                main(() -> {
                    prefixed(sender, "&aFroze &f" + target.name() + "&a. Reason: &f" + finalReason);
                    final Player online = Bukkit.getPlayer(target.uuid());
                    if (online != null) {
                        sendFrozenMessage(online, freeze);
                    }
                });
            });
        }, sender);
    }

    public void executeRotate(final CommandSender sender, final String targetName, final double degrees) {
        final ActorContext actor = actor(sender);
        if (!require(sender, "rotate")) {
            return;
        }
        final Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            prefixed(sender, "&cNo online player named '&f" + targetName + "&c'.");
            return;
        }
        final ResolvedTarget resolved = new ResolvedTarget(target.getUniqueId(), target.getName(), target, rankOf(target));
        if (!canAct(sender, actor, resolved)) {
            return;
        }
        final float newYaw = normaliseYaw((float) (target.getLocation().getYaw() + degrees));
        target.setRotation(newYaw, target.getLocation().getPitch());
        final double normalisedDegrees = normaliseDegrees(degrees);
        io.execute(() -> {
            final String reason = "Rotated POV by " + trimNumber(normalisedDegrees) + " degrees";
            records.add(PunishmentRecord.create(PunishmentType.ROTATE, resolved.uuid(), resolved.name(), actor,
                    reason, "staff-tool", 0, "", 0, System.currentTimeMillis(), 0L));
            audit("ROTATE actor=" + actor.name() + " target=" + resolved.name() + " degrees=" + normalisedDegrees);
            persistNow();
        });
        prefixed(sender, "&aRotated &f" + target.getName() + "&a by &f" + trimNumber(normalisedDegrees) + "°&a.");
    }

    public void executeHistory(final CommandSender sender, final String targetInput, final int page) {
        if (!require(sender, "history")) {
            return;
        }
        resolveTarget(targetInput, target -> io.execute(() -> {
            final List<PunishmentRecord> targetRecords = records.stream()
                    .filter(record -> record.targetUuid().equals(target.uuid()))
                    .sorted(Comparator.comparingLong(PunishmentRecord::createdAtMillis).reversed())
                    .toList();
            final Map<Integer, Integer> counters = tierCounters.getOrDefault(target.uuid(), new ConcurrentHashMap<>());
            main(() -> sendHistory(sender, target, targetRecords, counters, page));
        }), sender);
    }

    public void executeTierCorrection(
            final CommandSender sender,
            final String targetInput,
            final int tier,
            final String mode,
            final int amount,
            final String reason) {
        final ActorContext actor = actor(sender);
        if (!require(sender, "correction")) {
            return;
        }
        if (tier < 1 || tier > 5) {
            prefixed(sender, "&cTier must be 1-5.");
            return;
        }
        if (!mode.equalsIgnoreCase("set") && !mode.equalsIgnoreCase("add")) {
            prefixed(sender, "&cUsage: /tiercorrect <player> <tier> <set|add> <amount> [reason]");
            return;
        }
        resolveTarget(targetInput, target -> io.execute(() -> {
            final ConcurrentHashMap<Integer, Integer> counters =
                    tierCounters.computeIfAbsent(target.uuid(), ignored -> new ConcurrentHashMap<>());
            final int old = counters.getOrDefault(tier, 0);
            final int updated = Math.max(0, mode.equalsIgnoreCase("set") ? amount : old + amount);
            counters.put(tier, updated);
            final String finalReason = reasonOrDefault(reason, "Tier counter correction");
            records.add(PunishmentRecord.create(PunishmentType.TIER_CORRECTION, target.uuid(), target.name(), actor,
                    "T" + tier + " " + mode.toLowerCase(Locale.ROOT) + " from " + old + " to " + updated
                            + ": " + finalReason,
                    "correction", tier, "", updated, System.currentTimeMillis(), 0L));
            audit("TIER_CORRECTION actor=" + actor.name() + " target=" + target.name() + " tier=" + tier
                    + " old=" + old + " new=" + updated + " reason=\"" + finalReason + "\"");
            persistNow();
            main(() -> prefixed(sender, "&aSet &f" + target.name() + "&a T" + tier + " counter to &f" + updated + "&a."));
        }), sender);
    }

    public void handleFrozenDisconnect(final Player player) {
        final FreezeRecord freeze = activeFreezes.get(player.getUniqueId());
        if (freeze == null) {
            return;
        }
        audit("FROZEN_DISCONNECT target=" + player.getName() + " action=" + config.freezeDisconnectAction());
        if ("staff_alert".equals(config.freezeDisconnectAction())) {
            notifyStaff("&eFrozen player &f" + player.getName() + "&e disconnected. No automatic ban was issued.");
        }
    }

    public void sendFrozenMessage(final Player player, final FreezeRecord freeze) {
        prefixed(player, "&cYou have been frozen by staff. Do not disconnect.");
        prefixed(player, "&7Reason: &f" + freeze.reason());
        prefixed(player, "&7You may move your camera, but movement, interactions and most commands are blocked.");
    }

    public void notifyStaff(final String rawMessage) {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (hasCapability(online, "history") || hasCapability(online, "freeze")) {
                prefixed(online, rawMessage);
            }
        }
        plugin.getLogger().info(ColorUtil.colorize(rawMessage).replace('§', '&'));
    }

    public void prefixed(final CommandSender sender, final String rawMessage) {
        if (!Bukkit.isPrimaryThread()) {
            main(() -> prefixed(sender, rawMessage));
            return;
        }
        sender.sendMessage(plugin.messages().prefix() + ColorUtil.colorize(rawMessage));
    }

    public String muteMessage(final PunishmentRecord record) {
        return ColorUtil.colorize("&cYou are muted. &7Reason: &f" + record.reason()
                + " &8(&7Remaining: &f" + DurationParser.formatRemaining(record.expiresAtMillis(), System.currentTimeMillis())
                + "&8)&7.");
    }

    public String banMessage(final PunishmentRecord record) {
        String message = kickMessage("Banned", record.reason(), record.expiresAtMillis());
        if (record.tier() > 0) {
            message += ColorUtil.colorize("\n&7Tier: &fT" + record.tier() + " " + record.ruleKey()
                    + " offense #" + record.offenseNumber());
        }
        return message;
    }

    public void audit(final String line) {
        store.appendAudit(line);
    }

    public List<String> tabOnlinePlayers(final String partial) {
        final String lower = partial.toLowerCase(Locale.ROOT);
        final List<String> completions = new ArrayList<>();
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                completions.add(online.getName());
            }
        }
        return completions;
    }

    private void resolveTarget(
            final String input, final Consumer<ResolvedTarget> success, final CommandSender senderForFailure) {
        final Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            success.accept(new ResolvedTarget(online.getUniqueId(), online.getName(), online, rankOf(online)));
            return;
        }
        io.execute(() -> {
            Optional<UUID> uuid = plugin.playerData().resolveUuid(input);
            String knownName = input;
            if (uuid.isEmpty()) {
                final Optional<PunishmentRecord> byRecord = records.stream()
                        .filter(record -> record.targetName().equalsIgnoreCase(input))
                        .findFirst();
                if (byRecord.isPresent()) {
                    uuid = Optional.of(byRecord.get().targetUuid());
                    knownName = byRecord.get().targetName();
                }
            }
            if (uuid.isEmpty()) {
                main(() -> prefixed(senderForFailure, "&cUnknown player '&f" + input + "&c'. They may need to join once first."));
                return;
            }
            final UUID resolvedUuid = uuid.get();
            final String resolvedName = knownName;
            success.accept(new ResolvedTarget(resolvedUuid, resolvedName, null, offlineRankOf(resolvedUuid)));
        });
    }

    private boolean canAct(final CommandSender sender, final ActorContext actor, final ResolvedTarget target) {
        if (actor.uuid() != null && actor.uuid().equals(target.uuid())) {
            prefixed(sender, "&cYou cannot punish yourself.");
            return false;
        }
        if (!actor.override() && target.rank().weight() > 0 && target.rank().weight() >= actor.rank().weight()) {
            prefixed(sender, "&cYou cannot punish staff of equal or higher rank (&f" + target.rank().display() + "&c).");
            return false;
        }
        return true;
    }

    private void applyPunishment(
            final PunishmentRecord record, final Player maybeOnline, final CommandSender staff, final boolean fromTier) {
        final Player online = maybeOnline != null ? maybeOnline : Bukkit.getPlayer(record.targetUuid());
        final String action = record.type().name().toLowerCase(Locale.ROOT);
        switch (record.type()) {
            case WARN -> {
                if (online != null) {
                    prefixed(online, "&eWarning &8» &7Reason: &f" + record.reason()
                            + tierSuffix(record));
                }
                if (!fromTier) {
                    prefixed(staff, "&aWarned &f" + record.targetName() + "&a. Reason: &f" + record.reason());
                }
            }
            case MUTE -> {
                if (online != null) {
                    prefixed(online, "&cYou have been muted. &7Reason: &f" + record.reason()
                            + " &8(&7Duration: &f" + DurationParser.formatRemaining(record.expiresAtMillis(), record.createdAtMillis())
                            + "&8)&7" + tierSuffix(record));
                }
                if (!fromTier) {
                    prefixed(staff, "&aMuted &f" + record.targetName() + "&a for &f"
                            + DurationParser.formatRemaining(record.expiresAtMillis(), record.createdAtMillis())
                            + "&a. Reason: &f" + record.reason());
                }
            }
            case BAN -> {
                if (online != null) {
                    online.kickPlayer(banMessage(record));
                }
                if (!fromTier) {
                    prefixed(staff, "&aBanned &f" + record.targetName() + "&a for &f"
                            + DurationParser.formatRemaining(record.expiresAtMillis(), record.createdAtMillis())
                            + "&a. Reason: &f" + record.reason());
                }
            }
            case KICK -> {
                if (online != null) {
                    online.kickPlayer(kickMessage("Kicked", record.reason(), -1L));
                }
                if (!fromTier) {
                    prefixed(staff, "&aKicked &f" + record.targetName() + "&a. Reason: &f" + record.reason());
                }
            }
            case FREEZE, UNFREEZE, ROTATE, TIER_CORRECTION -> {
                if (!fromTier) {
                    prefixed(staff, "&aRecorded " + action + " for &f" + record.targetName() + "&a.");
                }
            }
        }
    }

    private void sendHistory(
            final CommandSender sender,
            final ResolvedTarget target,
            final List<PunishmentRecord> targetRecords,
            final Map<Integer, Integer> counters,
            final int page) {
        final int perPage = 8;
        final int maxPage = Math.max(1, (int) Math.ceil(targetRecords.size() / (double) perPage));
        final int safePage = Math.min(Math.max(1, page), maxPage);
        sender.sendMessage(ColorUtil.colorize("&b&lMOD HISTORY &8— &f" + target.name()
                + " &7(page " + safePage + "/" + maxPage + ")"));
        sender.sendMessage(ColorUtil.colorize("&7Counters: &fT1 " + counters.getOrDefault(1, 0)
                + "&7, &fT2 " + counters.getOrDefault(2, 0)
                + "&7, &fT3 " + counters.getOrDefault(3, 0)
                + "&7, &fT4 " + counters.getOrDefault(4, 0)
                + "&7, &fT5 " + counters.getOrDefault(5, 0)));
        if (targetRecords.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize("&7No CoreMC moderation records."));
            return;
        }
        final int start = (safePage - 1) * perPage;
        final int end = Math.min(targetRecords.size(), start + perPage);
        final long now = System.currentTimeMillis();
        for (int i = start; i < end; i++) {
            final PunishmentRecord record = targetRecords.get(i);
            final String state = record.revokedAtMillis() > 0L ? "&c revoked"
                    : record.activeAt(now) && (record.type() == PunishmentType.MUTE || record.type() == PunishmentType.BAN)
                    ? "&a active" : "&7 historical";
            final String tier = record.tier() > 0 ? " &8T" + record.tier() + "/" + record.ruleKey()
                    + " #" + record.offenseNumber() : "";
            sender.sendMessage(ColorUtil.colorize("&8#" + record.id().toString().substring(0, 8)
                    + " &f" + record.type() + state + tier + " &8— &7" + record.reason()
                    + " &8(" + DateTimeUtil.formatTimestamp(record.createdAtMillis()) + ")"));
        }
    }

    private String tierSuffix(final PunishmentRecord record) {
        if (record.tier() <= 0) {
            return "";
        }
        return " &8(&7T" + record.tier() + " &f" + record.ruleKey() + "&7 offense &f#"
                + record.offenseNumber() + "&8)";
    }

    private String describeAction(final TierSchedule.TierAction action) {
        if (action.action() == ModerationAction.WARN || action.action() == ModerationAction.KICK) {
            return action.action().name().toLowerCase(Locale.ROOT);
        }
        return action.action().name().toLowerCase(Locale.ROOT) + " " + action.durationText();
    }

    private Optional<java.util.OptionalLong> parseDuration(final String input) {
        final java.util.OptionalLong parsed = DurationParser.parseMillis(input);
        return parsed.isPresent() ? Optional.of(parsed) : Optional.empty();
    }

    private String reasonOrDefault(final String reason, final String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }

    private String kickMessage(final String title, final String reason, final long expiresAtMillis) {
        if (expiresAtMillis < 0L) {
            return ColorUtil.colorize("&b&lCOREMC\n&c" + title + "\n&7Reason: &f" + reason);
        }
        final String duration = expiresAtMillis == 0L ? "permanent"
                : DurationParser.formatRemaining(expiresAtMillis, System.currentTimeMillis());
        return ColorUtil.colorize("&b&lCOREMC\n&c" + title + "\n&7Reason: &f" + reason + "\n&7Remaining: &f" + duration);
    }

    private void persistNow() {
        try {
            store.save(snapshot());
        } catch (final IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save moderation state", exception);
        }
    }

    private ModerationSnapshot snapshot() {
        final Map<UUID, Map<Integer, Integer>> counters = new java.util.LinkedHashMap<>();
        for (final Map.Entry<UUID, ConcurrentHashMap<Integer, Integer>> entry : tierCounters.entrySet()) {
            counters.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return new ModerationSnapshot(List.copyOf(records), counters,
                config.freezePersist() ? Map.copyOf(activeFreezes) : Map.of());
    }

    private void main(final Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private static float normaliseYaw(final float yaw) {
        float result = yaw % 360.0F;
        if (result <= -180.0F) {
            result += 360.0F;
        }
        if (result > 180.0F) {
            result -= 360.0F;
        }
        return result;
    }

    private static double normaliseDegrees(final double degrees) {
        double result = degrees % 360.0D;
        if (result < 0.0D) {
            result += 360.0D;
        }
        return result;
    }

    private static String trimNumber(final double number) {
        if (Math.rint(number) == number) {
            return String.valueOf((long) number);
        }
        return String.format(Locale.ROOT, "%.2f", number);
    }
}
