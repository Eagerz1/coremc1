package com.coremc.core.island;

import com.coremc.core.gens.GensMenuGui;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.spawner.SpawnerMenuGui;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
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
 *
 * <p>Every item speaks the CoreMC GUI design language: coloured
 * names, short small-caps lore ({@link GuiText}), blank separator
 * lines, a yellow click action, consistent back/close buttons and a
 * live ✔ / ✖ indicator on everything that costs coins.</p>
 */
public final class IslandGui {

    private static final String MENU_TITLE = "&3&lCOREMC &8— &bIsland";

    private final IslandService islands;
    private final IslandUpgradeConfig config;
    private final IslandBuffService buffs;
    private final SpawnerMenuGui spawnerMenu;
    private final GensMenuGui gensMenu;
    private final EconomyService economy;

    public IslandGui(final IslandService islands, final IslandUpgradeConfig config,
                     final IslandBuffService buffs, final SpawnerMenuGui spawnerMenu,
                     final GensMenuGui gensMenu, final EconomyService economy) {
        this.islands = islands;
        this.config = config;
        this.buffs = buffs;
        this.spawnerMenu = spawnerMenu;
        this.gensMenu = gensMenu;
        this.economy = economy;
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
                "&a&l" + GuiText.caps("Go Home"),
                "&7" + GuiText.caps("Teleport to your island."),
                GuiText.blank(),
                GuiText.click("Click to travel")));
        inventory.setItem(IslandLayout.MENU_INVITE, GuiItems.item(Material.WRITABLE_BOOK,
                "&b&l" + GuiText.caps("Invite Players"),
                "&7" + GuiText.caps("Bring a friend to your island."),
                GuiText.blank(),
                GuiText.click("Click to open")));
        inventory.setItem(IslandLayout.MENU_MEMBERS, membersNavItem(island));
        inventory.setItem(IslandLayout.MENU_BORDER, GuiItems.item(Material.SPYGLASS,
                "&d&l" + GuiText.caps("Toggle Border"),
                "&7" + GuiText.caps("Show or hide your claim border."),
                GuiText.blank(),
                GuiText.click("Click to toggle")));
        inventory.setItem(IslandLayout.MENU_UPGRADES, upgradeNavItem(island));
        inventory.setItem(IslandLayout.MENU_BUFFS, buffNavItem(island));
        inventory.setItem(IslandLayout.MENU_SPAWNERS, spawnerNavItem());
        inventory.setItem(IslandLayout.MENU_GENERATORS, generatorNavItem());
        inventory.setItem(IslandLayout.MENU_DELETE, GuiItems.item(Material.TNT,
                "&c&l" + GuiText.caps("Delete Island"),
                "&7" + GuiText.caps("Deletes your island and"),
                "&7" + GuiText.caps("evicts every member."),
                GuiText.blank(),
                "&c" + GuiText.caps("Click twice to confirm")));
        inventory.setItem(IslandLayout.MENU_CLOSE, GuiItems.close());
        GuiItems.frame(inventory, IslandLayout.menuFrame());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack infoItem(final Island island) {
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Owner", "&f", island.ownerName()));
        final int limit = config.enabled()
                ? config.memberLimit(island.upgradeLevel("member-slots")) : 0;
        lore.add(GuiText.value("Members", "&f", limit > 0
                ? GuiText.progress(island.members().size() + 1, limit)
                : String.valueOf(island.members().size() + 1)));
        lore.add(GuiText.value("Claim", "&f",
                island.borderSize() + " x " + island.borderSize()));
        lore.add(GuiText.value("Slot", "&f", "#" + island.slot()));
        return GuiItems.head(Bukkit.getOfflinePlayer(island.owner()),
                "&b&l" + GuiText.caps(island.ownerName() + "'s Island"), lore);
    }

    private ItemStack membersNavItem(final Island island) {
        return GuiItems.item(Material.CHEST, "&e&l" + GuiText.caps("Members"),
                "&7" + GuiText.caps("Everyone on your island."),
                GuiText.blank(),
                GuiText.value("On the island", "&f", String.valueOf(island.members().size() + 1)),
                GuiText.blank(),
                GuiText.click("Click to view"));
    }

    private ItemStack upgradeNavItem(final Island island) {
        if (!config.enabled()) {
            return unavailableItem(Material.GOLD_BLOCK, "Island Upgrades");
        }
        final int claim = island.upgradeLevel("claim-size");
        final int slots = island.upgradeLevel("member-slots");
        return GuiItems.item(Material.GOLD_BLOCK, "&6&l" + GuiText.caps("Island Upgrades"),
                "&7" + GuiText.caps("Grow your claim and unlock"),
                "&7" + GuiText.caps("more member slots."),
                GuiText.blank(),
                GuiText.value("Expansion", "&f",
                        GuiText.progress(claim, config.claimSize().maxLevel())),
                GuiText.value("Member slots", "&f",
                        GuiText.progress(slots, config.memberSlots().maxLevel())),
                GuiText.blank(),
                GuiText.click("Click to open"));
    }

    private ItemStack buffNavItem(final Island island) {
        if (!config.enabled()) {
            return unavailableItem(Material.BEACON, "Island Buffs");
        }
        int active = 0;
        for (final IslandUpgradeConfig.BuffDef def : config.buffs()) {
            if (buffs.isActive(island, def.id())) {
                active++;
            }
        }
        return GuiItems.item(Material.BEACON, "&d&l" + GuiText.caps("Island Buffs"),
                "&7" + GuiText.caps("Timed boosts: crops,"),
                "&7" + GuiText.caps("spawners and XP."),
                GuiText.blank(),
                active > 0
                        ? GuiText.value("Active", "&a", String.valueOf(active))
                        : GuiText.value("Active", "&8", "none"),
                GuiText.blank(),
                GuiText.click("Click to open"));
    }

    private ItemStack spawnerNavItem() {
        if (spawnerMenu == null) {
            return unavailableItem(Material.SPAWNER, "Spawner Progression");
        }
        return GuiItems.item(Material.SPAWNER, "&5&l" + GuiText.caps("Spawner Progression"),
                "&7" + GuiText.caps("Every mob spawner, its"),
                "&7" + GuiText.caps("variants and island luck."),
                GuiText.blank(),
                GuiText.click("Click to open"));
    }

    private ItemStack generatorNavItem() {
        if (gensMenu == null) {
            return unavailableItem(Material.IRON_BLOCK, "Generators");
        }
        return GuiItems.item(Material.IRON_BLOCK, "&b&l" + GuiText.caps("Generators"),
                "&7" + GuiText.caps("Buy, place, stack and"),
                "&7" + GuiText.caps("upgrade your generators."),
                GuiText.blank(),
                GuiText.click("Click to open"));
    }

    /** A system that failed to load never looks like a working button. */
    private ItemStack unavailableItem(final Material material, final String name) {
        return GuiItems.item(Material.GRAY_STAINED_GLASS_PANE, "&8&l" + GuiText.caps(name),
                "&c" + GuiText.caps("Unavailable right now."),
                "&8" + GuiText.caps("See the server log."));
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
            inventory.setItem(IslandLayout.UPGRADE_CLAIM, claimItem(player, island));
            inventory.setItem(IslandLayout.UPGRADE_SLOTS, slotsItem(player, island));
        } else {
            inventory.setItem(13, unavailableItem(Material.BARRIER, "Upgrades unavailable"));
        }
        finishSubMenu(player, inventory);
    }

    private ItemStack claimItem(final Player player, final Island island) {
        final IslandUpgradeConfig.ClaimSizeDef def = config.claimSize();
        final int level = island.upgradeLevel("claim-size");
        final boolean maxed = level >= def.maxLevel();
        final double price = maxed ? 0 : config.claimSizePrice(level + 1);
        final boolean affordable = canPay(player, price);
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Grows your protected claim."));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Level", "&f", GuiText.progress(level, def.maxLevel())));
        lore.add(GuiText.value("Current", "&f",
                island.borderSize() + " x " + island.borderSize()));
        if (maxed) {
            lore.add(GuiText.blank());
            lore.add("&a" + GuiText.caps("Fully upgraded"));
        } else {
            final int next = island.borderSize() + def.growthPerLevel();
            lore.add(GuiText.value("Next", "&a", next + " x " + next));
            lore.add(GuiText.cost("Cost", GuiText.money(price), affordable));
            lore.add(GuiText.blank());
            lore.add(affordable ? GuiText.click("Click to upgrade")
                    : "&c" + GuiText.caps("You cannot afford this yet"));
        }
        return GuiItems.item(def.icon(), "&6&l" + GuiText.caps(def.name()), lore);
    }

    private ItemStack slotsItem(final Player player, final Island island) {
        final IslandUpgradeConfig.MemberSlotsDef def = config.memberSlots();
        final int level = island.upgradeLevel("member-slots");
        final int limit = config.memberLimit(level);
        final boolean maxed = level >= def.maxLevel();
        final double price = maxed ? 0 : config.memberSlotsPrice(level + 1);
        final boolean affordable = canPay(player, price);
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Room for more island-mates."));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Level", "&f", GuiText.progress(level, def.maxLevel())));
        lore.add(GuiText.value("Current", "&f",
                GuiText.progress(island.members().size() + 1, limit)));
        if (maxed) {
            lore.add(GuiText.blank());
            lore.add("&a" + GuiText.caps("Every slot unlocked"));
        } else {
            lore.add(GuiText.value("Next", "&a",
                    config.memberLimit(level + 1) + " " + GuiText.caps("slots")));
            lore.add(GuiText.cost("Cost", GuiText.money(price), affordable));
            lore.add(GuiText.blank());
            lore.add(affordable ? GuiText.click("Click to upgrade")
                    : "&c" + GuiText.caps("You cannot afford this yet"));
        }
        return GuiItems.item(def.icon(), "&6&l" + GuiText.caps(def.name()), lore);
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
                inventory.setItem(IslandLayout.buffSlot(i), buffItem(player, island, defs.get(i)));
            }
        } else {
            inventory.setItem(13, unavailableItem(Material.BARRIER, "Buffs unavailable"));
        }
        finishSubMenu(player, inventory);
    }

    private ItemStack buffItem(final Player player, final Island island,
                               final IslandUpgradeConfig.BuffDef def) {
        final boolean active = buffs.isActive(island, def.id());
        final boolean affordable = canPay(player, def.price());
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps(buffEffect(def)));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Duration", "&f", def.durationMinutes() + "m"));
        lore.add(active
                ? GuiText.value("Status", "&a",
                        GuiText.caps("active") + " &8(" + Math.max(1,
                                buffs.remainingMinutes(island, def.id())) + "m)")
                : GuiText.value("Status", "&8", "inactive"));
        lore.add(GuiText.cost("Cost", GuiText.money(def.price()), affordable));
        lore.add(GuiText.blank());
        lore.add(affordable
                ? GuiText.click(active ? "Click to restart the timer" : "Click to activate")
                : "&c" + GuiText.caps("You cannot afford this yet"));
        final ItemStack item = GuiItems.item(def.icon(), "&d&l" + GuiText.caps(def.name()), lore);
        return active ? GuiItems.glow(item) : item;
    }

    private String buffEffect(final IslandUpgradeConfig.BuffDef def) {
        return switch (def.id()) {
            case "crop-growth" -> "Crops grow x" + trim(def.multiplier()) + " faster.";
            case "spawner-boost" -> "Spawners run x" + trim(def.multiplier()) + " faster.";
            case "xp-boost" -> "Mob kills drop x" + trim(def.multiplier()) + " XP.";
            default -> "Boost: x" + trim(def.multiplier());
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
                        "&6&l" + GuiText.caps(island.ownerName()),
                        "&7" + GuiText.caps("Island owner.")));
        int slot = 0;
        for (final UUID memberId : island.members()) {
            if (slot >= IslandLayout.MEMBERS_MAX) {
                break;
            }
            final String name = Bukkit.getOfflinePlayer(memberId).getName();
            inventory.setItem(IslandLayout.memberSlot(slot), GuiItems.head(
                    Bukkit.getOfflinePlayer(memberId),
                    "&f&l" + GuiText.caps(name == null ? "member" : name),
                    "&7" + GuiText.caps("Island member."),
                    GuiText.blank(),
                    island.isOwner(player.getUniqueId())
                            ? "&c" + GuiText.caps("Click twice to kick")
                            : "&8" + GuiText.caps("Only the owner can kick")));
            slot++;
        }
        finishSubMenu(player, inventory);
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
                    "&f&l" + GuiText.caps(candidate.getName()),
                    "&7" + GuiText.caps("Online and island-less."),
                    GuiText.blank(),
                    GuiText.click("Click to invite")));
            slot++;
        }
        if (slot == 0) {
            inventory.setItem(13, GuiItems.item(Material.BOOK,
                    "&8&l" + GuiText.caps("No one to invite"),
                    "&7" + GuiText.caps("Nobody online is without"),
                    "&7" + GuiText.caps("an island right now.")));
        }
        finishSubMenu(player, inventory);
    }

    /** Back / close buttons, frame and filler — identical in every sub-menu. */
    private void finishSubMenu(final Player player, final Inventory inventory) {
        inventory.setItem(IslandLayout.SUB_BACK, GuiItems.back("island menu"));
        inventory.setItem(IslandLayout.SUB_CLOSE, GuiItems.close());
        GuiItems.frame(inventory, IslandLayout.subFrame());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    private boolean canPay(final Player player, final double price) {
        return economy != null && economy.has(player.getUniqueId(), price);
    }

    /** Opens the spawner menu (the island menu's cross-link). */
    public void openSpawnerMenu(final Player player) {
        if (spawnerMenu != null) {
            spawnerMenu.open(player);
        }
    }

    /** Opens the generator menu (the island menu's cross-link). */
    public void openGensMenu(final Player player) {
        if (gensMenu != null) {
            gensMenu.open(player);
        }
    }
}
