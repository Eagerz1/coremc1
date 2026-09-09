package com.coremc.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed access to CoreMC's config.yml.
 *
 * Values that may need balancing are read through here rather than
 * scattered around the codebase, and a missing/invalid value falls
 * back to a sane default instead of breaking the plugin.
 */
public final class CoreConfig {

    private static final long MIN_AUTOSAVE_SECONDS = 30L;
    private static final long DEFAULT_AUTOSAVE_SECONDS = 300L;

    private final JavaPlugin plugin;

    private long autosaveSeconds = DEFAULT_AUTOSAVE_SECONDS;
    private boolean firstJoinMessage = true;

    // island settings
    private String islandWorldName = "islands";
    private int islandSpacing = 256;
    private int islandStartHeight = 64;
    private long islandDeleteConfirmSeconds = 15L;
    private int islandBorderSize = 50;
    private int islandMemberSlots = 3;
    private long islandInviteExpirySeconds = 60L;
    private double roleXpMultiplier = 1.0;
    private double roleUniversalShare = 0.25;
    private long spawnerKillCapPerMinute = 120L;
    private int upgradeBorderStepBlocks = 25;
    /** upgrade id -> max tier, read from island.upgrades.<id>.max-tier. */
    private final java.util.Map<String, Integer> upgradeMaxTiers = new java.util.LinkedHashMap<>();
    /** upgrade id -> per-tier Sky Token costs, read from island.upgrades.<id>.costs. */
    private final java.util.Map<String, java.util.List<Long>> upgradeCosts = new java.util.LinkedHashMap<>();
    /** Absolute border width per tier (index = tier); empty = legacy step-blocks mode. */
    private final java.util.List<Integer> upgradeBorderSizes = new java.util.ArrayList<>();
    /** upgrade id -> (required track -> required tier), read from island.upgrades.<id>.requires. */
    private final java.util.Map<String, java.util.Map<String, Integer>> upgradeRequires =
            new java.util.LinkedHashMap<>();
    /** upgrade id -> (exact tier -> (required track -> required tier)), from requires-tiers. */
    private final java.util.Map<String, java.util.Map<Integer, java.util.Map<String, Integer>>>
            upgradeRequiresTiers = new java.util.LinkedHashMap<>();
    /** upgrade id -> required island level to buy any tier (0 = no gate). */
    private final java.util.Map<String, Integer> upgradeRequiresIslandLevel = new java.util.LinkedHashMap<>();
    /** upgrade id -> required best-role level (any role) to buy any tier (0 = no gate). */
    private final java.util.Map<String, Integer> upgradeRequiresRoleLevel = new java.util.LinkedHashMap<>();
    /** buff id -> max level, read from island-buffs.<id>.max-tier. */
    private final java.util.Map<String, Integer> buffMaxTiers = new java.util.LinkedHashMap<>();
    /** buff id -> per-level Sky Token costs, read from island-buffs.<id>.costs. */
    private final java.util.Map<String, java.util.List<Long>> buffCosts = new java.util.LinkedHashMap<>();
    /** buff id -> percent per level, read from island-buffs.<id>.percent-per-level. */
    private final java.util.Map<String, Integer> buffPercents = new java.util.LinkedHashMap<>();

