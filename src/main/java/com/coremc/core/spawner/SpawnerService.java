package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Spawner catalogue: kill-based unlock progression + Sky Token purchases.
 *
 * Kill progress lives in the player profile (schema v4) so it survives
 * restarts and role switches. All unlock thresholds and prices come from
 * {@code config.yml spawners:} — never hard-coded.
 */
public final class SpawnerService {

    private final CoreMCPlugin plugin;
    private final Map<String, SpawnerDefinition> definitions = new LinkedHashMap<>();

    public SpawnerService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads {@code spawners:} from config. Returns loaded count. */
    public int load() {
        definitions.clear();
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("spawners");
        if (section == null) {
            return 0;
        }
        for (final String id : section.getKeys(false)) {
            final ConfigurationSection def = section.getConfigurationSection(id);
            if (def == null) {
                continue;
            }
            final org.bukkit.entity.EntityType entity;
            try {
                entity = org.bukkit.entity.EntityType.valueOf(
                        def.getString("entity", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Spawner '" + id + "' has unknown entity; skipped.");
                continue;
            }
            final Material icon = Material.matchMaterial(def.getString("icon", "SPAWNER"));
            definitions.put(id, new SpawnerDefinition(
                    id,
                    def.getString("display", "&f" + id),
                    entity,
                    icon == null ? Material.SPAWNER : icon,
                    Math.max(0L, def.getLong("required-kills", 0L)),
                    Math.max(0L, def.getLong("price", 0L))));
        }
        return definitions.size();
    }

    public List<SpawnerDefinition> all() {
        return new ArrayList<>(definitions.values());
    }

    public Optional<SpawnerDefinition> definition(final String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    // ------------------------------------------------------------------ progress

    public long killsOf(final PlayerProfile profile, final SpawnerDefinition def) {
        return profile.killCountOf(def.killKey());
    }

    public boolean isUnlocked(final PlayerProfile profile, final SpawnerDefinition def) {
        return killsOf(profile, def) >= def.requiredKills();
    }

    /** Records a kill for the killer; messages fire on unlock boundary. */
    public void recordKill(final Player player, final PlayerProfile profile, final org.bukkit.entity.EntityType type) {
        final long before = profile.killCountOf(type.name().toLowerCase(Locale.ROOT));
        profile.addKillCount(type.name().toLowerCase(Locale.ROOT));
        plugin.playerData().markDirty(profile.uuid());
        for (final SpawnerDefinition def : definitions.values()) {
            if (def.entityType() != type) {
                continue;
            }
            if (before < def.requiredKills() && before + 1 >= def.requiredKills()) {
                plugin.messages().sendPrefixed(
                        player, "spawner.unlocked", Map.of("name", ColorUtil.colorize(def.display())));
            }
        }
    }

    // ------------------------------------------------------------------ purchasing

    /** Mints one typed deployable spawner. */
    public Optional<ItemStack> mint(final String id) {
        return definition(id).map(this::mint);
    }

    public ItemStack mint(final SpawnerDefinition def) {
        final ItemStack stack = new ItemStack(Material.SPAWNER);
        final var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(def.display()));
            meta.setLore(List.of(ColorUtil.colorize("&7Place me to set me down.")));
            stack.setItemMeta(meta);
        }
        return plugin.placeables().identify(stack, PlaceableService.Type.SPAWNER, def.id());
    }

    /**
     * Attempts a spawner purchase: must be unlocked and affordable.
     * Withdraws Sky Tokens and grants the item (overflow to ender chest).
     */
    public boolean buy(final Player player, final PlayerProfile profile, final SpawnerDefinition def) {
        if (!isUnlocked(profile, def)) {
            plugin.messages().sendPrefixed(player, "spawner.locked", Map.of(
                    "kills", String.valueOf(killsOf(profile, def)),
                    "needed", String.valueOf(def.requiredKills())));
            return false;
        }
        final String price = String.format(Locale.ROOT, "%,d", def.priceSkyTokens());
        if (!plugin.economy().withdraw(profile, Currency.SKY_TOKENS, def.priceSkyTokens())) {
            plugin.messages().sendPrefixed(player, "spawner.insufficient", Map.of("price", price));
            return false;
        }
        final ItemStack item = mint(def);
        final Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            player.getEnderChest().addItem(overflow.values().toArray(ItemStack[]::new));
            plugin.messages().sendPrefixed(player, "gen.bought-enderchest", Map.of());
        }
        plugin.messages().sendPrefixed(
                player, "spawner.bought", Map.of("name", ColorUtil.colorize(def.display()), "price", price));
        return true;
    }
}
