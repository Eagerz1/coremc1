package com.coremc.core.gen;

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
 * Generator catalogue + purchases. Definitions come from
 * {@code config.yml generators:}; purchases withdraw Credits through
 * {@link com.coremc.core.economy.EconomyService} and mint a PDC-tagged
 * deployable item via {@link PlaceableService}.
 */
public final class GeneratorService {

    private final CoreMCPlugin plugin;
    private final Map<String, GeneratorDefinition> definitions = new LinkedHashMap<>();

    public GeneratorService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads {@code generators:} from config. Returns loaded count. */
    public int load() {
        definitions.clear();
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("generators");
        if (section == null) {
            return 0;
        }
        for (final String id : section.getKeys(false)) {
            final ConfigurationSection def = section.getConfigurationSection(id);
            if (def == null) {
                continue;
            }
            final Material block = Material.matchMaterial(def.getString("block", ""));
            final Material product = Material.matchMaterial(def.getString("product", ""));
            if (block == null || product == null || !block.isBlock()) {
                plugin.getLogger().warning("Generator '" + id + "' has invalid block/product material; skipped.");
                continue;
            }
            definitions.put(id, new GeneratorDefinition(
                    id,
                    def.getString("display", "&f" + id),
                    block,
                    product,
                    Math.max(0L, def.getLong("price", 0L)),
                    Math.max(0L, def.getLong("cooldown-seconds", 5L))));
        }
        return definitions.size();
    }

    public List<GeneratorDefinition> all() {
        return new ArrayList<>(definitions.values());
    }

    public Optional<GeneratorDefinition> definition(final String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    /** Mints one deployable item for {@code id} (empty if unknown). */
    public Optional<ItemStack> mint(final String id) {
        return definition(id).map(this::mint);
    }

    /** Mints one deployable item for the definition. */
    public ItemStack mint(final GeneratorDefinition def) {
        final ItemStack stack = new ItemStack(def.blockMaterial());
        final var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(def.display()));
            meta.setLore(List.of(
                    ColorUtil.colorize("&7Place me to set me down."),
                    ColorUtil.colorize("&7Right-click me for " + def.productName() + ".")));
            stack.setItemMeta(meta);
        }
        return plugin.placeables().identify(stack, PlaceableService.Type.GENERATOR, def.id());
    }

    /**
     * Attempts a catalog purchase: validates funds, withdraws Credits,
     * grants the item (overflow goes to the ender chest). Failure reasons
     * are reported to the player as branded messages.
     *
     * @return true on completed purchase
     */
    public boolean buy(final Player player, final PlayerProfile profile, final GeneratorDefinition def) {
        final String price = String.format(Locale.ROOT, "%,d", def.priceCredits());
        if (!plugin.economy().withdraw(profile, Currency.CREDITS, def.priceCredits())) {
            plugin.messages().sendPrefixed(player, "gen.insufficient", Map.of("price", price));
            return false;
        }
        final ItemStack item = mint(def);
        final Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            player.getEnderChest().addItem(overflow.values().toArray(ItemStack[]::new));
            plugin.messages().sendPrefixed(player, "gen.bought-enderchest", Map.of());
        }
        plugin.messages().sendPrefixed(
                player, "gen.bought", Map.of("name", ColorUtil.colorize(def.display()), "price", price));
        return true;
    }
}