    public CoreConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads config.yml and re-reads every setting. */
    public void load() {
        plugin.saveDefaultConfig();
        mergeNewDefaultKeys();
        plugin.reloadConfig();

        final FileConfiguration config = plugin.getConfig();

        final long configuredAutosave = config.getLong("player-data.autosave-seconds", DEFAULT_AUTOSAVE_SECONDS);
        if (configuredAutosave < MIN_AUTOSAVE_SECONDS) {
            plugin.getLogger()
                    .warning("player-data.autosave-seconds is below " + MIN_AUTOSAVE_SECONDS
                            + "s (" + configuredAutosave + "s), clamping to " + DEFAULT_AUTOSAVE_SECONDS + "s.");
            this.autosaveSeconds = DEFAULT_AUTOSAVE_SECONDS;
        } else {
            this.autosaveSeconds = configuredAutosave;
        }

        this.firstJoinMessage = config.getBoolean("welcome.first-join-message", true);

        this.islandWorldName = config.getString("island.world", "islands");
        final int spacing = config.getInt("island.spacing", 256);
        if (spacing < 64) {
            plugin.getLogger().warning("island.spacing below 64 (" + spacing + "), clamping to 256.");
            this.islandSpacing = 256;
        } else {
            this.islandSpacing = spacing;
        }
        this.islandStartHeight = config.getInt("island.start-height", 64);
        this.islandDeleteConfirmSeconds = Math.max(5L, config.getLong("island.delete-confirm-seconds", 15L));

        int border = config.getInt("island.border-size", 50);
        // Cell centres sit spacing apart and containment is a half-open
        // [centre-half, centre+half) square, so a full border width up to
        // spacing can never touch the neighbour cell (width W overlaps iff
        // W/2 + W/2 > spacing). Round down to even.
        final int maxBorder = this.islandSpacing;
        if (border > maxBorder) {
            plugin.getLogger().warning("island.border-size " + border + " exceeds spacing (" + maxBorder
                    + "), clamping to prevent overlap.");
            border = maxBorder;
        }
        if (border < 10) {
            plugin.getLogger().warning("island.border-size below 10 (" + border + "), using 50.");
            border = 50;
        }
        this.islandBorderSize = border - (border % 2);
        this.islandMemberSlots = Math.max(0, config.getInt("island.member-slots", 3));
        this.islandInviteExpirySeconds = Math.max(15L, config.getLong("island.invite-expiry-seconds", 60L));

        this.upgradeBorderStepBlocks = Math.max(1, config.getInt("island.upgrades.border.step-blocks", 25));
        // Absolute per-tier border widths (index = tier). When present they win
        // over step-blocks; entries past spacing truncate the list (tier
        // indexes stay aligned: tier T reads sizes[T]).
        this.upgradeBorderSizes.clear();
        for (final int size : config.getIntegerList("island.upgrades.border.sizes")) {
            if (size <= 0) {
                continue;
            }
            final int even = size - (size % 2);
            if (even > this.islandSpacing) {
                plugin.getLogger().warning("island.upgrades.border.sizes entry " + size
                        + " exceeds spacing — truncating larger tiers.");
                break;
            }
            this.upgradeBorderSizes.add(even);
        }
        // The effective border (base + tiers * step) must stay inside the grid cell,
        // like the base border above — otherwise upgraded protection could overlap
        // the neighbour island (lookups probe the 2x2 candidate neighbourhood).
        final int roomToGrow = this.islandSpacing - this.islandBorderSize;
        final int maxSafeTier = this.upgradeBorderSizes.isEmpty()
                ? Math.max(0, roomToGrow / this.upgradeBorderStepBlocks)
                : this.upgradeBorderSizes.size() - 1;

        // Generic upgrade track table: every island.upgrades.<id> section with a
        // max-tier and a costs list becomes a purchasable track automatically
        // (border is additionally clamped so the effective border can never
        // leave the island cell — see above).
        this.upgradeMaxTiers.clear();
        this.upgradeCosts.clear();
        this.upgradeRequires.clear();
        this.upgradeRequiresTiers.clear();
        this.upgradeRequiresIslandLevel.clear();
        this.upgradeRequiresRoleLevel.clear();
        final org.bukkit.configuration.ConfigurationSection upgradesSection =
                config.getConfigurationSection("island.upgrades");
        if (upgradesSection != null) {
            for (final String id : upgradesSection.getKeys(false)) {
                if (!upgradesSection.isConfigurationSection(id)) {
                    continue; // flat keys like border.step-blocks are not tracks
                }
                int maxTier = Math.max(0, upgradesSection.getInt(id + ".max-tier", 0));
                if ("border".equals(id) && maxTier > maxSafeTier) {
                    plugin.getLogger().warning("island.upgrades.border.max-tier " + maxTier
                            + " would push the effective border past spacing; clamping to " + maxSafeTier + ".");
                    maxTier = maxSafeTier;
                }
                final java.util.List<Long> costs = longCosts(
                        config, "island.upgrades." + id + ".costs", java.util.List.of());
                if (costs.size() < maxTier) {
                    plugin.getLogger().warning("island.upgrades." + id + ".costs has only " + costs.size()
                            + " entr(y/ies) for max-tier " + maxTier + "; track capped at " + costs.size()
                            + " purchasable tiers.");
                }
                this.upgradeMaxTiers.put(id, maxTier);
                this.upgradeCosts.put(id, costs);
                final java.util.Map<String, Integer> requires = new java.util.LinkedHashMap<>();
                final org.bukkit.configuration.ConfigurationSection requiresSection =
                        upgradesSection.getConfigurationSection(id + ".requires");
                if (requiresSection != null) {
                    for (final String req : requiresSection.getKeys(false)) {
                        requires.put(req, Math.max(0, requiresSection.getInt(req, 0)));
                    }
                }
                this.upgradeRequires.put(id, requires);
                final java.util.Map<Integer, java.util.Map<String, Integer>> tierGates =
                        new java.util.LinkedHashMap<>();
                final org.bukkit.configuration.ConfigurationSection tiersSection =
                        upgradesSection.getConfigurationSection(id + ".requires-tiers");
                if (tiersSection != null) {
                    for (final String tierKey : tiersSection.getKeys(false)) {
                        final int gatedTier;
                        try {
                            gatedTier = Integer.parseInt(tierKey.trim());
                        } catch (final NumberFormatException notANumber) {
                            plugin.getLogger().warning("island.upgrades." + id
                                    + ".requires-tiers key '" + tierKey + "' is not a tier number — ignored.");
                            continue;
                        }
                        final org.bukkit.configuration.ConfigurationSection gate =
                                tiersSection.getConfigurationSection(tierKey);
                        if (gate == null) {
                            continue;
                        }
                        final java.util.Map<String, Integer> gateRequires = new java.util.LinkedHashMap<>();
                        for (final String req : gate.getKeys(false)) {
                            gateRequires.put(req, Math.max(0, gate.getInt(req, 0)));
                        }
                        tierGates.put(gatedTier, gateRequires);
                    }
                }
                this.upgradeRequiresTiers.put(id, tierGates);
                // Level gates (spec: upgrades may need island/role levels).
                this.upgradeRequiresIslandLevel.put(
                        id, Math.max(0, upgradesSection.getInt(id + ".requires-island-level", 0)));
                this.upgradeRequiresRoleLevel.put(
                        id, Math.max(0, upgradesSection.getInt(id + ".requires-role-level", 0)));
            }
        }

        // Island buff table: every island-buffs.<id> section with max-tier,
        // costs and percent-per-level becomes a purchasable buff. Buffs are
        // standalone (no prerequisite gates) by design.
        this.buffMaxTiers.clear();
        this.buffCosts.clear();
        this.buffPercents.clear();
        final org.bukkit.configuration.ConfigurationSection buffsSection =
                config.getConfigurationSection("island-buffs");
        if (buffsSection != null) {
            for (final String id : buffsSection.getKeys(false)) {
                if (!buffsSection.isConfigurationSection(id)) {
                    continue;
                }
                final int maxTier = Math.max(0, buffsSection.getInt(id + ".max-tier", 0));
                final java.util.List<Long> costs = longCosts(
                        config, "island-buffs." + id + ".costs", java.util.List.of());
                if (costs.size() < maxTier) {
                    plugin.getLogger().warning("island-buffs." + id + ".costs has only " + costs.size()
                            + " entr(y/ies) for max-tier " + maxTier + "; buff capped at " + costs.size()
                            + " purchasable levels.");
                }
                this.buffMaxTiers.put(id, maxTier);
                this.buffCosts.put(id, costs);
                this.buffPercents.put(id, Math.max(0, buffsSection.getInt(id + ".percent-per-level", 0)));
            }
        }

        this.roleXpMultiplier = Math.max(0.0, config.getDouble("roles.xp-multiplier", 1.0));
        final double share = config.getDouble("roles.universal-share", 0.25);
        this.roleUniversalShare = share < 0 ? 0.25 : Math.min(1.0, share);
        this.spawnerKillCapPerMinute = Math.max(0L, config.getLong("spawners.kill-cap-per-minute", 120L));
    }

