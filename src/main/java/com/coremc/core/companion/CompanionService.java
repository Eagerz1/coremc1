package com.coremc.core.companion;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.scheduler.TaskService;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/** Player-facing companion ownership, equipping, progression, ability and follower rendering. */
public final class CompanionService implements Listener {

    private final CoreMCPlugin plugin;
    private final Map<String, CompanionDefinition> definitions = new LinkedHashMap<>();
    private final Map<UUID, UUID> followers = new LinkedHashMap<>();

    public CompanionService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    public int load() {
        definitions.clear();
        final var section = plugin.getConfig().getConfigurationSection("companions");
        if (section == null) {
            return 0;
        }
        for (final String rawId : section.getKeys(false)) {
            final var row = section.getConfigurationSection(rawId);
            if (row == null) {
                continue;
            }
            final String id = rawId.toLowerCase(Locale.ROOT);
            final Material icon = Material.matchMaterial(row.getString("icon", "ALLAY_SPAWN_EGG"));
            final RoleCategory category;
            try {
                category = RoleCategory.valueOf(row.getString("category", "MINING").toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException exception) {
                plugin.getLogger().warning("Companion '" + id + "' has an invalid category; skipped.");
                continue;
            }
            if (icon == null || row.getLong("price-sky-tokens", 0L) <= 0L) {
                plugin.getLogger().warning("Companion '" + id + "' has an invalid icon/price; skipped.");
                continue;
            }
            definitions.put(id, new CompanionDefinition(
                    id, row.getString("display", "&f" + id), icon, category,
                    row.getLong("price-sky-tokens"), Math.max(1, row.getInt("max-level", 20)),
                    Math.max(0.0, row.getDouble("xp-bonus-per-level", 1.0))));
        }
        return definitions.size();
    }

    public List<CompanionDefinition> all() {
        return new ArrayList<>(definitions.values());
    }

    public Optional<CompanionDefinition> definition(final String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(definitions.get(id.toLowerCase(Locale.ROOT)));
    }

    public boolean owns(final PlayerProfile profile, final String id) {
        return !profile.companionOf(id).isEmpty();
    }

    public int level(final PlayerProfile profile, final String id) {
        final Object value = profile.companionOf(id).get("level");
        return value instanceof Number number ? Math.max(1, number.intValue()) : 1;
    }

    public long xp(final PlayerProfile profile, final String id) {
        final Object value = profile.companionOf(id).get("xp");
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    public long xpToNext(final int level) {
        return CompanionProgression.xpToNext(level);
    }

    public boolean purchase(final Player player, final PlayerProfile profile, final CompanionDefinition companion) {
        if (owns(profile, companion.id())) {
            plugin.messages().sendPrefixed(player, "companion.already-owned", Map.of());
            return false;
        }
        if (!plugin.economy().withdraw(profile, Currency.SKY_TOKENS, companion.priceTokens())) {
            plugin.messages().sendPrefixed(player, "companion.insufficient", Map.of(
                    "price", String.valueOf(companion.priceTokens())));
            return false;
        }
        profile.setCompanion(companion.id(), new LinkedHashMap<>(Map.of(
                "rarity", "earned", "level", 1, "xp", 0L)));
        profile.equippedCompanion(companion.id());
        plugin.playerData().persistImportant(profile);
        summon(player, companion);
        plugin.messages().sendPrefixed(player, "companion.unlocked", Map.of(
                "companion", ColorUtil.colorize(companion.display())));
        return true;
    }

    public boolean equip(final Player player, final PlayerProfile profile, final String id) {
        final CompanionDefinition companion = definition(id).orElse(null);
        if (companion == null || !owns(profile, companion.id())) {
            return false;
        }
        profile.equippedCompanion(companion.id());
        plugin.playerData().persistImportant(profile);
        summon(player, companion);
        plugin.messages().sendPrefixed(player, "companion.equipped", Map.of(
                "companion", ColorUtil.colorize(companion.display())));
        return true;
    }

    public void unequip(final Player player, final PlayerProfile profile) {
        profile.equippedCompanion("none");
        plugin.playerData().persistImportant(profile);
        removeFollower(player.getUniqueId());
        plugin.messages().sendPrefixed(player, "companion.unequipped", Map.of());
    }

    /** Active companion multiplier for the matching role category. */
    public double xpMultiplier(final PlayerProfile profile, final RoleCategory category) {
        final CompanionDefinition companion = definition(profile.equippedCompanion()).orElse(null);
        if (companion == null || companion.category() != category || !owns(profile, companion.id())) {
            return 1.0;
        }
        final int level = Math.min(companion.maxLevel(), level(profile, companion.id()));
        return 1.0 + (companion.xpBonusPerLevel() * level / 100.0);
    }

    /** Feeds the equipped matching companion from real category activity. */
    public void awardXp(final Player player, final PlayerProfile profile,
            final RoleCategory category, final long amount) {
        final CompanionDefinition companion = definition(profile.equippedCompanion()).orElse(null);
        if (companion == null || companion.category() != category || !owns(profile, companion.id()) || amount <= 0L) {
            return;
        }
        final CompanionProgression.Result result = CompanionProgression.award(
                level(profile, companion.id()), xp(profile, companion.id()), amount, companion.maxLevel());
        profile.setCompanion(companion.id(), new LinkedHashMap<>(Map.of(
                "rarity", "earned", "level", result.level(), "xp", result.xp())));
        plugin.playerData().markDirty(profile.uuid());
        if (result.levelsGained() > 0) {
            plugin.messages().sendPrefixed(player, "companion.level-up", Map.of(
                    "companion", ColorUtil.colorize(companion.display()),
                    "level", String.valueOf(result.level())));
        }
    }

    public void start(final TaskService tasks) {
        tasks.runTimer(this::tickFollowers, 10L, 10L);
    }

    public void shutdown() {
        for (final UUID playerId : List.copyOf(followers.keySet())) {
            removeFollower(playerId);
        }
    }

    private void summon(final Player player, final CompanionDefinition companion) {
        removeFollower(player.getUniqueId());
        final Location location = followerLocation(player);
        final ArmorStand stand = player.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setVisible(false);
            entity.setSmall(true);
            entity.setMarker(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setCustomName(ColorUtil.colorize(companion.display()));
            entity.setCustomNameVisible(true);
            if (entity.getEquipment() != null) {
                entity.getEquipment().setHelmet(new ItemStack(companion.icon()));
            }
        });
        followers.put(player.getUniqueId(), stand.getUniqueId());
    }

    private void tickFollowers() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
            final CompanionDefinition companion = profile == null
                    ? null : definition(profile.equippedCompanion()).orElse(null);
            if (companion == null || !owns(profile, companion.id())) {
                removeFollower(player.getUniqueId());
                continue;
            }
            final Entity follower = entity(player.getUniqueId());
            if (!(follower instanceof ArmorStand) || follower.getWorld() != player.getWorld()) {
                summon(player, companion);
                continue;
            }
            follower.teleport(followerLocation(player));
        }
    }

    private Location followerLocation(final Player player) {
        final Location target = player.getLocation().clone();
        final var behind = target.getDirection().setY(0).normalize().multiply(-1.15);
        return target.add(behind).add(0.8, 0.55 + Math.sin(System.currentTimeMillis() / 350.0) * 0.08, 0.0);
    }

    private Entity entity(final UUID playerId) {
        final UUID entityId = followers.get(playerId);
        return entityId == null ? null : plugin.getServer().getEntity(entityId);
    }

    private void removeFollower(final UUID playerId) {
        final UUID entityId = followers.remove(playerId);
        final Entity entity = entityId == null ? null : plugin.getServer().getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        removeFollower(event.getPlayer().getUniqueId());
    }
}
