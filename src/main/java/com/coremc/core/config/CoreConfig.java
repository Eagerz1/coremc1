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
        // Border must stay strictly inside the grid cell, otherwise islands
        // on adjacent cells could overlap. Round down to even.
        final int maxBorder = this.islandSpacing / 2;
        if (border > maxBorder) {
            plugin.getLogger().warning("island.border-size " + border + " exceeds spacing/2 (" + maxBorder
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
        // over step-blocks; entries past spacing/2 truncate the list (tier
        // indexes stay aligned: tier T reads sizes[T]).
        this.upgradeBorderSizes.clear();
        for (final int size : config.getIntegerList("island.upgrades.border.sizes")) {
            if (size <= 0) {
                continue;
            }
            final int even = size - (size % 2);
            if (even > this.islandSpacing / 2) {
                plugin.getLogger().warning("island.upgrades.border.sizes entry " + size
                        + " exceeds spacing/2 — truncating larger tiers.");
                break;
            }
            this.upgradeBorderSizes.add(even);
        }
        // The effective border (base + tiers * step) must stay inside the grid cell,
        // like the base border above — otherwise upgraded protection could extend
        // past the cell even though lookups only check the point's own cell.
        final int roomToGrow = (this.islandSpacing / 2) - this.islandBorderSize;
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
                            + " would push the effective border past spacing/2; clamping to " + maxSafeTier + ".");
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