    /**
     * Copies keys present in the bundled config.yml but missing on disk into
     * the server's config file, then saves. Existing values are never
     * touched — operators keep their edits, upgrades gain new balance keys.
     * Without this, {@code saveDefaultConfig()} alone would permanently hide
     * new sections from servers upgrading CoreMC.
     */
    private void mergeNewDefaultKeys() {
        try (var reader = new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(plugin.getResource("config.yml")),
                java.nio.charset.StandardCharsets.UTF_8)) {
            final FileConfiguration disk = plugin.getConfig();
            final var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            disk.setDefaults(defaults);
            disk.options().copyDefaults(true);
            plugin.saveConfig();
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge new config defaults: " + e.getMessage());
        }
    }

    /** Seconds between automatic flushes of dirty player profiles. */
    public long autosaveSeconds() {
        return autosaveSeconds;
    }

    /** Whether the first-join welcome message is enabled. */
    public boolean firstJoinMessage() {
        return firstJoinMessage;
    }

    /** Name of the world islands are created in. */
    public String islandWorldName() {
        return islandWorldName;
    }

    /** Distance between island centres on the island grid. */
    public int islandSpacing() {
        return islandSpacing;
    }

    /** Y level of the island platform surface. */
    public int islandStartHeight() {
        return islandStartHeight;
    }

    /** Seconds the delete confirmation stays valid. */
    public long islandDeleteConfirmSeconds() {
        return islandDeleteConfirmSeconds;
    }

    /** Protected border width of a new island (full square width, e.g. 50x50). */
    public int islandBorderSize() {
        return islandBorderSize;
    }

    /** Base number of non-owner members an island can hold. */
    public int islandMemberSlots() {
        return islandMemberSlots;
    }

    /** Seconds an island invite stays valid. */
    public long islandInviteExpirySeconds() {
        return islandInviteExpirySeconds;
    }

    /** Global role XP multiplier. */
    public double roleXpMultiplier() {
        return roleXpMultiplier;
    }

    /** Universal role's share of full XP per category action (0..1). */
    public double roleUniversalShare() {
        return roleUniversalShare;
    }

    /** Anti-farming: max counted kills per player per rolling minute. */
    public long spawnerKillCapPerMinute() {
        return spawnerKillCapPerMinute;
    }

    /** Blocks a border upgrade tier adds to the protected square. */
    public int upgradeBorderStepBlocks() {
        return upgradeBorderStepBlocks;
    }

    /** Max tier of an upgrade track (0 if the track is not configured). */
    public int upgradeMaxTier(final String upgradeId) {
        return upgradeMaxTiers.getOrDefault(upgradeId, 0);
    }

    /** Price (Sky Tokens) for buying tier {@code tier+1}, or empty past the configured list. */
    public java.util.OptionalLong upgradeCost(final String upgradeId, final int tier) {
        final java.util.List<Long> costs = upgradeCosts.get(upgradeId);
        if (costs == null || tier < 0 || tier >= costs.size()) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of(costs.get(tier));
    }

    /** Absolute border widths per tier (index = tier); empty = legacy step-blocks mode. */
    public java.util.List<Integer> upgradeBorderSizes() {
        return java.util.Collections.unmodifiableList(upgradeBorderSizes);
    }

    /** Required (track -> tier) map for an upgrade track (empty = no requirements). */
    public java.util.Map<String, Integer> upgradeRequires(final String upgradeId) {
        return java.util.Collections.unmodifiableMap(
                upgradeRequires.getOrDefault(upgradeId, java.util.Map.of()));
    }

    /**
     * Merged purchase gates for buying exactly {@code tier}: the
     * track-level requires plus any requires-tiers entry for it.
     */
    public java.util.Map<String, Integer> upgradeRequiresAt(final String upgradeId, final int tier) {
        final java.util.Map<String, Integer> merged =
                new java.util.LinkedHashMap<>(upgradeRequires(upgradeId));
        final java.util.Map<Integer, java.util.Map<String, Integer>> tiers =
                upgradeRequiresTiers.get(upgradeId);
        if (tiers != null && tiers.containsKey(tier)) {
            merged.putAll(tiers.get(tier));
        }
        return java.util.Collections.unmodifiableMap(merged);
    }

    /** Required island level to buy any tier of this track (0 = no gate). */
    public int upgradeRequiresIslandLevel(final String upgradeId) {
        return upgradeRequiresIslandLevel.getOrDefault(upgradeId, 0);
    }

    /** Required best-role level (any role) to buy any tier of this track (0 = no gate). */
    public int upgradeRequiresRoleLevel(final String upgradeId) {
        return upgradeRequiresRoleLevel.getOrDefault(upgradeId, 0);
    }

    /** Max level of a buff (0 if the buff is not configured). */
    public int buffMaxTier(final String buffId) {
        return buffMaxTiers.getOrDefault(buffId, 0);
    }

    /** Price (Sky Tokens) for buying buff level {@code tier+1}, or empty past the configured list. */
    public java.util.OptionalLong buffCost(final String buffId, final int tier) {
        final java.util.List<Long> costs = buffCosts.get(buffId);
        if (costs == null || tier < 0 || tier >= costs.size()) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of(costs.get(tier));
    }

    /** Percent per level of a buff (0 when unconfigured). */
    public int buffPercent(final String buffId) {
        return buffPercents.getOrDefault(buffId, 0);
    }

    private static java.util.List<Long> longCosts(
            final org.bukkit.configuration.file.FileConfiguration config,
            final String path, final java.util.List<Long> fallback) {
        final java.util.List<?> raw = config.getList(path);
        if (raw == null) {
            return fallback;
        }
        final java.util.List<Long> out = new java.util.ArrayList<>();
        for (final Object o : raw) {
            if (o instanceof Number n) {
                out.add(Math.max(0L, n.longValue()));
            } else {
                try {
                    out.add(Math.max(0L, Long.parseLong(String.valueOf(o))));
                } catch (NumberFormatException ignored) { /* skip junk */ }
            }
        }
        return out.isEmpty() ? fallback : java.util.List.copyOf(out);
    }
}
