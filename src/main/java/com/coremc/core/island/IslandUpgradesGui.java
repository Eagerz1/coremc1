package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Island upgrades panel ({@code /is upgrades}).
 *
 * Renders the upgrade track defined by UpgradeTrack: current tier from
 * the island's persisted upgrade map, effect summary and "coming soon"
 * purchase hint. Purchasing wires into EconomyService in a later
 * iteration; the data model and GUI are already upgrade-shaped so that
 * landing it is additive only.
 */
public final class IslandUpgradesGui implements Gui {

    private static final int SLOT_BORDER = 11;
    private static final int SLOT_MEMBERS = 13;
    private static final int SLOT_LEVEL = 15;

    private final CoreMCPlugin plugin;

    public IslandUpgradesGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCoreMC &8— &7Island Upgrades";
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final Optional<Island> island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(
                    13,
                    GuiService.item(
                            Material.BARRIER,
                            "&cYou don't have an island",
                            List.of("&7Create one with &f/is create")));
            return;
        }

        final Island value = island.get();
        inventory.setItem(
                SLOT_BORDER,
                GuiService.item(
                        Material.BEACON,
                        "&bBorder Size &7(tier " + tier(value, "border") + ")",
                        lore(value, "border",
                                "&7Current: &f" + plugin.islands().effectiveBorder(value) + "x"
                                        + plugin.islands().effectiveBorder(value))));
        inventory.setItem(
                SLOT_MEMBERS,
                GuiService.item(
                        Material.PLAYER_HEAD,
                        "&dMember Slots &7(tier " + tier(value, "member-slots") + ")",
                        lore(value, "member-slots",
                                "&7Capacity: &f" + plugin.islands().memberCapacity(value) + " members &8(currently "
                                        + value.members().size() + ")")));
        inventory.setItem(
                SLOT_LEVEL,
                GuiService.item(
                        Material.EXPERIENCE_BOTTLE,
                        "&bIsland Level &f" + value.level(),
                        List.of("&7Levels grow from island activity and quests")));

        GuiService.fillGaps(inventory);
    }

    private List<String> lore(final Island island, final String upgradeId, final String effectLine) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        out.add(effectLine);
        final int tier = tier(island, upgradeId);
        final int max = plugin.coreConfig().upgradeMaxTier(upgradeId);
        final var cost = plugin.coreConfig().upgradeCost(upgradeId, tier);
        if (tier >= max || cost.isEmpty()) {
            out.add("&a&lMAXED OUT");
        } else {
            out.add("&7Next tier: &f" + (tier + 1) + "&8/&7" + max);
            out.add("&7Cost: &b" + cost.getAsLong() + " Sky Tokens");
            out.add("&eClick to purchase.");
        }
        return out;
    }

    private int tier(final Island island, final String upgradeId) {
        return island.upgrades().getOrDefault(upgradeId, 0);
    }

    @Override
    public boolean onClick(final org.bukkit.entity.Player viewer, final int slot) {
        final java.util.Optional<Island> island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            return false;
        }
        if (slot == SLOT_BORDER) {
            return plugin.islands().purchaseUpgrade(viewer, island.get(), "border");
        }
        if (slot == SLOT_MEMBERS) {
            return plugin.islands().purchaseUpgrade(viewer, island.get(), "member-slots");
        }
        return false;
    }
}
