package com.coremc.core.progression;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandPointsService;
import com.coremc.core.island.IslandService;
import com.coremc.core.island.IslandUpgradeConfig;
import com.coremc.core.quest.QuestProgressService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.Money;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Runtime for island level, Island Mastery, Island Core slots and Sky Tokens.
 * XP is awarded to islands through active gameplay events. Repeated high-volume
 * sources are capped per rolling window, which keeps AFK/passive systems from
 * dominating the island-level race.
 */
public final class IslandProgressionService {

    private final JavaPlugin plugin;
    private final IslandProgressionConfig config;
    private final YamlIslandProgressionStore store;
    private final IslandService islands;
    private final EconomyService economy;
    private final MessageService messages;
    private final IslandPointsService islandPoints;
    private final IslandUpgradeConfig legacyUpgrades;
    private final CoreConfig coreConfig;
    private GameplayModifierService modifiers;
    private IslandCoreBuffService coreBuffs;
    private QuestProgressService quests;
    private final Logger logger;
    private final Map<UUID, IslandProgressionProfile> profiles = new LinkedHashMap<>();

    public IslandProgressionService(final JavaPlugin plugin, final IslandProgressionConfig config,
                                    final YamlIslandProgressionStore store,
                                    final IslandService islands, final EconomyService economy,
                                    final MessageService messages, final IslandPointsService islandPoints,
                                    final IslandUpgradeConfig legacyUpgrades, final CoreConfig coreConfig) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.islands = islands;
        this.economy = economy;
        this.messages = messages;
        this.islandPoints = islandPoints;
        this.legacyUpgrades = legacyUpgrades;
        this.coreConfig = coreConfig;
        this.logger = plugin == null ? Logger.getLogger("CoreMC-Test") : plugin.getLogger();
    }

    public void load() {
        profiles.clear();
        try {
            profiles.putAll(store.loadAll());
        } catch (final IOException exception) {
            logger.severe("Island progression starts fresh — " + exception.getMessage());
        }
    }

    public void shutdown() {
        persist();
    }

    public IslandProgressionConfig config() {
        return config;
    }

    public void attachModifiers(final GameplayModifierService modifiers) {
        this.modifiers = modifiers;
    }

    public void attachCoreBuffs(final IslandCoreBuffService coreBuffs) {
        this.coreBuffs = coreBuffs;
    }

    public void attachQuests(final QuestProgressService quests) {
        this.quests = quests;
    }

    public void saveNow() {
        persist();
    }

    public IslandProgressionProfile profile(final Island island) {
        return profiles.computeIfAbsent(island.id(), IslandProgressionProfile::new);
    }

    public IslandProgressionProfile profile(final UUID islandId) {
        return profiles.computeIfAbsent(islandId, IslandProgressionProfile::new);
    }

    public int level(final Island island) {
        return config.levelForXp(profile(island).xp());
    }

    public long xp(final Island island) {
        return profile(island).xp();
    }

    public long skyTokens(final Island island) {
        return profile(island).skyTokens();
    }

    public int totalMasteryPoints(final Island island) {
        return config.totalMasteryPoints(level(island));
    }

    public int spentMasteryPoints(final Island island) {
        int spent = 0;
        final IslandProgressionProfile profile = profile(island);
        for (final IslandProgressionConfig.MasteryBranch branch : config.branches()) {
            for (final IslandProgressionConfig.MasteryUpgrade upgrade : branch.upgrades()) {
                if (profile.masteryLevel(branch.id(), upgrade.id()) > 0) {
                    spent += upgrade.masteryPoints();
                }
            }
        }
        return spent;
    }

    public int availableMasteryPoints(final Island island) {
        return Math.max(0, totalMasteryPoints(island) - spentMasteryPoints(island));
    }

    public int moduleSlots(final Island island) {
        return config.moduleSlots(level(island));
    }

    public int purchasedInBranch(final Island island, final IslandProgressionConfig.MasteryBranch branch) {
        int purchased = 0;
        for (final IslandProgressionConfig.MasteryUpgrade upgrade : branch.upgrades()) {
            if (hasUpgrade(island, upgrade)) {
                purchased++;
            }
        }
        return purchased;
    }

    public boolean hasUpgrade(final Island island, final IslandProgressionConfig.MasteryUpgrade upgrade) {
        return profile(island).masteryLevel(upgrade.branchId(), upgrade.id()) > 0;
    }

    /**
     * Records active gameplay for the player's island at the given location.
     * The location must lie inside that island's claim when provided.
     *
     * @return XP actually awarded after caps/diminishing returns
     */
    public long recordActivity(final Player player, final Location location,
                               final String sourceId, final long units) {
        if (!config.enabled() || player == null || units <= 0L) {
            return 0L;
        }
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return 0L;
        }
        if (location != null && location.getWorld() != null
                && !island.contains(location.getWorld().getName(),
                location.getBlockX(), location.getBlockZ())) {
            return 0L;
        }
        return recordActivity(player, island, sourceId, units);
    }

    /** Records a configured source directly against an island. */
    public long recordActivity(final Player actor, final Island island,
                               final String sourceId, final long units) {
        if (!config.enabled() || island == null || units <= 0L) {
            return 0L;
        }
        final IslandProgressionConfig.SourceDef source = config.source(sourceId);
        if (source == null) {
            return 0L;
        }
        final IslandProgressionProfile profile = profile(island);
        final long oldXp = profile.xp();
        final int oldLevel = config.levelForXp(oldXp);
        final double multiplier = modifiers == null ? 1.0D : modifiers.islandXpMultiplier(island, source.id());
        final double rawXp = source.xpPerUnit() * units * multiplier;
        final long awarded = Math.max(0L, Math.round(cappedAward(profile, source, rawXp)));
        if (awarded <= 0L) {
            return 0L;
        }
        profile.addXp(awarded);
        final int newLevel = config.levelForXp(profile.xp());
        if (newLevel > oldLevel) {
            onLevelUp(actor, island, profile, oldLevel, newLevel);
        }
        if (coreBuffs != null) {
            coreBuffs.recordActiveGameplay(actor, island, source.id(), units, false);
        }
        if (quests != null) {
            quests.publish(actor, island, "island-xp-gained", awarded, Map.of("source", source.id()));
        }
        persist();
        return awarded;
    }

    public double killProgressionMultiplier(final Player player, final org.bukkit.Location location) {
        if (modifiers == null || player == null) {
            return 1.0D;
        }
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return 1.0D;
        }
        if (location != null && location.getWorld() != null
                && !island.contains(location.getWorld().getName(),
                location.getBlockX(), location.getBlockZ())) {
            return 1.0D;
        }
        return modifiers.killProgressionMultiplier(island);
    }

    /** Clean API for OmniTool branches: output items only, not vanilla drops or money. */
    public double omniToolOutputMultiplier(final Island island) {
        return modifiers == null ? 1.0D : modifiers.omniToolOutputMultiplier(island);
    }

    /** Clean API for OmniTool level/progression XP. */
    public double omniToolProgressionMultiplier(final Island island) {
        return modifiers == null ? 1.0D : modifiers.omniToolProgressionMultiplier(island);
    }

    /** Grants Sky Tokens from systems such as quests. */
    public void grantSkyTokens(final Island island, final long amount) {
        if (!config.enabled() || island == null || amount <= 0L) {
            return;
        }
        profile(island).addSkyTokens(amount);
        persist();
    }

    /** Direct XP awards for non-repeatable milestones. */
    public long awardMilestoneXp(final Player actor, final Island island, final long xp) {
        if (!config.enabled() || island == null || xp <= 0L) {
            return 0L;
        }
        final IslandProgressionProfile profile = profile(island);
        final int oldLevel = config.levelForXp(profile.xp());
        profile.addXp(xp);
        final int newLevel = config.levelForXp(profile.xp());
        if (newLevel > oldLevel) {
            onLevelUp(actor, island, profile, oldLevel, newLevel);
        }
        persist();
        return xp;
    }

    private double cappedAward(final IslandProgressionProfile profile,
                               final IslandProgressionConfig.SourceDef source,
                               final double rawXp) {
        if (!source.capped()) {
            return rawXp;
        }
        final long now = System.currentTimeMillis();
        final IslandProgressionProfile.SourceWindow window = profile.sourceWindow(source.id(), now);
        if (now - window.startedAt() >= source.windowMillis()) {
            window.reset(now);
        }
        if (source.hardCapXp() > 0 && window.xp() >= source.hardCapXp()) {
            return 0.0D;
        }
        double remainingRaw = rawXp;
        double awarded = 0.0D;
        if (source.softCapXp() <= 0 || window.xp() < source.softCapXp()) {
            final double normalRoom = source.softCapXp() <= 0
                    ? remainingRaw
                    : Math.max(0.0D, source.softCapXp() - window.xp());
            final double normal = Math.min(remainingRaw, normalRoom);
            awarded += normal;
            remainingRaw -= normal;
        }
        if (remainingRaw > 0.0D) {
            awarded += remainingRaw * source.softMultiplier();
        }
        if (source.hardCapXp() > 0.0D) {
            awarded = Math.min(awarded, Math.max(0.0D, source.hardCapXp() - window.xp()));
        }
        window.addXp(awarded);
        return awarded;
    }

    private void onLevelUp(final Player actor, final Island island, final IslandProgressionProfile profile,
                           final int oldLevel, final int newLevel) {
        final long tokens = config.skyTokensBetween(oldLevel, newLevel);
        profile.addSkyTokens(tokens);
        if (islandPoints != null && config.levelUpIslandTopPoints() > 0.0D) {
            islandPoints.add(island, config.levelUpIslandTopPoints() * (newLevel - oldLevel));
        }
        if (actor != null && actor.isOnline()) {
            final int gainedPoints = config.totalMasteryPoints(newLevel) - config.totalMasteryPoints(oldLevel);
            final IslandProgressionConfig.LevelDef def = config.levelDef(newLevel);
            messages.sendPrefixed(actor, "progression.level-up", Map.of(
                    "level", String.valueOf(newLevel),
                    "points", String.valueOf(gainedPoints),
                    "tokens", String.valueOf(tokens),
                    "milestone", def == null ? "" : def.milestone()));
        }
    }

    /** Attempts to purchase a one-time mastery node. Owner-only island decision. */
    public boolean purchaseMastery(final Player player, final String branchId, final String upgradeId) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "island.no-island");
            return false;
        }
        if (!island.isOwner(player.getUniqueId())) {
            messages.sendPrefixed(player, "island.not-owner");
            return false;
        }
        if (!config.enabled()) {
            messages.sendPrefixed(player, "progression.unavailable");
            return false;
        }
        final IslandProgressionConfig.MasteryUpgrade upgrade = config.upgrade(branchId, upgradeId);
        if (upgrade == null) {
            return false;
        }
        if (hasUpgrade(island, upgrade)) {
            messages.sendPrefixed(player, "progression.mastery-already");
            return false;
        }
        final List<String> missing = missingRequirements(player, island, upgrade);
        if (!missing.isEmpty()) {
            messages.sendPrefixed(player, "progression.mastery-missing",
                    Map.of("missing", String.join(", ", missing)));
            return false;
        }

        if (upgrade.money() > 0.0D) {
            economy.withdraw(player.getUniqueId(), upgrade.money());
        }
        final IslandProgressionProfile profile = profile(island);
        profile.takeSkyTokens(upgrade.skyTokens());
        profile.setMasteryLevel(upgrade.branchId(), upgrade.id(), 1);
        applyUnlockEffects(island, upgrade);
        if (islandPoints != null) {
            islandPoints.add(island, IslandPointsService.UPGRADE_POINTS);
        }
        persist();
        messages.sendPrefixed(player, "progression.mastery-unlocked", Map.of(
                "upgrade", upgrade.name(),
                "branch", config.branch(upgrade.branchId()).name()));
        return true;
    }

    public List<String> missingRequirements(final Player player, final Island island,
                                            final IslandProgressionConfig.MasteryUpgrade upgrade) {
        final List<String> missing = new ArrayList<>();
        final int islandLevel = level(island);
        if (islandLevel < upgrade.islandLevel()) {
            missing.add("Island Level " + upgrade.islandLevel());
        }
        if (availableMasteryPoints(island) < upgrade.masteryPoints()) {
            missing.add((upgrade.masteryPoints() - availableMasteryPoints(island)) + " Mastery Point(s)");
        }
        if (upgrade.skyTokens() > 0L && skyTokens(island) < upgrade.skyTokens()) {
            missing.add((upgrade.skyTokens() - skyTokens(island)) + " Sky Tokens");
        }
        if (upgrade.money() > 0.0D) {
            if (economy == null) {
                missing.add("economy unavailable");
            } else if (!economy.has(player.getUniqueId(), upgrade.money())) {
                missing.add(Money.format(upgrade.money() - economy.balance(player.getUniqueId()), "$"));
            }
        }
        for (final String required : upgrade.requires()) {
            final String[] parts = required.split("\\.", 2);
            if (parts.length != 2 || profile(island).masteryLevel(parts[0], parts[1]) <= 0) {
                missing.add(required.replace('.', ' '));
            }
        }
        return missing;
    }

    private void applyUnlockEffects(final Island island,
                                    final IslandProgressionConfig.MasteryUpgrade upgrade) {
        final int claimLevel = (int) Math.round(upgrade.effect("claim-size-level"));
        if (claimLevel > island.upgradeLevel("claim-size")
                && legacyUpgrades != null && legacyUpgrades.enabled() && coreConfig != null) {
            island.setUpgradeLevel("claim-size", claimLevel);
            islands.resizeClaim(island, legacyUpgrades.borderSizeFor(coreConfig.islandBorderSize(), claimLevel));
        }
        final int memberLevel = (int) Math.round(upgrade.effect("member-slots-level"));
        if (memberLevel > island.upgradeLevel("member-slots")) {
            island.setUpgradeLevel("member-slots", memberLevel);
            islands.save(island);
        }
    }

    /** Sum of purchased mastery and active-module effects for an island. */
    public double totalEffect(final Island island, final String effectId) {
        if (island == null) {
            return 0.0D;
        }
        double total = 0.0D;
        final IslandProgressionProfile profile = profile(island);
        for (final IslandProgressionConfig.MasteryBranch branch : config.branches()) {
            for (final IslandProgressionConfig.MasteryUpgrade upgrade : branch.upgrades()) {
                if (profile.masteryLevel(branch.id(), upgrade.id()) > 0) {
                    total += upgrade.effect(effectId);
                }
            }
        }
        for (final String moduleId : profile.activeModules()) {
            final IslandProgressionConfig.ModuleDef module = config.module(moduleId);
            if (module != null) {
                total += module.effect(effectId);
            }
        }
        return total;
    }

    public int effectiveSpawnerStackLimit(final Island island, final int baseLimit) {
        return Math.max(1, baseLimit + (int) Math.round(totalEffect(island, "spawner-stack-bonus")));
    }

    public int generatorCapacity(final Island island) {
        return Math.max(0, config.baseGeneratorCapacity()
                + (int) Math.round(totalEffect(island, "generator-capacity")));
    }

    /** Records a rare discovery counter for future module/crafting systems. */
    public void addDiscovery(final Island island, final String discoveryId, final int amount) {
        if (island == null || discoveryId == null || discoveryId.isBlank() || amount <= 0) {
            return;
        }
        profile(island).addDiscovery(discoveryId, amount);
        if (quests != null) {
            quests.publishIsland(island, "discovery-found", amount, Map.of("discovery", discoveryId));
        }
        persist();
    }

    public void onIslandDeleted(final Island island) {
        profiles.remove(island.id());
        persist();
    }

    private void persist() {
        try {
            store.saveAll(profiles);
        } catch (final IOException exception) {
            logger.severe("Could not save island progression: " + exception.getMessage());
        }
    }
}
