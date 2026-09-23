package com.coremc.core.island;

import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders one island top leaderboard (double chest, 54 slots): the
 * top ten islands of the category as owner heads in two centred rows
 * — ranks 1-5 above, ranks 6-10 below — with back / your rank / close
 * along the bottom.
 *
 * <pre>
 * [10..14]  ranks 1-5
 * [19..23]  ranks 6-10
 * [45] back   [49] your island's rank   [53] close
 * </pre>
 *
 * Rewarded places carry their season gift card reward in the lore.
 */
public final class IsTopBoardGui {

    private final IslandService islands;
    private final IslandPointsService points;

    public IsTopBoardGui(final IslandService islands, final IslandPointsService points) {
        this.islands = islands;
        this.points = points;
    }

    /** Opens the leaderboard board of {@code category}. */
    public void open(final Player player, final IslandTop.Category category) {
        final IsTopBoardHolder holder = new IsTopBoardHolder(category);
        final Inventory inventory = Bukkit.createInventory(holder, IsTopLayout.BOARD_SIZE,
                ColorUtil.colorize("&3&lCOREMC &8— &bIsland Top &8— &b" + category.display()));
        holder.inventory(inventory);

        renderEntries(inventory, player, category);
        inventory.setItem(IsTopLayout.BOARD_BACK, GuiItems.item(Material.ARROW,
                "&c&lBack", "&7Return to the category picker."));
        renderYourRank(inventory, player);
        inventory.setItem(IsTopLayout.BOARD_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private void renderEntries(final Inventory inventory, final Player player,
                               final IslandTop.Category category) {
        final List<Island> entries = IslandTop.top(islands.all(), points::points,
                category, IsTopLayout.BOARD_ENTRIES);
        final Island own = islands.islandOf(player.getUniqueId());
        for (int index = 0; index < entries.size(); index++) {
            final Island island = entries.get(index);
            final int rank = index + 1;
            final double islandPoints = points.points(island);
            final OfflinePlayer owner = Bukkit.getOfflinePlayer(island.owner());
            final List<String> lore = new ArrayList<>();
            lore.add("&7Points: &b" + IslandPointsService.format(islandPoints));
            lore.add("&7Team size: &f" + (1 + island.members().size()));
            final double reward = IsTopLayout.rewardFor(category, rank);
            if (reward > 0) {
                lore.add("&7Season reward: &a" + IslandPointsService.format(reward) + " GC");
            }
            if (own != null && own.id().equals(island.id())) {
                lore.add("&aThis is your island!");
            }
            inventory.setItem(IsTopLayout.boardSlot(rank),
                    GuiItems.head(owner, "&e#" + rank + " &f" + island.ownerName(),
                            lore.toArray(new String[0])));
        }
    }

    private void renderYourRank(final Inventory inventory, final Player player) {
        final Island own = islands.islandOf(player.getUniqueId());
        if (own == null) {
            inventory.setItem(IsTopLayout.BOARD_YOUR_RANK, GuiItems.item(Material.COMPASS,
                    "&fYour island",
                    "&7You don't have an island yet.",
                    "&7Create one with &e/is create&7."));
            return;
        }
        final int rank = IslandTop.rankOf(own, islands.all(), points::points);
        inventory.setItem(IsTopLayout.BOARD_YOUR_RANK, GuiItems.item(Material.COMPASS,
                "&fYour island",
                "&7Category: &f" + IslandTop.categoryOf(own).display(),
                "&7Rank: &e#" + rank,
                "&7Points: &b" + IslandPointsService.format(points.points(own))));
    }
}
