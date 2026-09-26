package com.coremc.core.gens;

import com.coremc.core.island.Island;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders the /gens menu: the whole generator progression in tier
 * order, each entry showing its tier, interval, value and a live
 * ✔ / ✖ price, plus a panel with the player's balance, island points,
 * placed generators and income.
 *
 * <p>Clicks are routed by {@link GensMenuListener}; purchases go
 * through {@link GeneratorService#buy}, the same flow {@code /gens
 * buy} uses, so the rules and messages never fork.</p>
 */
public final class GensMenuGui {

    private static final String TITLE = "&3&lCOREMC &8— &bGenerators";

    private final GeneratorConfig config;
    private final GeneratorService generators;
    private final EconomyService economy;

    public GensMenuGui(final GeneratorConfig config, final GeneratorService generators,
                       final EconomyService economy) {
        this.config = config;
        this.generators = generators;
        this.economy = economy;
    }

    /** Opens the generator menu for a player. */
    public void open(final Player player) {
        final GensMenuHolder holder = new GensMenuHolder();
        final Inventory inventory = Bukkit.createInventory(holder, GensLayout.MENU_SIZE,
                ColorUtil.colorize(TITLE));
        holder.inventory(inventory);

        inventory.setItem(GensLayout.MENU_INFO, panelItem(player));
        renderGenerators(player, inventory);
        inventory.setItem(GensLayout.MENU_BACK, GuiItems.back("island menu"));
        inventory.setItem(GensLayout.MENU_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private void renderGenerators(final Player player, final Inventory inventory) {
        final Island island = generators.islandOf(player);
        final List<GeneratorTier> tiers = config.all();
        for (int index = 0; index < tiers.size() && index < GensLayout.MAX_GENERATORS; index++) {
            inventory.setItem(GensLayout.genSlot(index), generatorItem(player, island, tiers.get(index)));
        }
    }

    /** One catalogue entry — locked, unaffordable and buyable all look different. */
    private ItemStack generatorItem(final Player player, final Island island,
                                    final GeneratorTier tier) {
        final boolean unlocked = island != null && generators.unlocked(island, tier);
        final boolean affordable = economy != null
                && economy.has(player.getUniqueId(), tier.price());
        final int placed = island == null ? 0 : generators.countOnIsland(island.id(), tier.id());
        final List<String> lore = GeneratorLore.menu(tier, unlocked, affordable,
                generators.pointsOf(island), placed);

        if (!unlocked) {
            // Locked generators are deliberately dull: grey name, grey
            // pane icon — never mistakable for something purchasable.
            return GuiItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    GeneratorLore.lockedTitle(tier), lore);
        }
        final ItemStack item = GuiItems.item(tier.icon(), GeneratorLore.title(tier, 1), lore);
        return affordable ? GuiItems.glow(item) : item;
    }

    private ItemStack panelItem(final Player player) {
        final Island island = generators.islandOf(player);
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Balance", "&a",
                GuiText.money(economy == null ? 0 : economy.balance(player.getUniqueId()))));
        lore.add(GuiText.value("Island points", "&f",
                GuiText.number(generators.pointsOf(island))));
        lore.add(GuiText.blank());
        if (island == null) {
            lore.add("&c" + GuiText.caps("You need an island first."));
            lore.add(GuiText.hint("Use /is create"));
        } else {
            lore.add(GuiText.value("Generators", "&f", GuiText.progress(
                    generators.countOnIsland(island.id()), config.maxPerIsland())));
            lore.add(GuiText.value("Income", "&a",
                    GuiText.money(generators.incomePerHour(island.id())) + "/"
                            + GuiText.caps("h")));
        }
        lore.add(GuiText.blank());
        lore.add("&7" + GuiText.caps("Generators pay out on their own"));
        lore.add("&7" + GuiText.caps("while you play. Stack them up."));
        return GuiItems.head(player, "&b&l" + GuiText.caps("Your generators"), lore);
    }
}
