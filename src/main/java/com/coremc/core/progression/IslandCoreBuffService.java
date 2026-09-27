package com.coremc.core.progression;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.quest.QuestProgressService;
import com.coremc.core.season.SeasonJourneyService;
import com.coremc.core.season.SeasonXpSource;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.Money;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Persistent Island Core buff runtime. This service owns only island build
 * choices and island-scoped proc states; server-wide hourly events live in
 * {@link ServerEventService}. It is listener-driven and never scans islands.
 */
public final class IslandCoreBuffService {

    public record Hotspot(String world, double x, double y, double z, double radius, long until) {
        boolean contains(final Location location, final long now) {
            if (location == null || location.getWorld() == null || until <= now
                    || !world.equals(location.getWorld().getName())) {
                return false;
            }
            return location.distanceSquared(new Location(location.getWorld(), x, y, z)) <= radius * radius;
        }
    }

    private record RecentRole(UUID player, String role, long at) {
    }

    private final JavaPlugin plugin;
    private final IslandCoreBuffConfig config;
    private final IslandProgressionService progression;
    private final IslandService islands;
    private final EconomyService economy;
    private final MessageService messages;
    private final GameplayModifierService modifiers;
    private final Random random;
    private MiningCubeIntegration miningCubes = MiningCubeIntegration.none();
    private GeneratorIntegration generators = GeneratorIntegration.none();
    private RoleIntegration roles = RoleIntegration.fallback();
    private QuestProgressService quests;
    private SeasonJourneyService seasonJourney;
    private final Map<UUID, Hotspot> hotspots = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, RecentRole>> recentRoles = new LinkedHashMap<>();
    private final Map<UUID, Map<String, BossBar>> bossBars = new LinkedHashMap<>();

    public IslandCoreBuffService(final JavaPlugin plugin, final IslandCoreBuffConfig config,
                                 final IslandProgressionService progression, final IslandService islands,
                                 final EconomyService economy, final MessageService messages,
                                 final GameplayModifierService modifiers) {
        this(plugin, config, progression, islands, economy, messages, modifiers, new Random());
    }

