package com.coremc.core.spawner;

import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
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
 *
 * <p>Styled in the CoreMC GUI design language: coloured names, short
 * small-caps lore, and a live ✔ / ✖ marker on every price.</p>
 */
public final class SpawnerMenuGui {

    private static final String TITLE = "&3&lCOREMC &8— &bSpawners";

    private final SpawnerConfig config;
    private final SpawnerService spawners;
    private final IslandService islands;
    private final EconomyService economy;

    public SpawnerMenuGui(final SpawnerConfig config, final SpawnerService spawners,
                          final IslandService islands, final EconomyService economy) {
        this.config = config;
        this.spawners = spawners;
        this.islands = islands;
        this.economy = economy;
    }

    /** Opens the spawner menu for a player (island holders see live luck state). */
    public void open(final Player player) {
        final SpawnerMenuHolder holder = new SpawnerMenuHolder();
        final Inventory inventory = Bukkit.createInventory(holder, SpawnerMenuLayout.SIZE,
                ColorUtil.colorize(TITLE));
        holder.inventory(inventory);

        inventory.setItem(SpawnerMenuLayout.GUIDE, guideItem());
        inventory.setItem(SpawnerMenuLayout.LUCK, luckItem(player));
        renderMobs(player, inventory);
        inventory.setItem(SpawnerMenuLayout.CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack guideItem() {
        return GuiItems.item(Material.WRITTEN_BOOK, "&b&l" + GuiText.caps("Spawner Guide"),
                "&7" + GuiText.caps("Buy spawners with coins,"),
                "&7" + GuiText.caps("unlock them with essence"),
                "&7" + GuiText.caps("and upgrade their variants."),
                GuiText.blank(),
                GuiText.hint("/spawner help for commands"));
    }

    private ItemStack luckItem(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        final int level = island == null ? 0 : spawners.luckOf(island);
        final boolean maxed = level >= config.luckMaxLevel();
        final double price = maxed ? 0 : config.luckCost(level + 1);
        final boolean affordable = economy != null
                && economy.has(player.getUniqueId(), price);
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Better drops from spawner mobs."));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Level", "&f",
                GuiText.progress(level, config.luckMaxLevel())));
        lore.add(GuiText.value("Drop chance", "&f",
                Math.round(config.uniqueDropChance(level) * 100.0) + "%"));
        if (maxed) {
            lore.add(GuiText.blank());
            lore.add("&a" + GuiText.caps("Fully upgraded"));
        } else {
            lore.add(GuiText.value("Next", "&a",
                    Math.round(config.uniqueDropChance(level + 1) * 100.0) + "%"));
            lore.add(GuiText.cost("Cost", GuiText.money(price), affordable));
            lore.add(GuiText.blank());
            lore.add(affordable ? GuiText.click("Click to upgrade")
                    : "&c" + GuiText.caps("You cannot afford this yet"));
        }
        return GuiItems.item(Material.RABBIT_FOOT, "&d&l" + GuiText.caps("Spawner Luck"), lore);
    }

    private void renderMobs(final Player player, final Inventory inventory) {
        for (int flatIndex = 0; flatIndex < SpawnerMenuLayout.MAX_MOBS; flatIndex++) {
            final SpawnerMob mob = SpawnerMenuLayout.mob(config, flatIndex);
            if (mob == null) {
                break;
            }
            inventory.setItem(SpawnerMenuLayout.mobSlot(flatIndex), mobItem(player, mob));
        }
    }

    /**
     * The mob's icon is a real spawner block with the mob rendered inside
     * the cage — a spawner ItemStack whose BlockStateMeta carries a
     * CreatureSpawner state with the mob's spawn type baked in.
     */
    private ItemStack mobItem(final Player player, final SpawnerMob mob) {
        final SpawnerGroup group = config.groupOf(mob.id());
        final boolean affordable = economy != null
                && economy.has(player.getUniqueId(), mob.spawnerCost());
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Spawns " + mob.name().toLowerCase() + "s for you."));
        lore.add(GuiText.blank());
        lore.add(GuiText.line("Group", "&f", group == null ? "?" : group.name()));
        lore.add(GuiText.value("Unlock", "&f",
                mob.unlockEssence() + " " + GuiText.caps("slayer essence")
                        + (mob.unlockDrops().isEmpty() ? "" : " &7+ " + GuiText.caps("drops"))));
        lore.add(GuiText.cost("Price", GuiText.money(mob.spawnerCost()), affordable));
        lore.add(GuiText.blank());
        lore.add(affordable ? GuiText.click("Click to buy")
                : "&c" + GuiText.caps("You cannot afford this yet"));

        final ItemStack item = GuiItems.item(Material.SPAWNER,
                "&f&l" + GuiText.caps(mob.name() + " Spawner"), lore);
        if (item.getItemMeta() instanceof BlockStateMeta meta) {
            if (meta.getBlockState() instanceof CreatureSpawner spawner) {
                spawner.setSpawnedType(mob.entity());
                meta.setBlockState(spawner);
                item.setItemMeta(meta);
            }
        }
        return affordable ? GuiItems.glow(item) : item;
    }
}
