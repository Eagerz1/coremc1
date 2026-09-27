package com.coremc.core.moderation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Runtime view of the configurable CoreMC staff/moderation system. */
public final class ModerationConfig {

    private final JavaPlugin plugin;

    private int cpsWindowSeconds = 10;
    private boolean freezePersist = true;
    private String freezeDisconnectAction = "staff_alert";
    private final Set<String> freezeAllowedCommands = new LinkedHashSet<>();
    private final Set<String> mutedCommandAliases = new LinkedHashSet<>();
    private final Map<String, StaffRank> ranks = new LinkedHashMap<>();
    private final Map<Integer, TierSchedule> schedules = new LinkedHashMap<>();
    private final Map<Integer, Map<String, RuleDefinition>> tierRules = new LinkedHashMap<>();

    public ModerationConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        final FileConfiguration config = plugin.getConfig();
        this.cpsWindowSeconds = Math.max(3, config.getInt("moderation.cps.window-seconds", 10));
        this.freezePersist = config.getBoolean("moderation.freeze.persist-on-restart", true);
        this.freezeDisconnectAction = config.getString("moderation.freeze.disconnect-action", "staff_alert")
                .trim().toLowerCase(Locale.ROOT);
        this.freezeAllowedCommands.clear();
        for (final String command : config.getStringList("moderation.freeze.allowed-commands")) {
            freezeAllowedCommands.add(normaliseCommand(command));
        }
        if (freezeAllowedCommands.isEmpty()) {
            freezeAllowedCommands.addAll(List.of("msg", "tell", "w", "whisper", "r", "reply", "help", "rules"));
        }

        this.mutedCommandAliases.clear();
        for (final String command : config.getStringList("moderation.mute.blocked-command-aliases")) {
            mutedCommandAliases.add(normaliseCommand(command));
        }
        if (mutedCommandAliases.isEmpty()) {
            mutedCommandAliases.addAll(List.of("msg", "tell", "w", "whisper", "r", "reply", "pm", "m", "me", "islandchat", "ic", "party", "pc"));
        }

