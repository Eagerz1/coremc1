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
                11,
                GuiService.item(
                        Material.BEACON,
                        "&bBorder Size &7(tier " + tier(value, "border") + ")",
                        List.of(
                                "&7Current: &f" + value.borderSize() + "x" + value.borderSize(),
                                "&7Next tiers protect a larger square",
                                "&ePurchasing arrives with economy upgrades")));
        inventory.setItem(
                13,
                GuiService.item(
                        Material.PLAYER_HEAD,
                        "&dMember Slots &7(tier " + tier(value, "member-slots") + ")",
                        List.of(
                                "&7Capacity: &f" + plugin.islands().memberCapacity(value) + " members",
                                "&8Currently: " + value.members().size() + " member(s)",
                                "&ePurchasing arrives with economy upgrades")));
        inventory.setItem(
                15,
                GuiService.item(
                        Material.EXPERIENCE_BOTTLE,
                        "&bIsland Level &f" + value.level(),
                        List.of("&7Levels grow from island activity and quests")));

        GuiService.fillGaps(inventory);
    }

    private int tier(final Island island, final String upgradeId) {
        return island.upgrades().getOrDefault(upgradeId, 0);
    }
}
