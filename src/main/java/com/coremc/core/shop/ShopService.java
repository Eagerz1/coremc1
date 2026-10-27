package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * The shop catalogue: loads {@code plugins/CoreMC/shop.yml} (all prices and
 * items live in that file), serves category/lookup queries and executes
 * purchases and sales.
 *
 * Purchase flow is atomic-by-construction: withdraw first (the economy
 * service rolls back nothing and returns false when funds are short), then
 * deliver; inventory overflow spills into the ender chest with a notice —
 * items are never dropped on the ground, never silently deleted.
 *
 * Sale flow is atomic the other way round: the deposit lands FIRST (a
 * failed/overflowing deposit leaves the inventory untouched), then the
 * counted stock is removed. Only plain stock sells: enchanted, damaged
 * or CoreMC-tagged items are never accepted.
 */
public final class ShopService {

    private static final String FILE_NAME = "shop.yml";

    private final CoreMCPlugin plugin;
    private final File file;
    private final Map<ShopCategory, List<ShopEntry>> catalogue = new EnumMap<>(ShopCategory.class);
    private final ShopTransactionCoordinator transactions;

    public ShopService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        this.transactions = new ShopTransactionCoordinator(plugin);
    }

    /** (Re)loads the catalogue; used by startup and by a future reload flow. */
    public int loadCatalogue() {
        catalogue.clear();
        com.coremc.core.util.YamlFiles.mergeNewDefaults(plugin, FILE_NAME);
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
        final long sellPrice = Math.max(0L, def.getLong("sell-price", 0L));
        final String display = def.getString("display", "&f" + material.name());
        return Optional.of(new ShopEntry(id, material, display, currency, price, amount, sellPrice));
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
        return transactions.purchase(player, entry);
    }

    /**
     * Sells every matching item in the player's inventory back to the
     * shop at the entry's per-item sell price. Proceeds ride the
     * sell-boost multiplier for members on their own island.
     *
     * @return true when at least one item sold
     */
    public boolean sell(final Player player, final ShopEntry entry) {
        return transactions.sell(player, entry);
    }
}
