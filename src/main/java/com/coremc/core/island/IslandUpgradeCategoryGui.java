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
 * One upgrade category page (54 slots) listing that category's tracks.
 *
 *   rows 2-3 (14 slots)  upgrade tracks (click = buy next tier)
 *   40                   Sky Token balance
 *   44                   back to the category hub
 *
 * Tracks whose requirements are unmet render locked (redstone block)
 * with the missing prerequisite named; clicking one explains the
 * requirement instead of charging anything. Level gates (island
 * level, buyer role level) render locked the same way.
 */
public final class IslandUpgradeCategoryGui implements Gui {

    private static final int[] TRACK_SLOTS =
            {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int SLOT_BALANCE = 40;
    private static final int SLOT_BACK = 44;

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
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(22, GuiService.item(
                    Material.BARRIER,
                    "&cNot available",
                    List.of("&7Only the island owner can upgrade.")));
            GuiService.fillGaps(inventory);
            return;
        }
        final Island value = island.get();
        final List<UpgradeCatalog.Track> tracks = UpgradeCatalog.ofCategory(category);
        final com.coremc.core.player.PlayerProfile viewerProfile =
                plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);

        for (int i = 0; i < tracks.size() && i < TRACK_SLOTS.length; i++) {
            inventory.setItem(TRACK_SLOTS[i], render(tracks.get(i), value, viewerProfile));
        }

        final long tokens = viewerProfile == null ? 0L : viewerProfile.skyTokens();
        inventory.setItem(SLOT_BALANCE, GuiService.item(
                Material.NETHER_STAR,
                "&bYour balance",
                List.of("&b" + tokens + " Sky Tokens")));
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&eBack", List.of()));

        GuiService.fillGaps(inventory);
    }

    private org.bukkit.inventory.ItemStack render(final UpgradeCatalog.Track track, final Island island,
            final com.coremc.core.player.PlayerProfile viewerProfile) {
        final int tier = island.upgrades().getOrDefault(track.id(), 0);
        final int max = plugin.coreConfig().upgradeMaxTier(track.id());
        final var cost = plugin.coreConfig().upgradeCost(track.id(), tier);
        final Map.Entry<String, Integer> locked = unmetRequirement(island, track.id(), tier + 1);

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
        final int needIsland = plugin.coreConfig().upgradeRequiresIslandLevel(track.id());
        if (needIsland > 0 && island.level() < needIsland) {
            lore.add("&c&lLOCKED");
            lore.add("&7Requires island level &f" + needIsland + " &8(yours: " + island.level() + "&8)");
            if (tier < max && cost.isPresent()) {
                lore.add("&8Next: tier " + (tier + 1) + " — " + cost.getAsLong() + " Sky Tokens");
            }
            return GuiService.item(
                    Material.REDSTONE_BLOCK,
                    "&c" + track.display() + " &8[LOCKED]",
                    lore);
        }
        final int needRole = plugin.coreConfig().upgradeRequiresRoleLevel(track.id());
        final int haveRole = viewerProfile == null ? 0 : plugin.roles().maxRoleLevel(viewerProfile);
        if (needRole > 0 && haveRole < needRole) {
            lore.add("&c&lLOCKED");
            lore.add("&7Requires role level &f" + needRole + " &8(any role, yours: " + haveRole + "&8)");
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

    /** First unmet gate for buying exactly {@code nextTier}, or null when buyable. */
    private Map.Entry<String, Integer> unmetRequirement(
            final Island island, final String trackId, final int nextTier) {
        for (final Map.Entry<String, Integer> requirement :
                plugin.coreConfig().upgradeRequiresAt(trackId, nextTier).entrySet()) {
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
