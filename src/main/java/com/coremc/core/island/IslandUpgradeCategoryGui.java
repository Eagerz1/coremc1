package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * One upgrade category page (27 slots) listing that category's tracks.
 *
 * Tracks sit in the middle row stepping by 2 (clear spacing, max 4
 * tracks per category):
 *   10 / 12 / 14 / 16  upgrade tracks (click = buy next tier)
 *   22                 Sky Token balance
 *   26                 back to the category hub
 *
 * Tracks whose requirements are unmet render locked (redstone block)
 * with the missing prerequisite named; clicking one explains the
 * requirement instead of charging anything.
 */
public final class IslandUpgradeCategoryGui implements Gui {

    private static final int[] TRACK_SLOTS = {10, 12, 14, 16};
    private static final int SLOT_BALANCE = 22;
    private static final int SLOT_BACK = 26;

    private final CoreMCPlugin plugin;
    private final UpgradeCatalog.Category category;

    public IslandUpgradeCategoryGui(final CoreMCPlugin plugin, final UpgradeCatalog.Category category) {
        this.plugin = plugin;
        this.category = category;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» " + category.color() + category.label() + " Upgrades";
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(13, GuiService.item(
                    Material.BARRIER,
                    "&cNot available",
                    List.of("&7Only the island owner can upgrade.")));
            GuiService.fillGaps(inventory);
            return;
        }
        final Island value = island.get();
        final List<UpgradeCatalog.Track> tracks = UpgradeCatalog.ofCategory(category);

        for (int i = 0; i < tracks.size() && i < TRACK_SLOTS.length; i++) {
            inventory.setItem(TRACK_SLOTS[i], render(tracks.get(i), value));
        }

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

    private org.bukkit.inventory.ItemStack render(final UpgradeCatalog.Track track, final Island island) {
        final int tier = island.upgrades().getOrDefault(track.id(), 0);
        final int max = plugin.coreConfig().upgradeMaxTier(track.id());
        final var cost = plugin.coreConfig().upgradeCost(track.id(), tier);
        final Map.Entry<String, Integer> locked = unmetRequirement(island, track.id());

        final List<String> lore = new ArrayList<>(track.summary());
        lore.add("");
        if ("border".equals(track.id())) {
            lore.add("&7Current: &f" + plugin.islands().effectiveBorder(island) + "x"
                    + plugin.islands().effectiveBorder(island));
        } else if ("member-slots".equals(track.id())) {
            lore.add("&7Capacity: &f" + plugin.islands().memberCapacity(island)
                    + " members");
        } else if (!track.effectText(tier).isEmpty()) {
            lore.add(track.effectText(tier));
        }
        lore.add("");
        if (locked != null) {
            final int have = island.upgrades().getOrDefault(locked.getKey(), 0);
            lore.add("&c&lLOCKED");
            lore.add("&7Requires: &f" + UpgradeCatalog.displayOf(locked.getKey())
                    + " tier " + locked.getValue() + " &8(yours: " + have + "&8)");
            if (tier < max && cost.isPresent()) {
                lore.add("&8Next: tier " + (tier + 1) + " — " + cost.getAsLong() + " Sky Tokens");
            }
            return GuiService.item(
                    Material.REDSTONE_BLOCK,
                    "&c" + track.display() + " &8[LOCKED]",
                    lore);
        }
        if (tier >= max || cost.isEmpty()) {
            lore.add("&a&lMAXED OUT");
        } else {
            lore.add("&7Next tier: &f" + (tier + 1) + "&8/&7" + max);
            lore.add("&7Cost: &b" + cost.getAsLong() + " Sky Tokens");
            lore.add("&eClick to purchase.");
        }
        return GuiService.item(
                tier > 0 ? track.icon() : track.icon(),
                category.color() + track.display()
                        + (tier > 0 ? " &8[&f" + tier + "&8/&7" + max + "&8]" : ""),
                lore);
    }

    /** First unmet purchase requirement, or null when the track can be bought. */
    private Map.Entry<String, Integer> unmetRequirement(final Island island, final String trackId) {
        for (final Map.Entry<String, Integer> requirement :
                plugin.coreConfig().upgradeRequires(trackId).entrySet()) {
            if (island.upgrades().getOrDefault(requirement.getKey(), 0) < requirement.getValue()) {
                return requirement;
            }
        }
        return null;
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new IslandUpgradesGui(plugin));
            return false;
        }
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            return false;
        }
        final List<UpgradeCatalog.Track> tracks = UpgradeCatalog.ofCategory(category);
        for (int i = 0; i < tracks.size() && i < TRACK_SLOTS.length; i++) {
            if (slot == TRACK_SLOTS[i]) {
                return plugin.islands().purchaseUpgrade(viewer, island.get(), tracks.get(i).id());
            }
        }
        return false;
    }
}
