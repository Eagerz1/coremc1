package com.coremc.core.spawner;

import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.shop.Money;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Renders the spawner progression menu: every mob spawner laid out by
 * group, plus the island's luck upgrade. Clicks are routed by
 * {@link SpawnerMenuListener}; purchases go through the same
 * {@link SpawnerService#buy} and {@link SpawnerService#luckUpgrade}
 * flows as the commands, so unlock rules and messages stay identical.
 */
public final class SpawnerMenuGui {

    private static final String TITLE = "&3&lCOREMC &8— &bSpawners";

    private final SpawnerConfig config;
    private final SpawnerService spawners;
    private final IslandService islands;

    public SpawnerMenuGui(final SpawnerConfig config, final SpawnerService spawners,
                          final IslandService islands) {
        this.config = config;
        this.spawners = spawners;
        this.islands = islands;
    }

    /** Opens the spawner menu for a player (island holders see live luck state). */
    public void open(final Player player) {
        final SpawnerMenuHolder holder = new SpawnerMenuHolder();
        final Inventory inventory = Bukkit.createInventory(holder, SpawnerMenuLayout.SIZE,
                ColorUtil.colorize(TITLE));
        holder.inventory(inventory);

        inventory.setItem(SpawnerMenuLayout.GUIDE, guideItem());
        inventory.setItem(SpawnerMenuLayout.LUCK, luckItem(player));
        renderMobs(inventory);
        inventory.setItem(SpawnerMenuLayout.CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack guideItem() {
        return GuiItems.item(Material.WRITTEN_BOOK, "&b&lSpawner Guide",
                "&7Buy mob spawners with coins,",
                "&7unlock them with essence and",
                "&7their drops, then upgrade each",
                "&7one through its variants.",
                "&7Luck improves drop rolls.",
                "&8/spawner help for commands.");
    }

    private ItemStack luckItem(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        final int level = island == null ? 0 : spawners.luckOf(island);
        final List<String> lore = new java.util.ArrayList<>();
        lore.add("&7Better drops from spawner mobs.");
        lore.add("&7Unique drop chance: &f"
                + Math.round(config.uniqueDropChance(level) * 100.0) + "%");
        if (level >= config.luckMaxLevel()) {
            lore.add("&aLuck fully upgraded!");
        } else {
            lore.add("&7Next level: &e" + Money.format(config.luckCost(level + 1), "$"));
            lore.add("&eClick to upgrade");
        }
        lore.add("&7Level: &f" + level + "&7/&f" + config.luckMaxLevel());
        return GuiItems.item(Material.RABBIT_FOOT, "&d&lSpawner Luck",
                lore.toArray(new String[0]));
    }

    private void renderMobs(final Inventory inventory) {
        for (int flatIndex = 0; flatIndex < SpawnerMenuLayout.MAX_MOBS; flatIndex++) {
            final SpawnerMob mob = SpawnerMenuLayout.mob(config, flatIndex);
            if (mob == null) {
                break;
            }
            inventory.setItem(SpawnerMenuLayout.mobSlot(flatIndex), mobItem(mob));
        }
    }

    /**
     * The mob's icon is a real spawner block with the mob rendered inside
     * the cage — a spawner ItemStack whose BlockStateMeta carries a
     * CreatureSpawner state with the mob's spawn type baked in.
     */
    private ItemStack mobItem(final SpawnerMob mob) {
        final SpawnerGroup group = config.groupOf(mob.id());
        final ItemStack item = GuiItems.item(Material.SPAWNER, "&f&l" + mob.name(),
                "&7Group: &f" + (group == null ? "?" : group.name()),
                "&7Spawner: &e" + Money.format(mob.spawnerCost(), "$"),
                "&7Unlock: &f" + mob.unlockEssence() + " &7essence"
                        + (mob.unlockDrops().isEmpty() ? "" : " &7+ drops"),
                "&eClick to buy");
        if (item.getItemMeta() instanceof BlockStateMeta meta) {
            if (meta.getBlockState() instanceof CreatureSpawner spawner) {
                spawner.setSpawnedType(mob.entity());
                meta.setBlockState(spawner);
                item.setItemMeta(meta);
            }
        }
        return item;
    }
}