    IslandCoreBuffService(final JavaPlugin plugin, final IslandCoreBuffConfig config,
                          final IslandProgressionService progression, final IslandService islands,
                          final EconomyService economy, final MessageService messages,
                          final GameplayModifierService modifiers, final Random random) {
        this.plugin = plugin;
        this.config = config;
        this.progression = progression;
        this.islands = islands;
        this.economy = economy;
        this.messages = messages;
        this.modifiers = modifiers;
        this.random = random;
        if (plugin != null) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickVisuals, 20L, 20L);
        }
    }

    public IslandCoreBuffConfig config() {
        return config;
    }

    public void setMiningCubes(final MiningCubeIntegration miningCubes) {
        this.miningCubes = miningCubes == null ? MiningCubeIntegration.none() : miningCubes;
    }

    public void setGenerators(final GeneratorIntegration generators) {
        this.generators = generators == null ? GeneratorIntegration.none() : generators;
    }

    public void setRoles(final RoleIntegration roles) {
        this.roles = roles == null ? RoleIntegration.fallback() : roles;
    }

    public void attachQuests(final QuestProgressService quests) {
        this.quests = quests;
    }

    public void attachSeasonJourney(final SeasonJourneyService seasonJourney) {
        this.seasonJourney = seasonJourney;
    }

    public boolean isUnlocked(final Island island, final String buffId) {
        return island != null && progression.profile(island).ownedModules().contains(normalise(buffId));
    }

    public boolean isEquipped(final Island island, final String buffId) {
        return island != null && progression.profile(island).activeModules().contains(normalise(buffId));
    }

    public boolean isStateActive(final Island island, final String state) {
        return island != null && progression.profile(island).stateActive(normalise(state), now());
    }

    public String fortuneFocus(final Island island) {
        if (island == null) {
            return "";
        }
        return normalise(progression.profile(island).fortuneFocus());
    }

    public long swapCooldownRemainingMillis(final Island island) {
        if (island == null) {
            return 0L;
        }
        final long last = progression.profile(island).lastModuleSwapAt();
        if (last <= 0L) {
            return 0L;
        }
        return Math.max(0L, last + config.swapCooldownMillis() - now());
    }

    public boolean canUnlock(final Player player, final Island island,
                             final IslandCoreBuffConfig.BuffDef buff) {
        return missingUnlockRequirements(player, island, buff).isEmpty();
    }

    public List<String> missingUnlockRequirements(final Player player, final Island island,
                                                  final IslandCoreBuffConfig.BuffDef buff) {
        final List<String> missing = new ArrayList<>();
        if (island == null || buff == null) {
            missing.add("island");
            return missing;
        }
        final int islandLevel = progression.level(island);
        if (islandLevel < buff.islandLevel()) {
            missing.add("Island Lvl " + buff.islandLevel());
        }
        if (buff.skyTokens() > 0 && progression.skyTokens(island) < buff.skyTokens()) {
            missing.add((buff.skyTokens() - progression.skyTokens(island)) + " Sky Tokens");
        }
        if (buff.money() > 0.0D) {
            if (economy == null || player == null) {
                missing.add("money");
            } else if (!economy.has(player.getUniqueId(), buff.money())) {
                missing.add(Money.format(buff.money() - economy.balance(player.getUniqueId()), "$"));
            }
        }
        for (final String required : buff.masteryRequires()) {
            final String[] parts = required.split("\\.", 2);
            if (parts.length != 2 || progression.profile(island).masteryLevel(parts[0], parts[1]) <= 0) {
                missing.add(readable(required));
            }
        }
        return missing;
    }

    /** Click flow: locked -> unlock if possible; unlocked -> equip/unequip within slots/cooldown. */
    public boolean clickBuff(final Player player, final String buffId) {
        final Island island = player == null ? null : islands.islandOf(player.getUniqueId());
        if (island == null) {
            if (messages != null && player != null) {
                messages.sendPrefixed(player, "island.no-island");
            }
            return false;
        }
        if (!island.isOwner(player.getUniqueId())) {
            if (messages != null) {
                messages.sendPrefixed(player, "island.not-owner");
            }
            return false;
        }
        final IslandCoreBuffConfig.BuffDef buff = config.buff(buffId);
        if (buff == null) {
            return false;
        }
        if (!isUnlocked(island, buff.id())) {
            return unlock(player, island, buff);
        }
        return toggleEquip(player, island, buff.id());
    }

    public boolean unlock(final Player player, final Island island, final IslandCoreBuffConfig.BuffDef buff) {
        if (isUnlocked(island, buff.id())) {
            return false;
        }
        final List<String> missing = missingUnlockRequirements(player, island, buff);
        if (!missing.isEmpty()) {
            if (messages != null && player != null) {
                messages.sendPrefixed(player, "core-buff.missing", Map.of("missing", String.join(", ", missing)));
            }
            return false;
        }
        if (economy != null && player != null && buff.money() > 0.0D) {
            economy.withdraw(player.getUniqueId(), buff.money());
        }
        progression.profile(island).takeSkyTokens(buff.skyTokens());
        progression.profile(island).addOwnedModule(buff.id());
        if (seasonJourney != null && player != null) {
            seasonJourney.addConfiguredXpOnce(player.getUniqueId(), SeasonXpSource.CORE_UNLOCK,
                    "core-unlock:" + island.id() + ":" + buff.id());
        }
        progression.saveNow();
        if (messages != null && player != null) {
            messages.sendPrefixed(player, "core-buff.unlocked", Map.of("buff", buff.name()));
        }
        return true;
    }

    public boolean toggleEquip(final Player player, final Island island, final String buffId) {
        final String id = normalise(buffId);
        if (!isUnlocked(island, id)) {
            return false;
        }
        final long remaining = swapCooldownRemainingMillis(island);
        if (remaining > 0L) {
            if (messages != null && player != null) {
                messages.sendPrefixed(player, "core-buff.cooldown",
                        Map.of("time", formatDuration(remaining)));
            }
            return false;
        }
        final IslandProgressionProfile profile = progression.profile(island);
        final CoreBuffEquipRules.Result result = CoreBuffEquipRules.toggle(profile.ownedModules(),
                profile.activeModules(), id, progression.moduleSlots(island), profile.lastModuleSwapAt(),
                now(), config.swapCooldownMillis());
        if (result.status() == CoreBuffEquipRules.Status.NO_SLOT) {
            if (messages != null && player != null) {
                messages.sendPrefixed(player, "core-buff.no-slot");
            }
            return false;
        }
        if (result.status() == CoreBuffEquipRules.Status.COOLDOWN) {
            if (messages != null && player != null) {
                messages.sendPrefixed(player, "core-buff.cooldown",
                        Map.of("time", formatDuration(swapCooldownRemainingMillis(island))));
            }
            return false;
        }
        if (!result.changed()) {
            return false;
        }
        profile.setActiveModules(result.active());
        profile.setLastModuleSwapAt(result.lastSwapAt());
        progression.saveNow();
        if (messages != null && player != null) {
            final IslandCoreBuffConfig.BuffDef buff = config.buff(id);
            messages.sendPrefixed(player, result.status() == CoreBuffEquipRules.Status.EQUIPPED
                            ? "core-buff.equipped" : "core-buff.unequipped",
                    Map.of("buff", buff == null ? id : buff.name()));
        }
        return true;
    }

    public boolean selectFortuneFocus(final Island island, final String focus) {
        final IslandCoreBuffConfig.BuffDef buff = config.buff("fortune-cycle");
        if (island == null || buff == null || !isEquipped(island, "fortune-cycle")) {
            return false;
        }
        final String clean = normalise(focus);
        if (!buff.list("focuses").isEmpty() && !buff.list("focuses").contains(clean)) {
            return false;
        }
        final IslandProgressionProfile profile = progression.profile(island);
        if (profile.fortuneSelectedAt() > 0L
                && profile.fortuneSelectedAt() + config.fortuneCooldownMillis() > now()) {
            return false;
        }
        profile.setFortuneFocus(clean, now());
        progression.saveNow();
        return true;
    }

    public void recordActiveGameplay(final Player player, final Island island, final String sourceId,
                                     final long units, final boolean passive) {
        if (!config.enabled() || island == null || units <= 0L || passive) {
            return;
        }
        final String source = normalise(sourceId);
        final IslandProgressionProfile profile = progression.profile(island);
        updateRecentRoles(player, island, source);
        handleMomentum(island, profile, source, units);
        handleRoleSynergy(island, profile);
        handleCoreDiscovery(island, source);
        handleBranchProc(player, island, source, units);
        progression.saveNow();
    }

    private void updateRecentRoles(final Player player, final Island island, final String source) {
        if (player == null) {
            return;
        }
        final String role = normalise(roles.roleFor(player, source));
        recentRoles.computeIfAbsent(island.id(), ignored -> new LinkedHashMap<>())
                .put(player.getUniqueId(), new RecentRole(player.getUniqueId(), role, now()));
    }

    private void handleMomentum(final Island island, final IslandProgressionProfile profile,
                                final String source, final long units) {
        if (!isEquipped(island, "momentum") || profile.stateActive("momentum", now())) {
            return;
        }
        final IslandCoreBuffConfig.BuffDef buff = config.buff("momentum");
        final double fill = buff == null ? 0.0D : buff.number(source + "-fill", 0.0D) * units;
        profile.addMomentum(fill, config.momentumMax());
        if (profile.momentum() >= config.momentumMax()) {
            profile.setMomentum(0.0D);
            startState(island, "momentum", duration(buff, 600));
        }
    }

    private void handleRoleSynergy(final Island island, final IslandProgressionProfile profile) {
        if (!isEquipped(island, "role-synergy")) {
            return;
        }
        final IslandCoreBuffConfig.BuffDef buff = config.buff("role-synergy");
        final long window = seconds(buff, "recent-window-seconds", 180) * 1000L;
        final int minimum = (int) Math.max(1, buff == null ? 2 : buff.number("minimum-distinct-roles", 2));
        final Map<UUID, RecentRole> recent = recentRoles.getOrDefault(island.id(), Map.of());
        final Set<String> rolesSeen = new java.util.HashSet<>();
        for (final RecentRole role : recent.values()) {
            if (now() - role.at() <= window) {
                rolesSeen.add(role.role());
            }
        }
        if (rolesSeen.size() >= minimum) {
            startState(island, "role-synergy", duration(buff, 180));
        }
    }

    private void handleCoreDiscovery(final Island island, final String source) {
        if (!isEquipped(island, "core-discovery")) {
            return;
        }
        final IslandCoreBuffConfig.BuffDef buff = config.buff("core-discovery");
        final double chance = chance(island, buff, source);
        if (random.nextDouble() <= chance) {
            progression.addDiscovery(island, discoveryFor(source), 1);
        }
    }

    private void handleBranchProc(final Player player, final Island island, final String source, final long units) {
        switch (source) {
            case "mining" -> tryRichVein(player, island, player == null ? null : player.getLocation());
            case "fishing" -> tryDeepWaters(player, island, player == null ? null : player.getLocation());
            case "slayer" -> trySlayerFrenzy(player, island);
            case "spawner", "industry" -> tryGeneratorOverdrive(island);
            default -> {
            }
        }
        tryCoreSurge(island);
    }

    public boolean tryRichVein(final Player player, final Island island, final Location location) {
        final IslandCoreBuffConfig.BuffDef buff = config.buff("rich-veins");
        if (!isEquipped(island, "rich-veins") || buff == null || location == null
                || !miningCubes.isMiningCubeBlock(island, location)
                || !cooldownReady(island, "rich-veins") || random.nextDouble() > chance(island, buff, "mining")) {
            return false;
        }
        final int min = (int) buff.number("size-min", 6);
        final int max = Math.max(min, (int) buff.number("size-max", min));
        final int size = min + random.nextInt(Math.max(1, max - min + 1));
        miningCubes.spawnRichVein(island, location, buff, size);
        setCooldown(island, "rich-veins", seconds(buff, "cooldown-seconds", 180));
        if (quests != null) {
            quests.publish(player, island, "island-buff-triggered", 1L, Map.of("buff", "rich-veins"));
            quests.publish(player, island, "rich-vein-discovery", 1L);
        }
        if (player != null) {
            feedback(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        }
        return true;
    }

    public boolean tryDeepWaters(final Player player, final Island island, final Location location) {
        final IslandCoreBuffConfig.BuffDef buff = config.buff("deep-waters");
        if (!isEquipped(island, "deep-waters") || buff == null || location == null
                || !cooldownReady(island, "deep-waters") || random.nextDouble() > chance(island, buff, "fishing")) {
            return false;
        }
        final long duration = duration(buff, 120);
        final double radius = buff.number("radius", 8.0D);
        hotspots.put(island.id(), new Hotspot(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), radius, now() + duration));
        startState(island, "deep-waters", duration);
        setCooldown(island, "deep-waters", seconds(buff, "cooldown-seconds", 300));
        if (quests != null) {
            quests.publish(player, island, "island-buff-triggered", 1L, Map.of("buff", "deep-waters"));
            quests.publish(player, island, "fishing-hotspot", 1L);
        }
        if (player != null) {
            feedback(player, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED);
        }
        return true;
    }

    public boolean trySlayerFrenzy(final Player player, final Island island) {
        final IslandCoreBuffConfig.BuffDef buff = config.buff("slayer-frenzy");
        if (!isEquipped(island, "slayer-frenzy") || buff == null || !cooldownReady(island, "slayer-frenzy")
                || random.nextDouble() > chance(island, buff, "slayer")) {
            return false;
        }
        startState(island, "slayer-frenzy", duration(buff, 30));
        setCooldown(island, "slayer-frenzy", seconds(buff, "cooldown-seconds", 240));
        if (quests != null) {
            quests.publish(player, island, "island-buff-triggered", 1L, Map.of("buff", "slayer-frenzy"));
            quests.publish(player, island, "slayer-frenzy", 1L);
        }
        if (player != null) {
            feedback(player, Sound.ENTITY_WITHER_SPAWN);
        }
        return true;
    }

    public boolean tryGeneratorOverdrive(final Island island) {
        final IslandCoreBuffConfig.BuffDef buff = config.buff("generator-overdrive");
        if (!isEquipped(island, "generator-overdrive") || buff == null
                || !cooldownReady(island, "generator-overdrive")
                || random.nextDouble() > chance(island, buff, "industry")) {
            return false;
        }
        final long duration = duration(buff, 90);
        final int accepted = generators.activateOverdrive(island, buff, duration,
                buff.number("speed-multiplier", 1.5D));
        if (accepted <= 0) {
            return false;
        }
        startState(island, "generator-overdrive", duration);
        setCooldown(island, "generator-overdrive", seconds(buff, "cooldown-seconds", 300));
        if (quests != null) {
            quests.publishIsland(island, "island-buff-triggered", 1L, Map.of("buff", "generator-overdrive"));
        }
        return true;
    }

    public boolean tryCoreSurge(final Island island) {
        final IslandCoreBuffConfig.BuffDef buff = config.buff("core-surge");
        if (!isEquipped(island, "core-surge") || buff == null || !cooldownReady(island, "core-surge")
                || random.nextDouble() > chance(island, buff, "core")) {
            return false;
        }
        startState(island, "core-surge", duration(buff, 300));
        setCooldown(island, "core-surge", seconds(buff, "cooldown-seconds", 900));
        if (quests != null) {
            quests.publishIsland(island, "island-buff-triggered", 1L, Map.of("buff", "core-surge"));
        }
        return true;
    }

    public boolean isFishingHotspotActive(final Island island, final Location location) {
        final Hotspot hotspot = island == null ? null : hotspots.get(island.id());
        return hotspot != null && hotspot.contains(location, now());
    }

    public double momentumProgress(final Island island) {
        return island == null ? 0.0D : progression.profile(island).momentum();
    }

    public void onIslandDeleted(final Island island) {
        if (island == null) {
            return;
        }
        hotspots.remove(island.id());
        recentRoles.remove(island.id());
        final Map<String, BossBar> bars = bossBars.remove(island.id());
        if (bars != null) {
            for (final BossBar bar : bars.values()) {
                bar.removeAll();
            }
        }
    }

    public void shutdown() {
        for (final Map<String, BossBar> bars : bossBars.values()) {
            for (final BossBar bar : bars.values()) {
                bar.removeAll();
            }
        }
        bossBars.clear();
    }

    private boolean cooldownReady(final Island island, final String id) {
        return progression.profile(island).cooldownUntil(id) <= now();
    }

    private void setCooldown(final Island island, final String id, final long seconds) {
        progression.profile(island).setCooldownUntil(id, now() + seconds * 1000L);
    }

    private double chance(final Island island, final IslandCoreBuffConfig.BuffDef buff, final String branch) {
        final double base = buff == null ? 0.0D : buff.number("proc-chance", 0.0D);
        return Math.max(0.0D, Math.min(1.0D,
                base * (modifiers == null ? 1.0D : modifiers.specialFrequencyMultiplier(island, branch))));
    }

    private long duration(final IslandCoreBuffConfig.BuffDef buff, final long fallbackSeconds) {
        return seconds(buff, "duration-seconds", fallbackSeconds) * 1000L;
    }

    private long seconds(final IslandCoreBuffConfig.BuffDef buff, final String key, final long fallback) {
        return Math.max(1L, Math.round(buff == null ? fallback : buff.number(key, fallback)));
    }

    private void startState(final Island island, final String state, final long durationMillis) {
        progression.profile(island).setActiveUntil(state, now() + durationMillis);
        if (plugin != null) {
            final IslandCoreBuffConfig.BuffDef buff = config.buff(state);
            final BossBar bar = Bukkit.createBossBar(ColorUtil.colorize("&b&l"
                            + (buff == null ? readable(state) : buff.name()) + " &7• &f" + formatDuration(durationMillis)),
                    BarColor.BLUE, BarStyle.SOLID);
            bossBars.computeIfAbsent(island.id(), ignored -> new LinkedHashMap<>()).put(state, bar);
            addIslandViewers(island, bar);
        }
    }

    private void tickVisuals() {
        final long now = now();
        for (final Island island : islands.all()) {
            final IslandProgressionProfile profile = progression.profile(island);
            final Map<String, BossBar> bars = bossBars.getOrDefault(island.id(), Map.of());
            for (final Map.Entry<String, Long> active : List.copyOf(profile.activeStates().entrySet())) {
                final String state = active.getKey();
                final long until = active.getValue();
                final BossBar bar = bars.get(state);
                if (until <= now) {
                    profile.setActiveUntil(state, 0L);
                    if (bar != null) {
                        bar.removeAll();
                    }
                    continue;
                }
                if (bar != null) {
                    final IslandCoreBuffConfig.BuffDef buff = config.buff(state);
                    final long remaining = until - now;
                    bar.setTitle(ColorUtil.colorize("&b&l" + (buff == null ? readable(state) : buff.name())
                            + " &7• &f" + formatDuration(remaining)));
                    bar.setProgress(Math.max(0.0D, Math.min(1.0D, remaining
                            / (double) Math.max(1L, duration(buff, remaining / 1000L)))));
                    addIslandViewers(island, bar);
                }
            }
        }
    }

    private void addIslandViewers(final Island island, final BossBar bar) {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (island.isMember(online.getUniqueId()) && !bar.getPlayers().contains(online)) {
                bar.addPlayer(online);
            }
        }
    }

    private String discoveryFor(final String source) {
        return switch (source) {
            case "mining" -> "core-fragment";
            case "fishing" -> "sunken-cache";
            case "slayer" -> "monster-relic";
            case "farming" -> "ancient-seed";
            case "spawner", "industry" -> "generator-core";
            default -> "core-fragment";
        };
    }

    private void feedback(final Player player, final Sound sound) {
        if (player != null && sound != null) {
            player.playSound(player.getLocation(), sound, 0.8f, 1.1f);
        }
    }

    private String formatDuration(final long millis) {
        final long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private String readable(final String raw) {
        final String[] parts = raw.replace('.', ' ').replace('-', ' ').split(" ");
        final StringBuilder builder = new StringBuilder();
        for (final String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String normalise(final String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    long now() {
        return System.currentTimeMillis();
    }
}
