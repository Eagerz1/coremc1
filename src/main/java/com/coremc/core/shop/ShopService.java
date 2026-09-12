package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
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
import org.bukkit.inventory.ItemStack;

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

    public ShopService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
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

    /**
     * Sells every matching item in the player's inventory back to the
     * shop at the entry's per-item sell price. Proceeds ride the
     * sell-boost multiplier for members on their own island.
     *
     * @return true when at least one item sold
     */
    public boolean sell(final Player player, final ShopEntry entry) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(player, "shop.unavailable", Map.of());
            return false;
        }
        final String itemName = ColorUtil.colorize(entry.display());
        if (entry.sellPrice() <= 0L) {
            plugin.messages().sendPrefixed(player, "shop.unsellable", Map.of("item", itemName));
            return false;
        }
        int count = 0;
        for (final ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == entry.material() && sellable(item)) {
                count += item.getAmount();
            }
        }
        if (count <= 0) {
            plugin.messages().sendPrefixed(player, "shop.nothing-to-sell", Map.of("item", itemName));
            return false;
        }
        final double mult = plugin.islandBuffs().sellMult(player);
        final double raw = count * (double) entry.sellPrice() * mult;
        final long total = raw >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(raw));
        try {
            plugin.economy().deposit(profile, entry.currency(), total);
        } catch (final IllegalArgumentException overflow) {
            // Balance cannot hold the proceeds: inventory untouched, nothing sold.
            plugin.messages().sendPrefixed(player, "shop.overflowed", Map.of());
            return false;
        }
        int remaining = count;
        final var inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            final ItemStack item = contents[slot];
            if (item == null || item.getType() != entry.material() || !sellable(item)) {
                continue;
            }
            final int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
        }
        plugin.messages().sendPrefixed(player, "shop.sold", Map.of(
                "amount", String.valueOf(count - remaining),
                "item", itemName,
                "price", String.format(Locale.ROOT, "%,d", total),
                "currency", entry.currency().displayName()));
        return true;
    }

    /**
     * Plain stock only: matching material (checked by the caller),
     * unenchanted, undamaged, and carrying no CoreMC persistent data
     * (OmniTools, keys, placeables and soulbound gear never sell —
     * even if a matching shop entry is ever added).
     */
    private static boolean sellable(final ItemStack item) {
        if (!item.getEnchantments().isEmpty()) {
            return false;
        }
        final var meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        return meta.getPersistentDataContainer().isEmpty();
    }
}