        loadRanks(config);
        loadTiers(config);
    }

    private void loadRanks(final FileConfiguration config) {
        ranks.clear();
        ranks.put("member", StaffRank.member());
        final ConfigurationSection section = config.getConfigurationSection("moderation.staff-ranks");
        if (section == null) {
            defaultRanks().forEach(rank -> ranks.put(rank.id(), rank));
            return;
        }
        for (final String id : section.getKeys(false)) {
            final String path = id + ".";
            final String normalised = normaliseKey(id);
            final String display = section.getString(path + "display", prettify(id));
            final int weight = Math.max(0, section.getInt(path + "weight", 0));
            final Set<String> capabilities = new LinkedHashSet<>();
            for (final String capability : section.getStringList(path + "capabilities")) {
                capabilities.add(capability.toLowerCase(Locale.ROOT));
            }
            ranks.put(normalised, new StaffRank(normalised, display, weight, Set.copyOf(capabilities)));
        }
        if (ranks.size() <= 1) {
            defaultRanks().forEach(rank -> ranks.put(rank.id(), rank));
        }
    }

    @SuppressWarnings("unchecked")
    private void loadTiers(final FileConfiguration config) {
        schedules.clear();
        tierRules.clear();
        for (int tier = 1; tier <= 5; tier++) {
            final String root = "moderation.tiers.t" + tier;
            final List<Map<?, ?>> rawProgression = (List<Map<?, ?>>) (List<?>) config.getMapList(root + ".progression");
            schedules.put(tier, rawProgression.isEmpty()
                    ? TierSchedule.defaultFor(tier)
                    : TierSchedule.parse(tier, rawProgression));
            tierRules.put(tier, loadRules(config.getConfigurationSection(root + ".rules"), tier));
        }
    }

    private Map<String, RuleDefinition> loadRules(final ConfigurationSection section, final int tier) {
        final Map<String, RuleDefinition> rules = new LinkedHashMap<>();
        if (section == null) {
            defaultRules(tier).forEach(rule -> rules.put(rule.key(), rule));
            return rules;
        }
        for (final String key : section.getKeys(false)) {
            final String normalised = normaliseKey(key);
            final String path = key + ".";
            final RuleDefinition rule;
            if (section.isConfigurationSection(key)) {
                rule = new RuleDefinition(
                        normalised,
                        section.getString(path + "display", prettify(key)),
                        section.getBoolean(path + "enabled", true),
                        section.getBoolean(path + "requires-acknowledgement", false));
            } else {
                rule = new RuleDefinition(normalised, section.getString(key, prettify(key)), true, false);
            }
            rules.put(rule.key(), rule);
        }
        if (rules.isEmpty()) {
            defaultRules(tier).forEach(rule -> rules.put(rule.key(), rule));
        }
        return rules;
    }

    public int cpsWindowSeconds() {
        return cpsWindowSeconds;
    }

    public boolean freezePersist() {
        return freezePersist;
    }

    public String freezeDisconnectAction() {
        return freezeDisconnectAction;
    }

    public boolean isFreezeCommandAllowed(final String rootCommand) {
        return freezeAllowedCommands.contains(normaliseCommand(rootCommand));
    }

    public boolean isMutedCommandAlias(final String rootCommand) {
        return mutedCommandAliases.contains(normaliseCommand(rootCommand));
    }

    public Collection<StaffRank> ranksByWeightDescending() {
        final List<StaffRank> list = new ArrayList<>(ranks.values());
        list.sort(Comparator.comparingInt(StaffRank::weight).reversed());
        return list;
    }

    public StaffRank rank(final String id) {
        return ranks.getOrDefault(normaliseKey(id), StaffRank.member());
    }

    public boolean hasRank(final String id) {
        return ranks.containsKey(normaliseKey(id));
    }

    public TierSchedule schedule(final int tier) {
        return schedules.getOrDefault(tier, TierSchedule.defaultFor(tier));
    }

    public Optional<RuleDefinition> rule(final int tier, final String key) {
        final Map<String, RuleDefinition> rules = tierRules.getOrDefault(tier, Map.of());
        return Optional.ofNullable(rules.get(normaliseKey(key)));
    }

    public List<String> ruleKeys(final int tier, final boolean includeDisabled) {
        final List<String> keys = new ArrayList<>();
        for (final RuleDefinition rule : tierRules.getOrDefault(tier, Map.of()).values()) {
            if (includeDisabled || rule.enabled()) {
                keys.add(rule.key());
            }
        }
        return keys;
    }

    public static String normaliseKey(final String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    public static String normaliseCommand(final String raw) {
        String result = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        final int colon = result.indexOf(':');
        if (colon >= 0 && colon < result.length() - 1) {
            result = result.substring(colon + 1);
        }
        return result;
    }

    public static String prettify(final String key) {
        final String[] pieces = normaliseKey(key).split("_");
        final StringBuilder out = new StringBuilder();
        for (final String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(piece.charAt(0))).append(piece.substring(1));
        }
        return out.toString();
    }

    private static List<StaffRank> defaultRanks() {
        return List.of(
                new StaffRank("helper", "Helper", 10, Set.of(
                        "vanish", "vanish.see", "spectate", "cps", "freeze", "kick", "mute", "unmute",
                        "tier.1", "history")),
                new StaffRank("mod", "Mod", 20, Set.of(
                        "vanish", "vanish.see", "spectate", "cps", "rotate", "freeze", "kick", "mute", "unmute",
                        "ban", "unban", "tier.1", "tier.2", "history")),
                new StaffRank("srmod", "SrMod", 30, Set.of(
                        "vanish", "vanish.see", "spectate", "cps", "rotate", "freeze", "kick", "mute", "unmute",
                        "ban", "unban", "tier.1", "tier.2", "tier.3", "history", "correction")),
                new StaffRank("jr_admin", "Jr Admin", 40, Set.of(
                        "vanish", "vanish.see", "spectate", "cps", "rotate", "freeze", "kick", "mute", "unmute",
                        "ban", "unban", "tier.1", "tier.2", "tier.3", "tier.4", "history", "correction")),
                new StaffRank("admin", "Admin", 50, Set.of(
                        "vanish", "vanish.see", "spectate", "cps", "rotate", "freeze", "kick", "mute", "unmute",
                        "ban", "unban", "tier.1", "tier.2", "tier.3", "tier.4", "tier.5", "history", "correction",
                        "override")),
                new StaffRank("manager", "Manager", 60, Set.of("*")));
    }

    private static List<RuleDefinition> defaultRules(final int tier) {
        return switch (tier) {
            case 1 -> List.of(
                    rule("chat_spam", "Chat Spam"),
                    rule("symbols", "Symbols"),
                    rule("filter_bypass", "Filter Bypass"),
                    rule("inappropriate_chat", "Inappropriate Chat"),
                    rule("disrespect", "Disrespect"),
                    rule("fake_messages", "Fake Messages"),
                    rule("hackusating", "Hackusating"),
                    rule("excessive_caps", "Excessive Caps"),
                    rule("rule_breaking_encouragement", "Rule Breaking Encouragement"),
                    new RuleDefinition("non_english", "Non-English Chat", false, false));
            case 2 -> List.of(
                    rule("light_advertising", "Light Advertising"),
                    rule("impersonation", "Impersonation"),
                    rule("minor_glitch_abuse", "Minor Glitch Abuse"),
                    rule("event_abuse", "Event Abuse"),
                    rule("lag_machines", "Lag Machines"),
                    rule("player_casinos", "Player Casinos"),
                    rule("inappropriate_builds", "Inappropriate Builds"),
                    rule("kill_boosting", "Kill Boosting"),
                    rule("item_name_abuse", "Item Name Abuse"),
                    rule("nickname_abuse", "Nickname Abuse"),
                    rule("report_misuse", "Report Misuse"),
                    rule("claim_trapping", "Claim Trapping"),
                    rule("tp_trapping", "TP Trapping"),
                    rule("in_game_scamming", "In-Game Scamming"),
                    rule("inventory_flooding", "Inventory Flooding"),
                    rule("unauthorized_entry", "Unauthorized Entry"),
                    rule("command_abuse", "Command Abuse"));
            case 3 -> List.of(
                    rule("harassment", "Harassment"),
                    rule("toxicity", "Toxicity"),
                    rule("non_pvp_autoclicking", "Non-PvP Autoclicking"),
                    rule("afk_grinding", "AFK Grinding"),
                    rule("excessive_alts", "Excessive Alts"),
                    rule("alt_abuse", "Alt Abuse"),
                    rule("banning_staff", "Banning Staff"),
                    rule("account_sharing", "Account Sharing"),
                    rule("disallowed_modification", "Disallowed Modification"));
            case 4 -> List.of(
                    rule("major_toxicity", "Major Toxicity"),
                    rule("hacked_client", "Hacked Client"),
                    rule("threats", "Threats"),
                    rule("griefing", "Griefing"),
                    rule("irl_trading", "IRL Trading"),
                    rule("irl_scamming", "IRL Scamming"),
                    rule("island_insiding", "Island Insiding"),
                    rule("pvp_autoclicking", "PvP Autoclicking"),
                    rule("disallowed_macros", "Disallowed Macros"));
            case 5 -> List.of(
                    rule("advertising_ip", "Advertising IP"),
                    rule("harmful_links", "Harmful Links"),
                    rule("duping", "Duping"),
                    rule("major_glitch_abuse", "Major Glitch Abuse"),
                    rule("botting", "Botting"),
                    rule("leaking_personal_information", "Leaking Personal Information"),
                    rule("inappropriate_skin", "Inappropriate Skin", true, true),
                    rule("inappropriate_ign", "Inappropriate IGN", true, true));
            default -> List.of();
        };
    }

    private static RuleDefinition rule(final String key, final String display) {
        return new RuleDefinition(key, display, true, false);
    }

    private static RuleDefinition rule(
            final String key, final String display, final boolean enabled, final boolean requiresAcknowledgement) {
        return new RuleDefinition(key, display, enabled, requiresAcknowledgement);
    }
}
