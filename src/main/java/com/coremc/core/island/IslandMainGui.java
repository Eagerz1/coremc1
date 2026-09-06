package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The main island panel opened by bare {@code /is}.
 *
 * Buttons route through the chat commands (single source of behaviour —
 * no duplicated logic), except Upgrades which opens the upgrades panel
 * directly.
 */
public final class IslandMainGui implements Gui {

    private final CoreMCPlugin plugin;

    public IslandMainGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Actionable slots (teleport/info/upgrades/invite/delete) — exported so
     * audits and tests never need to duplicate the layout.
     */
    public static final java.util.Set<Integer> ACTION_SLOTS = java.util.Set.of(10, 12, 14, 16, 22);

    @Override
    public String title() {
        return "&b&lCoreMC &8— &7Island";
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final boolean hasIsland = plugin.islands().islandOf(viewer.getUniqueId()).isPresent();
        final Map<String, String> placeholders = plugin.islands().placeholdersOf(viewer.getUniqueId());

        if (hasIsland) {
            inventory.setItem(
                    10,
                    GuiService.item(
                            Material.ENDER_PEARL,
                            "&aTeleport home",
                            List.of("&7/is home")));
        } else {
            inventory.setItem(
                    10,
                    GuiService.item(
                            Material.GRASS_BLOCK,
                            "&aCreate your island",
                            List.of("&7/is create")));
        }
        inventory.setItem(
                12,
                GuiService.item(
                        Material.BOOK,
                        "&bIsland info",
                        List.of(
                                "&7Level: &f" + placeholders.get("island_level"),
                                "&7Border: &f" + placeholders.get("island_border"),
                                "&7Members: &f" + placeholders.get("island_members"))));
        inventory.setItem(
                14,
                GuiService.item(
                        Material.CRAFTING_TABLE,
                        "&eUpgrades",
                        List.of("&7Open the island upgrades panel")));
        inventory.setItem(
                16,
                GuiService.item(
                        Material.PLAYER_HEAD,
                        "&dInvite a friend",
                        List.of("&7/is invite <player>")));
        inventory.setItem(
                22,
                GuiService.item(
                        Material.BARRIER,
                        "&cDelete island (danger zone)",
                        List.of("&7/is delete", "&cPermanent — asks for confirmation")));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final boolean hasIsland = plugin.islands().islandOf(viewer.getUniqueId()).isPresent();
        switch (slot) {
            case 10 -> viewer.performCommand(hasIsland ? "is home" : "is create");
            case 12 -> viewer.performCommand("is info");
            case 14 -> plugin.gui().open(viewer, new IslandUpgradesGui(plugin));
            case 16 -> plugin.messages()
                    .sendPrefixed(viewer, "island.invite-hint", Map.of());
            case 22 -> viewer.performCommand("is delete");
            default -> {
            }
        }
        return false;
    }
}
