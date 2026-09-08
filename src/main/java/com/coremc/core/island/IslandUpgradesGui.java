package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Island upgrades hub ({@code /is upgrades}) — the 27-slot category panel.
 *
 * Positions are named constants:
 *   10 Mining   11 Fishing   12 Farming
 *   13 Slaying  14 Logging   16 Island
 *   22 balances              26 back to the island menu
 *
 * Clicking a category opens {@link IslandUpgradeCategoryGui} with that
 * category's tracks.
 */
public final class IslandUpgradesGui implements Gui {

    private static final int SLOT_MINING = 10;
    private static final int SLOT_FISHING = 11;
    private static final int SLOT_FARMING = 12;
    private static final int SLOT_SLAYING = 13;
    private static final int SLOT_LOGGING = 14;
    private static final int SLOT_ISLAND = 16;
    private static final int SLOT_BALANCE = 22;
    private static final int SLOT_BACK = 26;

    public IslandUpgradesGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    private final CoreMCPlugin plugin;

    @Override
    public String title() {
        return "&b&lCOREMC &8» &fIsland Upgrades";
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            if (plugin.islands().islandOf(viewer.getUniqueId()).isPresent()) {
                inventory.setItem(13, GuiService.item(
                        Material.IRON_BARS,
                        "&cOnly the island owner",
                        List.of("&7may open island upgrades.")));
            } else {
                inventory.setItem(13, GuiService.item(
                        Material.BARRIER,
                        "&cYou don't have an island",
                        List.of("&7Create one with &f/is create")));
            }
            GuiService.fillGaps(inventory);
            return;
        }
        final Island value = island.get();

        categoryButton(inventory, SLOT_MINING, UpgradeCatalog.Category.MINING, value);
        categoryButton(inventory, SLOT_FISHING, UpgradeCatalog.Category.FISHING, value);
        categoryButton(inventory, SLOT_FARMING, UpgradeCatalog.Category.FARMING, value);
        categoryButton(inventory, SLOT_SLAYING, UpgradeCatalog.Category.SLAYING, value);
        categoryButton(inventory, SLOT_LOGGING, UpgradeCatalog.Category.LOGGING, value);
        categoryButton(inventory, SLOT_ISLAND, UpgradeCatalog.Category.ISLAND, value);

        final long tokens = plugin.playerData()
                .profileOf(viewer.getUniqueId())
                .map(com.coremc.core.player.PlayerProfile::skyTokens)
                .orElse(0L);
        inventory.setItem(SLOT_BALANCE, GuiService.item(
                Material.NETHER_STAR,
                "&bYour balance",
                List.of("&b" + tokens + " Sky Tokens")));
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&eBack", List.of()));

        GuiService.fillGaps(inventory);
    }

    private void categoryButton(
            final Inventory inventory, final int slot, final UpgradeCatalog.Category category, final Island island) {
        final List<UpgradeCatalog.Track> tracks = UpgradeCatalog.ofCategory(category);
        final List<String> lore = new ArrayList<>();
        int purchased = 0;
        int possible = 0;
        for (final UpgradeCatalog.Track track : tracks) {
            final int tier = island.upgrades().getOrDefault(track.id(), 0);
            final int max = plugin.coreConfig().upgradeMaxTier(track.id());
            purchased += tier;
            possible += max;
            lore.add(category.color() + track.display() + " &8— &f" + tier + "&8/&7" + max);
        }
        lore.add("");
        lore.add("&7Progress: &f" + purchased + "&8/&7" + possible + " tiers");
        lore.add("&eClick to open " + category.label() + ".");
        inventory.setItem(slot, GuiService.item(
                category.icon(),
                category.color() + "&l" + category.label(),
                lore));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new IslandMainGui(plugin));
            return false;
        }
        final UpgradeCatalog.Category category = switch (slot) {
            case SLOT_MINING -> UpgradeCatalog.Category.MINING;
            case SLOT_FISHING -> UpgradeCatalog.Category.FISHING;
            case SLOT_FARMING -> UpgradeCatalog.Category.FARMING;
            case SLOT_SLAYING -> UpgradeCatalog.Category.SLAYING;
            case SLOT_LOGGING -> UpgradeCatalog.Category.LOGGING;
            case SLOT_ISLAND -> UpgradeCatalog.Category.ISLAND;
            default -> null;
        };
        if (category == null) {
            return false;
        }
        plugin.gui().open(viewer, new IslandUpgradeCategoryGui(plugin, category));
        return false;
    }
}
