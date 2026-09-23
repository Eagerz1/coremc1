package com.coremc.core.island;

import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders the island top category picker (small chest, 27 slots):
 * how island points work at the top, the three leaderboards spaced
 * across the middle row, close at the bottom right.
 *
 * <pre>
 * [4]  how points + rewards work (book)
 * [10] Solos   [13] Duos   [16] Teams
 * [22] close
 * </pre>
 *
 * Each option opens the 54-slot leaderboard board (see
 * {@link IsTopBoardGui}).
 */
public final class IsTopGui {

    private final IslandService islands;
    private final IslandPointsService points;

    public IsTopGui(final IslandService islands, final IslandPointsService points) {
        this.islands = islands;
        this.points = points;
    }

    /** Opens the category picker. */
    public void open(final Player player) {
        final IsTopHolder holder = new IsTopHolder();
        final Inventory inventory = Bukkit.createInventory(holder, IsTopLayout.PICKER_SIZE,
                ColorUtil.colorize("&3&lCOREMC &8— &bIsland Top"));
        holder.inventory(inventory);

        inventory.setItem(IsTopLayout.PICKER_INFO, info());
        inventory.setItem(IsTopLayout.PICKER_SOLOS, option(IslandTop.Category.SOLOS,
                Material.IRON_INGOT, "&f&lSolos"));
        inventory.setItem(IsTopLayout.PICKER_DUOS, option(IslandTop.Category.DUOS,
                Material.GOLD_INGOT, "&6&lDuos"));
        inventory.setItem(IsTopLayout.PICKER_TEAMS, option(IslandTop.Category.TEAMS,
                Material.DIAMOND, "&b&lTeams"));
        inventory.setItem(IsTopLayout.PICKER_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack info() {
        return GuiItems.item(Material.WRITABLE_BOOK,
                "&b&lIsland Top",
                "&7Islands earn points from block",
                "&7work, play time, upgrades and",
                "&7core levels — the top islands",
                "&7of each leaderboard win",
                "&7webstore gift cards each season.",
                "",
                "&7Rewards go to the island owner:",
                "&fSolos: &etop " + IsTopLayout.rewardedPlaces(IslandTop.Category.SOLOS),
                "&fDuos: &etop " + IsTopLayout.rewardedPlaces(IslandTop.Category.DUOS),
                "&fTeams: &etop " + IsTopLayout.rewardedPlaces(IslandTop.Category.TEAMS),
                "",
                "&ePick a leaderboard below");
    }

    private ItemStack option(final IslandTop.Category category, final Material material,
                             final String name) {
        final List<Island> entries =
                IslandTop.top(islands.all(), points::points, category, 1);
        final String leader = entries.isEmpty()
                ? "&8nobody yet"
                : "&f" + entries.get(0).ownerName() + " &8— &b"
                        + IslandPointsService.format(points.points(entries.get(0)));
        return GuiItems.item(material, name,
                "&7Islands with " + teamSizeLabel(category) + " compete here.",
                "&7Leader: " + leader,
                "&7Season rewards: &atop "
                        + IsTopLayout.rewardedPlaces(category) + " win GC",
                "&eClick to view");
    }

    private static String teamSizeLabel(final IslandTop.Category category) {
        return switch (category) {
            case SOLOS -> "one member";
            case DUOS -> "two members";
            case TEAMS -> "three or more members";
        };
    }
}
