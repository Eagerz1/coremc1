package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The shop catalogue: loads {@code plugins/CoreMC/shop.yml} (all prices and
 * items live in that file), serves category/lookup queries and executes
 * purchases.
 *
 * Purchase flow is atomic-by-construction: withdraw first (the economy
 * service rolls back nothing and returns false when funds are short), then
 * deliver; inventory overflow spills into the ender chest with a notice —
 * items are never dropped on the ground, never silently deleted.
 */
public final class ShopService {

    private static final String FILE_NAME = "shop.yml";

    private final CoreMCPlugin plugin;
    private final File file;
    private final Map<ShopCategory, List<ShopEntry>> catalogue = new EnumMap<>(ShopCategory.class);

    public ShopService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** (Re)loads the catalogue; used by startup and by a future reload flow. */
    public int loadCatalogue() {
        catalogue.clear();
        if (!file.isFile()) {
            plugin.saveResource(FILE_NAME, false);
        }
        mergeNewDefaults();
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (final ShopCategory category : ShopCategory.values()) {
            final List<ShopEntry> entries = new ArrayList<>();
            final ConfigurationSection section = yaml.getConfigurationSection(category.key());
            if (section != null) {
                for (final String id : section.getKeys(false)) {
                    parseEntry(id, section).ifPresent(entries::add);
                }
            }
            catalogue.put(category, List.copyOf(entries));
        }
        return catalogue.values().stream().mapToInt(List::size).sum();
    }

    private Optional<ShopEntry> parseEntry(final String id, final ConfigurationSection parent) {
        final ConfigurationSection def = parent.getConfigurationSection(id);
        if (def == null) {
            return Optional.empty();
        }
        final Material material = Material.matchMaterial(def.getString("material", ""));
        if (material == null || material == Material.AIR || !material.isItem()) {
            plugin.getLogger().warning("Shop entry '" + id + "' has invalid material; skipped.");
            return Optional.empty();
        }
        Currency currency;
        try {
            currency = Currency.valueOf(def.getString("currency", "MONEY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Shop entry '" + id + "' has invalid currency; skipped.");
            return Optional.empty();
        }
        final long price = Math.max(0L, def.getLong("price", 0L));
        final int amount = Math.max(1, def.getInt("amount", 1));
        final String display = def.getString("display", "&f" + material.name());
        return Optional.of(new ShopEntry(id, material, display, currency, price, amount));
    }

    /** Same upgrade-safety as config.yml: new jar keys flow into the disk file. */
    private void mergeNewDefaults() {
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(plugin.getResource(FILE_NAME)), StandardCharsets.UTF_8)) {
            final YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
            disk.setDefaults(YamlConfiguration.loadConfiguration(reader));
            disk.options().copyDefaults(true);
            disk.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge shop.yml defaults: " + e.getMessage());
        }
    }

    public List<ShopEntry> entriesOf(final ShopCategory category) {
        return catalogue.getOrDefault(category, List.of());
    }

    public Optional<ShopEntry> entry(final ShopCategory category, final String id) {
        return entriesOf(category).stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * Executes a purchase. Every failure mode is a branded message:
     * insufficient funds are refused before any stock changes hands.
     *
     * @return true on completed purchase
     */
    public boolean purchase(final Player player, final ShopEntry entry) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(player, "shop.unavailable", Map.of());
            return false;
        }
        final String price = String.format(Locale.ROOT, "%,d", entry.price());
        final String currencyName = entry.currency().displayName();
        final boolean free = entry.price() <= 0L; // withdraw(0) would throw by contract
        if (!free && !plugin.economy().withdraw(profile, entry.currency(), entry.price())) {
            plugin.messages().sendPrefixed(player, "shop.insufficient",
                    Map.of("price", price, "currency", currencyName));
            return false;
        }
        final ItemStack item = new ItemStack(entry.material(), entry.amount());
        final var meta = item.getItemMeta();
        if (meta != null && entry.display() != null) {
            meta.setDisplayName(ColorUtil.colorize(entry.display()));
            item.setItemMeta(meta);
        }
        final var delivery = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
        if (delivery == com.coremc.core.util.ItemDelivery.Result.FAILED) {
            // nothing fit anywhere: the rollback restored both inventories, so refund the price.
            if (!free) {
                plugin.economy().deposit(profile, entry.currency(), entry.price());
            }
            plugin.messages().sendPrefixed(player, "purchase.no-space", Map.of());
            return false;
        }
        if (delivery == com.coremc.core.util.ItemDelivery.Result.DELIVERED_TO_ENDER_CHEST) {
            plugin.messages().sendPrefixed(player, "shop.overflow", Map.of());
        }
        plugin.messages().sendPrefixed(player, "shop.bought", Map.of(
                "item", ColorUtil.colorize(entry.display()),
                "price", price,
                "currency", currencyName));
        return true;
    }
}
