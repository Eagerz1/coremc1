package com.coremc.core.shop;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates the shop catalogue from {@code shop.yml}.
 *
 * Like schematics, a broken shop fails loudly at load time (every
 * problem is collected into one exception) instead of silently
 * rendering half a shop. Item entries are compact
 * {@code MATERIAL:BUY:SELL} strings; SELL may be omitted and then
 * defaults to 25% of BUY.
 *
 * Structure:
 * <pre>
 * starting-balance: 100.0
 * currency-symbol: "$"
 * sections:
 *   building:
 *     name: "Building Blocks"
 *     icon: BRICKS
 *     items:
 *       - "COBBLESTONE:1:0.25"
 * </pre>
 */
public final class ShopConfig {

    /** Maximum sections: the shop root menu has exactly seven section slots. */
    static final int MAX_SECTIONS = 7;

    private static final String FILE_NAME = "shop.yml";

    private final JavaPlugin plugin;
    private final List<ShopSection> sections = new ArrayList<>();
    /** Material -> catalogue entry across ALL sections (for /sell valuation). */
    private final Map<Material, ShopItem> pricedMaterials = new LinkedHashMap<>();
    private double startingBalance = 100.0;
    private double defaultSellPrice = 0.25;
    private String currencySymbol = "$";

    public ShopConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default shop.yml on first run, then parses and validates it. */
    public void load() {
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (final Exception exception) {
            throw new IllegalArgumentException("cannot read " + FILE_NAME + ": " + exception.getMessage(),
                    exception);
        }
        parse(yaml);
    }

    /** Parses and validates a YAML document (also used by tests). */
    void parse(final YamlConfiguration yaml) {
        this.startingBalance = Math.max(0, yaml.getDouble("starting-balance", 100.0));
        final String symbol = yaml.getString("currency-symbol", "$");
        this.currencySymbol = (symbol == null || symbol.isEmpty()) ? "$" : symbol;
        this.defaultSellPrice = Math.max(0, yaml.getDouble("default-sell-price", 0.25));

        final ConfigurationSection root = yaml.getConfigurationSection("sections");
        final List<String> problems = new ArrayList<>();
        final List<ShopSection> parsed = new ArrayList<>();
        if (root == null) {
            problems.add("missing 'sections' mapping");
        } else {
            for (final String id : root.getKeys(false)) {
                final ShopSection section = parseSection(id, root.getConfigurationSection(id), problems);
                if (section != null) {
                    parsed.add(section);
                }
            }
        }
        if (parsed.isEmpty()) {
            problems.add("no valid sections defined");
        }
        if (parsed.size() > MAX_SECTIONS) {
            problems.add("found " + parsed.size() + " sections but the shop root menu only has "
                    + MAX_SECTIONS + " slots");
        }
        // Index every priced material across sections so /sell values any
        // listed item; the same material in two sections would be ambiguous.
        final Map<Material, ShopItem> priced = new LinkedHashMap<>();
        final Map<Material, String> homeSection = new HashMap<>();
        for (final ShopSection section : parsed) {
            for (final ShopItem item : section.items()) {
                if (priced.containsKey(item.material())) {
                    problems.add("material '" + item.material()
                            + "' is listed in both '" + homeSection.get(item.material())
                            + "' and '" + section.id() + "'");
                } else {
                    priced.put(item.material(), item);
                    homeSection.put(item.material(), section.id());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken shop: " + String.join("; ", problems));
        }

        this.sections.clear();
        this.sections.addAll(parsed);
        this.pricedMaterials.clear();
        this.pricedMaterials.putAll(priced);
    }

    private ShopSection parseSection(final String id, final ConfigurationSection section,
                                     final List<String> problems) {
        if (section == null) {
            problems.add("section '" + id + "' is not a mapping");
            return null;
        }
        final String name = section.getString("name", id);
        final String iconKey = section.getString("icon", "CHEST");
        final Material icon = Material.matchMaterial(iconKey == null ? "" : iconKey);
        if (icon == null) {
            problems.add("section '" + id + "': unknown icon material '" + iconKey + "'");
        }

        final List<ShopItem> items = new ArrayList<>();
        final Map<String, Integer> seen = new LinkedHashMap<>();
        for (final String entry : section.getStringList("items")) {
            final String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final String[] parts = trimmed.split(":");
            if (parts.length < 2 || parts.length > 3) {
                problems.add("section '" + id + "': item '" + trimmed
                        + "' must be MATERIAL:BUY:SELL or MATERIAL:BUY");
                continue;
            }
            final Material material = Material.matchMaterial(parts[0].trim());
            if (material == null) {
                problems.add("section '" + id + "': unknown material '" + parts[0].trim() + "'");
                continue;
            }
            final Double buy = price(parts[1], "BUY", id, trimmed, problems);
            if (buy == null) {
                continue;
            }
            final double sell = parts.length == 3
                    ? price(parts[2], "SELL", id, trimmed, problems)
                    : Money.round(buy * 0.25);
            if (buy == 0 && sell == 0) {
                problems.add("section '" + id + "': item '" + trimmed + "' has no buy and no sell price");
                continue;
            }
            if (sell > buy) {
                problems.add("section '" + id + "': item '" + trimmed
                        + "' sells for more than it buys (infinite money loop)");
                continue;
            }
            if (seen.containsKey(material.name())) {
                problems.add("section '" + id + "': duplicate material '" + material.name() + "'");
                continue;
            }
            seen.put(material.name(), items.size());
            items.add(new ShopItem(material, buy, sell));
        }
        if (items.isEmpty()) {
            problems.add("section '" + id + "': no valid items");
        }
        if (icon == null) {
            return null;
        }
        return new ShopSection(id, name, icon, items);
    }

    private Double price(final String raw, final String label, final String section,
                         final String entry, final List<String> problems) {
        try {
            final double value = Double.parseDouble(raw.trim());
            if (value < 0) {
                problems.add("section '" + section + "': negative " + label + " price in '" + entry + "'");
                return null;
            }
            return value;
        } catch (final NumberFormatException exception) {
            problems.add("section '" + section + "': bad " + label + " price in '" + entry + "'");
            return null;
        }
    }

    /** Parsed sections in root-menu order. */
    public List<ShopSection> sections() {
        return List.copyOf(sections);
    }

    /** Section lookup by config id, or {@code null}. */
    public ShopSection section(final String id) {
        for (final ShopSection section : sections) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        return null;
    }

    /** Coins a brand-new player starts with. */
    public double startingBalance() {
        return startingBalance;
    }

    /** Currency symbol used when rendering prices. */
    public String currencySymbol() {
        return currencySymbol;
    }

    /**
     * Sell price for one unit of {@code material} in the /sell window:
     * the catalogue price when the material is listed (0 means the
     * catalogue explicitly refuses it), else the default sell price —
     * so every ordinary item has a price.
     */
    public double sellPrice(final Material material) {
        final ShopItem item = pricedMaterials.get(material);
        return item == null ? defaultSellPrice : item.sellPrice();
    }

    /** Default per-item sell price for materials not in the catalogue. */
    public double defaultSellPrice() {
        return defaultSellPrice;
    }
}
