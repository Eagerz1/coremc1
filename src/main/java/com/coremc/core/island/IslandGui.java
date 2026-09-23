package com.coremc.core.island;

import com.coremc.core.shop.Money;
import com.coremc.core.spawner.SpawnerMenuGui;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders the island menu GUIs: the double-chest island menu and its
 * small-chest sub-menus (upgrades, buffs, members, invite). All state
 * lives in the {@link IslandMenu} holder; windows are rebuilt on every
 * navigation; the click controller (see IslandMenuListener) decides
 * what a click means — this class only draws.
 */
public final class IslandGui {

    private static final String MENU_TITLE = "&3&lCOREMC &8— &bIsland";

    private final IslandService islands;
    private final IslandUpgradeConfig config;
    private final IslandBuffService buffs;
    private final SpawnerMenuGui spawnerMenu;

    public IslandGui(final IslandService islands, final IslandUpgradeConfig config,
                     final IslandBuffService buffs, final SpawnerMenuGui spawnerMenu) {
        this.islands = islands;
        this.config = config;
        this.buffs = buffs;
        this.spawnerMenu = spawnerMenu;
    }

    // ------------------------------------------------------------------
    // island menu (double chest)
    // ------------------------------------------------------------------

    /** Opens the main island menu (the caller checked the player has an island). */
    public void openIslandMenu(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.ISLAND);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.MENU_SIZE,
                ColorUtil.colorize(MENU_TITLE));
        menu.inventory(inventory);

        inventory.setItem(IslandLayout.MENU_INFO, infoItem(island));
        inventory.setItem(IslandLayout.MENU_GO_HOME, GuiItems.item(Material.ENDER_PEARL,
                "&a&lGo Home", "&7Teleport to your island."));
        inventory.setItem(IslandLayout.MENU_INVITE, GuiItems.item(Material.WRITABLE_BOOK,
                "&b&lInvite Players", "&7Invite an online player", "&7to your island."));
        inventory.setItem(IslandLayout.MENU_MEMBERS, GuiItems.item(Material.CHEST,
                "&e&lMembers", "&7Everyone on your island.", "&8Owner: kick from here."));
        inventory.setItem(IslandLayout.MENU_BORDER, GuiItems.item(Material.SPYGLASS,
                "&d&lToggle Border", "&7Show or hide your", "&7island's world border."));
        inventory.setItem(IslandLayout.MENU_UPGRADES, upgradeNavItem());
        inventory.setItem(IslandLayout.MENU_BUFFS, buffNavItem(island));
        inventory.setItem(IslandLayout.MENU_SPAWNERS, GuiItems.item(Material.SPAWNER,
                "&5&lSpawner Progression", "&7Browse every mob spawner,", "&7luck and prices."));
        inventory.setItem(IslandLayout.MENU_DELETE, GuiItems.item(Material.TNT,
                "&c&lDelete Island", "&7Permanently delete your island", "&7and evict its members.",
                "&cClick twice to confirm."));
        inventory.setItem(IslandLayout.MENU_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack infoItem(final Island island) {
        final List<String> lore = new ArrayList<>();
        lore.add("&7Owner: &f" + island.ownerName());
        if (config.enabled()) {
            final int limit = config.memberLimit(island.upgradeLevel("member-slots"));
            lore.add("&7Members: &f" + island.members().size() + "&7/&f" + limit);
            lore.add("&7Claim: &f" + island.borderSize() + " x " + island.borderSize());
        } else {
            lore.add("&7Members: &f" + island.members().size());
            lore.add("&7Claim: &f" + island.borderSize() + " x " + island.borderSize());
        }
        lore.add("&7Slot: &f#" + island.slot());
        return GuiItems.item(Material.PLAYER_HEAD, "&b&l" + island.ownerName() + "'s Island",
                lore.toArray(new String[0]));
    }

    private ItemStack upgradeNavItem() {
        if (!config.enabled()) {
            return GuiItems.item(Material.GOLD_BLOCK, "&6&lIsland Upgrades",
                    "&cUnavailable right now.");
        }
        return GuiItems.item(Material.GOLD_BLOCK, "&6&lIsland Upgrades",
                "&7Grow your claim and unlock", "&7more member slots", "&8— paid with coins.");
    }

    private ItemStack buffNavItem(final Island island) {
        if (!config.enabled()) {
            return GuiItems.item(Material.BEACON, "&d&lIsland Buffs", "&cUnavailable right now.");
        }
        long active = 0;
        for (final IslandUpgradeConfig.BuffDef def : config.buffs()) {
            if (buffs.isActive(island, def.id())) {
                active++;
            }
        }
        return GuiItems.item(Material.BEACON, "&d&lIsland Buffs",
                "&7Timed boosts for your island:", "&7crops, spawners and XP.",
                active > 0 ? "&a" + active + " buff" + (active == 1 ? "" : "s") + " active!"
                           : "&8None active yet.");
    }

    // ------------------------------------------------------------------
    // sub-menus (small chests)
    // ------------------------------------------------------------------

    /** Opens the upgrades menu. */
    public void openUpgrades(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.UPGRADES);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.SUB_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &bUpgrades"));
        menu.inventory(inventory);

        if (config.enabled()) {
            inventory.setItem(IslandLayout.UPGRADE_CLAIM, claimItem(island));
            inventory.setItem(IslandLayout.UPGRADE_SLOTS, slotsItem(island));
        } else {
            inventory.setItem(13, GuiItems.item(Material.BARRIER,
                    "&cUpgrades unavailable", "&7The upgrades config failed to load.",
                    "&7See the server log."));
        }
        inventory.setItem(IslandLayout.SUB_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.SUB_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack claimItem(final Island island) {
        final IslandUpgradeConfig.ClaimSizeDef def = config.claimSize();
        final int level = island.upgradeLevel("claim-size");
        final List<String> lore = new ArrayList<>();
        lore.add("&7Claim: &f" + island.borderSize() + " x " + island.borderSize());
        if (level >= def.maxLevel()) {
            lore.add("&aFully expanded!");
        } else {
            final int next = island.borderSize() + def.growthPerLevel();
            lore.add("&7Next: &f" + next + " x " + next);
            lore.add("&7Cost: &e" + Money.format(config.claimSizePrice(level + 1), "$"));
            lore.add("&eClick to upgrade");
        }
        lore.add("&7Level: &f" + level + "&7/&f" + def.maxLevel());
        return GuiItems.item(def.icon(), "&6&l" + def.name(), lore.toArray(new String[0]));
    }

    private ItemStack slotsItem(final Island island) {
        final IslandUpgradeConfig.MemberSlotsDef def = config.memberSlots();
        final int level = island.upgradeLevel("member-slots");
        final int limit = config.memberLimit(level);
        final List<String> lore = new ArrayList<>();
        lore.add("&7Member slots: &f" + limit);
        if (level >= def.maxLevel()) {
            lore.add("&aEvery slot unlocked!");
        } else {
            lore.add("&7Next: &f" + config.memberLimit(level + 1) + " &7slots");
            lore.add("&7Cost: &e" + Money.format(config.memberSlotsPrice(level + 1), "$"));
            lore.add("&eClick to upgrade");
        }
        lore.add("&7Currently: &f" + (island.members().size() + 1) + "&7/&f" + limit);
        return GuiItems.item(def.icon(), "&6&l" + def.name(), lore.toArray(new String[0]));
    }

    /** Opens the buffs menu. */
    public void openBuffs(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.BUFFS);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.SUB_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &bBuffs"));
        menu.inventory(inventory);

        if (config.enabled()) {
            final List<IslandUpgradeConfig.BuffDef> defs = config.buffs();
            for (int i = 0; i < defs.size() && i < 3; i++) {
                inventory.setItem(IslandLayout.buffSlot(i), buffItem(island, defs.get(i)));
            }
        } else {
            inventory.setItem(13, GuiItems.item(Material.BARRIER,
                    "&cBuffs unavailable", "&7The upgrades config failed to load.",
                    "&7See the server log."));
        }
        inventory.setItem(IslandLayout.SUB_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.SUB_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack buffItem(final Island island, final IslandUpgradeConfig.BuffDef def) {
        final List<String> lore = new ArrayList<>();
        lore.add(buffEffect(def));
        if (buffs.isActive(island, def.id())) {
            lore.add("&aActive for &f" + Math.max(1, buffs.remainingMinutes(island, def.id()))
                    + " &aminute(s) more");
            lore.add("&eClick to restart the timer");
        } else {
            lore.add("&8Inactive");
        }
        lore.add("&7Cost: &e" + Money.format(def.price(), "$"));
        return GuiItems.item(def.icon(), "&d&l" + def.name(), lore.toArray(new String[0]));
    }

    private String buffEffect(final IslandUpgradeConfig.BuffDef def) {
        return switch (def.id()) {
            case "crop-growth" -> "&7Crops grow &fx" + trim(def.multiplier()) + " &7faster";
            case "spawner-boost" -> "&7Spawners run &fx" + trim(def.multiplier()) + " &7faster";
            case "xp-boost" -> "&7Mob kills drop &fx" + trim(def.multiplier()) + " &7XP";
            default -> "&7Boost: &fx" + trim(def.multiplier());
        };
    }

    private static String trim(final double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Opens the members menu. */
    public void openMembers(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.MEMBERS);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.SUB_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &bMembers"));
        menu.inventory(inventory);

        inventory.setItem(IslandLayout.MEMBERS_OWNER,
                GuiItems.head(Bukkit.getOfflinePlayer(island.owner()),
                        "&6&lOwner: " + island.ownerName(), "&7Island owner."));
        int slot = 0;
        for (final UUID memberId : island.members()) {
            if (slot >= IslandLayout.MEMBERS_MAX) {
                break;
            }
            final String name = Bukkit.getOfflinePlayer(memberId).getName();
            inventory.setItem(IslandLayout.memberSlot(slot), GuiItems.head(
                    Bukkit.getOfflinePlayer(memberId), "&f" + (name == null ? "member" : name),
                    "&7Member.", island.isOwner(player.getUniqueId())
                            ? "&cClick twice to kick" : "&8Only the owner can kick"));
            slot++;
        }
        inventory.setItem(IslandLayout.SUB_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.SUB_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    /** Opens the invite menu: online island-less players, excluding the viewer. */
    public void openInvite(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.INVITE);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.SUB_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &bInvite"));
        menu.inventory(inventory);

        int slot = 0;
        for (final Player candidate : Bukkit.getOnlinePlayers()) {
            if (slot >= IslandLayout.INVITE_MAX) {
                break;
            }
            if (candidate.getUniqueId().equals(player.getUniqueId())
                    || islands.islandOf(candidate.getUniqueId()) != null) {
                continue;
            }
            inventory.setItem(IslandLayout.inviteSlot(slot), GuiItems.head(candidate,
                    "&f" + candidate.getName(), "&7Online and island-less.",
                    "&eClick to invite"));
            slot++;
        }
        if (slot == 0) {
            inventory.setItem(13, GuiItems.item(Material.BOOK,
                    "&7No one to invite", "&7Nobody online is without", "&7an island right now."));
        }
        inventory.setItem(IslandLayout.SUB_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.SUB_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    /** Opens the spawner menu (the island menu's cross-link). */
    public void openSpawnerMenu(final Player player) {
        if (spawnerMenu != null) {
            spawnerMenu.open(player);
        }
    }
}
