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
    private String islandWorldName = "world";
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
    private int upgradeBorderMaxTier = 5;
    private int upgradeMemberMaxTier = 5;
    private java.util.List<Long> upgradeBorderCosts = java.util.List.of(10L, 20L, 30L, 40L, 50L);
    private java.util.List<Long> upgradeMemberCosts = java.util.List.of(5L, 10L, 15L, 20L, 25L);

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

        this.islandWorldName = config.getString("island.world", "world");
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
        this.upgradeBorderMaxTier = Math.max(0, config.getInt("island.upgrades.border.max-tier", 5));
        // The effective border (base + tiers * step) must stay inside the grid cell,
        // like the base border above — otherwise upgraded protection could extend
        // past the cell even though lookups only check the point's own cell.
        final int roomToGrow = (this.islandSpacing / 2) - this.islandBorderSize;
        final int maxSafeTier = Math.max(0, roomToGrow / this.upgradeBorderStepBlocks);
        if (this.upgradeBorderMaxTier > maxSafeTier) {
            plugin.getLogger().warning("island.upgrades.border.max-tier " + this.upgradeBorderMaxTier
                    + " would push the effective border past spacing/2; clamping to " + maxSafeTier + ".");
            this.upgradeBorderMaxTier = maxSafeTier;
        }
        this.upgradeMemberMaxTier = Math.max(0, config.getInt("island.upgrades.member-slots.max-tier", 5));
        this.upgradeBorderCosts = longCosts(config, "island.upgrades.border.costs",
                java.util.List.of(10L, 20L, 30L, 40L, 50L));
        this.upgradeMemberCosts = longCosts(config, "island.upgrades.member-slots.costs",
                java.util.List.of(5L, 10L, 15L, 20L, 25L));

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

    /** Max tier of an upgrade track ("border" or "member-slots"). */
    public int upgradeMaxTier(final String upgradeId) {
        return "border".equals(upgradeId) ? upgradeBorderMaxTier
                : "member-slots".equals(upgradeId) ? upgradeMemberMaxTier : 0;
    }

    /** Price (Sky Tokens) for buying tier {@code tier+1}, or empty past max. */
    public java.util.OptionalLong upgradeCost(final String upgradeId, final int tier) {
        final java.util.List<Long> costs =
                "border".equals(upgradeId) ? upgradeBorderCosts
                        : "member-slots".equals(upgradeId) ? upgradeMemberCosts : null;
        if (costs == null || tier < 0 || tier >= costs.size()) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of(costs.get(tier));
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
