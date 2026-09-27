package com.coremc.core.island;

import com.coremc.core.progression.IslandCoreBuffConfig;
import com.coremc.core.progression.IslandCoreBuffService;
import com.coremc.core.progression.IslandProgressionConfig;
import com.coremc.core.progression.IslandProgressionService;
import com.coremc.core.shop.EconomyService;
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
 * Renders the island menu GUIs: the double-chest island menu, the
 * double-chest Island Mastery views and compact sub-menus (buffs, core,
 * members, invite). All state
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
    private final IslandProgressionService progression;
    private final IslandCoreBuffService coreBuffs;
    private final EconomyService economy;

    public IslandGui(final IslandService islands, final IslandUpgradeConfig config,
                     final IslandBuffService buffs, final SpawnerMenuGui spawnerMenu,
                     final IslandProgressionService progression, final IslandCoreBuffService coreBuffs,
                     final EconomyService economy) {
        this.islands = islands;
        this.config = config;
        this.buffs = buffs;
        this.spawnerMenu = spawnerMenu;
        this.progression = progression;
        this.coreBuffs = coreBuffs;
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
        if (progression != null && progression.config().enabled()) {
            lore.add("&7Island Level: &b" + progression.level(island));
            lore.add("&7Mastery Points: &e" + progression.availableMasteryPoints(island)
                    + "&7/&e" + progression.totalMasteryPoints(island));
        }
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
        if (progression == null || !progression.config().enabled()) {
            return GuiItems.item(Material.GOLD_BLOCK, "&6&lIsland Mastery",
                    "&cUnavailable right now.");
        }
        return GuiItems.item(Material.GOLD_BLOCK, "&6&lIsland Mastery",
                "&7ʟᴇᴠᴇʟ, ᴍᴀsᴛᴇʀʏ ᴀɴᴅ ᴄᴏʀᴇ.",
                "&7sᴘᴇᴄɪᴀʟɪsᴇ ʏᴏᴜʀ ɪsʟᴀɴᴅ.",
                "&eClick to open");
    }

    private ItemStack buffNavItem(final Island island) {
        if (coreBuffs == null || !coreBuffs.config().enabled()) {
            return GuiItems.item(Material.BEACON, "&d&lCore Buffs", "&cUnavailable right now.");
        }
        return GuiItems.item(Material.BEACON, "&d&lCore Buffs",
                "&7ᴘᴇʀsɪsᴛᴇɴᴛ ɪsʟᴀɴᴅ ʙᴜɪʟᴅ ᴄʜᴏɪᴄᴇs.",
                "&7ᴇǫᴜɪᴘᴘᴇᴅ: &d" + progression.profile(island).activeModules().size()
                        + "&7/&d" + progression.moduleSlots(island),
                "&eClick to manage");
    }

    // ------------------------------------------------------------------
    // sub-menus (small chests)
    // ------------------------------------------------------------------

    /** Opens the Island Mastery root menu (used by /is upgrades). */
    public void openUpgrades(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.UPGRADES);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.MASTERY_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &bMastery"));
        menu.inventory(inventory);

        if (progression == null || !progression.config().enabled()) {
            inventory.setItem(22, GuiItems.item(Material.BARRIER,
                    "&cMastery unavailable", "&7ᴘʀᴏɢʀᴇssɪᴏɴ.ʏᴍʟ ᴅɪᴅ ɴᴏᴛ ʟᴏᴀᴅ.",
                    "&7sᴇᴇ ᴛʜᴇ sᴇʀᴠᴇʀ ʟᴏɢ."));
        } else {
            inventory.setItem(IslandLayout.MASTERY_INFO, masteryInfoItem(island));
            inventory.setItem(IslandLayout.MASTERY_TOKENS, skyTokenItem(island));
            for (final IslandProgressionConfig.MasteryBranch branch : progression.config().branches()) {
                final int slot = branchRootSlot(branch.id());
                if (slot >= 0) {
                    inventory.setItem(slot, branchItem(island, branch));
                }
            }
            inventory.setItem(IslandLayout.MASTERY_CORE, coreItem(island));
            inventory.setItem(IslandLayout.MASTERY_MODULES, activeModulesItem(island));
        }
        inventory.setItem(IslandLayout.MASTERY_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.MASTERY_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack masteryInfoItem(final Island island) {
        final int level = progression.level(island);
        final long xp = progression.xp(island);
        final IslandProgressionConfig.LevelDef next = progression.config().nextLevel(level);
        final List<String> lore = new ArrayList<>();
        lore.add("&7ɪsʟᴀɴᴅ ʟᴇᴠᴇʟ: &b" + level + "&7/&b" + progression.config().maxLevel());
        if (next == null) {
            lore.add("&7xᴘ: &a" + formatLong(xp) + " &8(max)");
        } else {
            final long currentFloor = progression.config().xpForLevel(level);
            final long have = Math.max(0L, xp - currentFloor);
            final long need = Math.max(1L, next.xp() - currentFloor);
            lore.add("&7xᴘ: &f" + formatLong(have) + "&7/&f" + formatLong(need));
            lore.add("&7ɴᴇxᴛ: &fʟᴇᴠᴇʟ " + next.level() + " &8— &7" + next.milestone());
        }
        lore.add(" ");
        lore.add("&7ᴍᴀsᴛᴇʀʏ ᴘᴏɪɴᴛs: &e" + progression.availableMasteryPoints(island)
                + "&7/&e" + progression.totalMasteryPoints(island));
        lore.add("&7ᴄᴏʀᴇ sʟᴏᴛs: &d" + progression.moduleSlots(island));
        return GuiItems.item(Material.NETHER_STAR, "&b&lIsland Level", lore.toArray(new String[0]));
    }

    private ItemStack skyTokenItem(final Island island) {
        return GuiItems.item(Material.SUNFLOWER, "&e&lSky Tokens",
                "&7ʙᴀʟᴀɴᴄᴇ: &e" + formatLong(progression.skyTokens(island)),
                "&7ᴇᴀʀɴᴇᴅ ꜰʀᴏᴍ ɪsʟᴀɴᴅ ʟᴇᴠᴇʟs.",
                "&7sᴘᴇɴᴅ ᴛʜᴇᴍ ᴏɴ ᴍᴀsᴛᴇʀʏ.");
    }

    private ItemStack branchItem(final Island island, final IslandProgressionConfig.MasteryBranch branch) {
        final int purchased = progression.purchasedInBranch(island, branch);
        return GuiItems.item(branch.icon(), branchColor(branch.id()) + "&l" + branch.name(),
                "&7ᴜɴʟᴏᴄᴋᴇᴅ: &f" + purchased + "&7/&f" + branch.upgrades().size(),
                "&7sᴘᴇᴄɪᴀʟɪsᴇ ᴡɪᴛʜ ɴᴇᴡ ᴍᴇᴄʜᴀɴɪᴄs.",
                "&eClick to view");
    }

    private ItemStack coreItem(final Island island) {
        final int slots = progression.moduleSlots(island);
        final List<String> lore = new ArrayList<>();
        lore.add("&7ᴀᴄᴛɪᴠᴇ sʟᴏᴛs: &d" + progression.profile(island).activeModules().size()
                + "&7/&d" + slots);
        lore.add("&7sʟᴏᴛ ᴜɴʟᴏᴄᴋs: &f" + progression.config().moduleSlotLevels());
        lore.add("&7ᴍᴏᴅᴜʟᴇs ᴄʜᴀɴɢᴇ ɪsʟᴀɴᴅ ʙᴇʜᴀᴠɪᴏᴜʀ.");
        lore.add("&eClick to view");
        return GuiItems.item(Material.BEACON, "&d&lIsland Core", lore.toArray(new String[0]));
    }

    private ItemStack activeModulesItem(final Island island) {
        final List<String> lore = new ArrayList<>();
        if (progression.profile(island).activeModules().isEmpty()) {
            lore.add("&8ɴᴏ ᴍᴏᴅᴜʟᴇs ᴀᴄᴛɪᴠᴇ.");
        } else {
            for (final String moduleId : progression.profile(island).activeModules()) {
                final IslandProgressionConfig.ModuleDef module = progression.config().module(moduleId);
                lore.add("&a✔ &f" + (module == null ? moduleId : module.name()));
            }
        }
        lore.add(" ");
        lore.add("&7ᴍᴏᴅᴜʟᴇ ᴇᴀʀɴɪɴɢ ʜᴏᴏᴋs ᴀʀᴇ ɴᴇxᴛ.");
        lore.add("&eClick to view core");
        return GuiItems.item(Material.AMETHYST_CLUSTER, "&d&lActive Modules", lore.toArray(new String[0]));
    }

    /** Opens a single Island Mastery branch. */
    public void openMasteryBranch(final Player player, final String branchId) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null || progression == null) {
            return;
        }
        final IslandProgressionConfig.MasteryBranch branch = progression.config().branch(branchId);
        if (branch == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.MASTERY_BRANCH, branch.id());
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.MASTERY_SIZE,
                ColorUtil.colorize("&3&lMastery &8— " + branchColor(branch.id()) + branch.name()));
        menu.inventory(inventory);

        inventory.setItem(4, GuiItems.item(branch.icon(), branchColor(branch.id()) + "&l" + branch.name(),
                "&7ᴍᴀsᴛᴇʀʏ ᴘᴏɪɴᴛs: &e" + progression.availableMasteryPoints(island)
                        + "&7/&e" + progression.totalMasteryPoints(island),
                "&7ɪsʟᴀɴᴅ ʟᴇᴠᴇʟ: &b" + progression.level(island)));
        for (int i = 0; i < branch.upgrades().size() && i < IslandLayout.masteryUpgradeCapacity(); i++) {
            inventory.setItem(IslandLayout.masteryUpgradeSlot(i), masteryUpgradeItem(player, island,
                    branch.upgrades().get(i)));
        }
        inventory.setItem(IslandLayout.MASTERY_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.MASTERY_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    private ItemStack masteryUpgradeItem(final Player player, final Island island,
                                         final IslandProgressionConfig.MasteryUpgrade upgrade) {
        final List<String> lore = new ArrayList<>();
        lore.addAll(upgrade.lore());
        lore.add(" ");
        if (progression.hasUpgrade(island, upgrade)) {
            lore.add("&a✔ ᴜɴʟᴏᴄᴋᴇᴅ");
            return GuiItems.item(upgrade.icon(), "&a&l" + upgrade.name(), lore.toArray(new String[0]));
        }
        final int islandLevel = progression.level(island);
        lore.add(requirementLine("ɪsʟᴀɴᴅ ʟᴇᴠᴇʟ", islandLevel, upgrade.islandLevel()));
        lore.add(requirementLine("ᴍᴀsᴛᴇʀʏ ᴘᴏɪɴᴛs", progression.availableMasteryPoints(island),
                upgrade.masteryPoints()));
        if (upgrade.money() > 0.0D) {
            final boolean hasMoney = economy != null && economy.has(player.getUniqueId(), upgrade.money());
            lore.add(priceLine(Money.format(upgrade.money(), "$"), hasMoney));
        }
        if (upgrade.skyTokens() > 0L) {
            lore.add(requirementLine("sᴋʏ ᴛᴏᴋᴇɴs", progression.skyTokens(island), upgrade.skyTokens()));
        }
        for (final String required : upgrade.requires()) {
            final String[] parts = required.split("\\.", 2);
            final boolean met = parts.length == 2
                    && progression.profile(island).masteryLevel(parts[0], parts[1]) > 0;
            lore.add("&7ʀᴇǫᴜɪʀᴇs: " + (met ? "&a" : "&c")
                    + readableRequirement(required) + " " + tick(met));
        }
        lore.add(" ");
        final boolean canBuy = progression.missingRequirements(player, island, upgrade).isEmpty();
        lore.add(canBuy ? "&eClick to unlock" : "&8Locked");
        return GuiItems.item(upgrade.icon(), (canBuy ? "&e&l" : "&c&l") + upgrade.name(),
                lore.toArray(new String[0]));
    }

    /** Opens persistent Island Core buffs. */
    public void openCoreBuffs(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.CORE_BUFFS);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.MASTERY_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &dCore Buffs"));
        menu.inventory(inventory);
        if (coreBuffs == null || !coreBuffs.config().enabled()) {
            inventory.setItem(22, GuiItems.item(Material.BARRIER,
                    "&cCore Buffs unavailable", "&7ɪsʟᴀɴᴅ-ʙᴜꜰꜰs.ʏᴍʟ ᴅɪᴅ ɴᴏᴛ ʟᴏᴀᴅ."));
        } else {
            inventory.setItem(4, GuiItems.item(Material.BEACON, "&d&lCore Buff Slots",
                    "&7sʟᴏᴛs: &d" + progression.profile(island).activeModules().size()
                            + "&7/&d" + progression.moduleSlots(island),
                    "&7sᴡᴀᴘ ᴄᴏᴏʟᴅᴏᴡɴ: &f" + formatMillis(coreBuffs.swapCooldownRemainingMillis(island)),
                    "&7ᴍᴏᴍᴇɴᴛᴜᴍ: &b" + Math.round(coreBuffs.momentumProgress(island))
                            + "&7/&b" + Math.round(coreBuffs.config().momentumMax())));
            final List<IslandCoreBuffConfig.BuffDef> defs = coreBuffs.config().buffs();
            for (int i = 0; i < defs.size() && i < IslandLayout.coreBuffCapacity(); i++) {
                inventory.setItem(IslandLayout.coreBuffSlot(i), coreBuffItem(player, island, defs.get(i)));
            }
        }
        inventory.setItem(IslandLayout.MASTERY_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.MASTERY_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    private ItemStack coreBuffItem(final Player player, final Island island,
                                   final IslandCoreBuffConfig.BuffDef buff) {
        final List<String> lore = new ArrayList<>(buff.lore());
        lore.add(" ");
        final boolean unlocked = coreBuffs.isUnlocked(island, buff.id());
        final boolean equipped = coreBuffs.isEquipped(island, buff.id());
        if (equipped) {
            lore.add("&7sᴛᴀᴛᴜs: &aᴇǫᴜɪᴘᴘᴇᴅ ✔");
        } else if (unlocked) {
            lore.add("&7sᴛᴀᴛᴜs: &eᴜɴʟᴏᴄᴋᴇᴅ");
            lore.add("&eClick to equip");
        } else {
            lore.add(requirementLine("ɪsʟᴀɴᴅ ʟᴠʟ", progression.level(island), buff.islandLevel()));
            if (buff.money() > 0.0D) {
                lore.add(priceLine(Money.format(buff.money(), "$"),
                        economy != null && economy.has(player.getUniqueId(), buff.money())));
            }
            if (buff.skyTokens() > 0L) {
                lore.add(requirementLine("sᴋʏ ᴛᴏᴋᴇɴs", progression.skyTokens(island), buff.skyTokens()));
            }
            for (final String req : buff.masteryRequires()) {
                final String[] parts = req.split("\\.", 2);
                final boolean met = parts.length == 2
                        && progression.profile(island).masteryLevel(parts[0], parts[1]) > 0;
                lore.add("&7ʀᴇǫᴜɪʀᴇs: " + (met ? "&a" : "&c") + readableRequirement(req)
                        + " " + tick(met));
            }
            lore.add(coreBuffs.canUnlock(player, island, buff) ? "&eClick to unlock" : "&8Locked");
        }
        if ("fortune-cycle".equals(buff.id())) {
            lore.add("&7ꜰᴏᴄᴜs: &f" + (coreBuffs.fortuneFocus(island).isBlank()
                    ? "none" : coreBuffs.fortuneFocus(island)));
            lore.add("&8Right-click support is handled by integrations.");
        }
        return GuiItems.item(buff.icon(), (equipped ? "&a&l" : unlocked ? "&e&l" : "&c&l")
                + buff.name(), lore.toArray(new String[0]));
    }

    /** Opens the Island Core overview. Module activation comes after module earning. */
    public void openCore(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null || progression == null) {
            return;
        }
        final IslandMenu menu = new IslandMenu(IslandMenu.Kind.CORE);
        final Inventory inventory = Bukkit.createInventory(menu, IslandLayout.SUB_SIZE,
                ColorUtil.colorize("&3&lIsland &8— &dCore"));
        menu.inventory(inventory);
        inventory.setItem(4, coreItem(island));
        final int slots = progression.moduleSlots(island);
        for (int i = 0; i < 3; i++) {
            final boolean unlocked = i < slots;
            final int unlockLevel = i < progression.config().moduleSlotLevels().size()
                    ? progression.config().moduleSlotLevels().get(i) : 0;
            inventory.setItem(10 + i * 2, GuiItems.item(unlocked ? Material.LIME_STAINED_GLASS_PANE
                            : Material.RED_STAINED_GLASS_PANE,
                    unlocked ? "&a&lModule Slot " + (i + 1) : "&c&lModule Slot " + (i + 1),
                    unlocked ? "&7ᴜɴʟᴏᴄᴋᴇᴅ." : "&7ᴜɴʟᴏᴄᴋs ᴀᴛ ɪsʟᴀɴᴅ ʟᴇᴠᴇʟ &f"
                            + (unlockLevel <= 0 ? "?" : unlockLevel) + "&7."));
        }
        inventory.setItem(16, GuiItems.item(Material.BOOK,
                "&d&lModule Catalogue", "&7ᴍᴏᴅᴜʟᴇs ᴀʀᴇ ᴇᴀʀɴᴇᴅ ʙʏ ɢᴀᴍᴇᴘʟᴀʏ.",
                "&7ᴍɪɴɪɴɢ, ꜰɪsʜɪɴɢ, sʟᴀʏᴇʀ ᴀɴᴅ ᴇᴠᴇɴᴛs."));
        inventory.setItem(IslandLayout.SUB_BACK, GuiItems.back());
        inventory.setItem(IslandLayout.SUB_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    private int branchRootSlot(final String branchId) {
        return switch (branchId) {
            case "farming" -> IslandLayout.MASTERY_FARMING;
            case "mining" -> IslandLayout.MASTERY_MINING;
            case "fishing" -> IslandLayout.MASTERY_FISHING;
            case "slayer" -> IslandLayout.MASTERY_SLAYER;
            case "industry" -> IslandLayout.MASTERY_INDUSTRY;
            default -> -1;
        };
    }

    private String branchColor(final String branchId) {
        return switch (branchId) {
            case "farming" -> "&a";
            case "mining" -> "&b";
            case "fishing" -> "&3";
            case "slayer" -> "&c";
            case "industry" -> "&6";
            default -> "&e";
        };
    }

    private String requirementLine(final String label, final long have, final long need) {
        final boolean met = have >= need;
        return "&7" + label + ": " + (met ? "&a" : "&c") + formatLong(have)
                + "/" + formatLong(need) + " " + tick(met);
    }

    private String priceLine(final String price, final boolean affordable) {
        return "&7ᴘʀɪᴄᴇ: " + (affordable ? "&a" : "&c") + price + " " + tick(affordable);
    }

    private String tick(final boolean yes) {
        return yes ? "✔" : "✖";
    }

    private String readableRequirement(final String raw) {
        final String[] parts = raw.replace('.', ' ').replace('-', ' ').split(" ");
        final StringBuilder builder = new StringBuilder();
        for (final String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String formatLong(final long value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }

    private String formatMillis(final long millis) {
        if (millis <= 0L) {
            return "Ready";
        }
        final long seconds = millis / 1000L;
        final long hours = seconds / 3600L;
        final long minutes = (seconds % 3600L) / 60L;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
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
                inventory.setItem(IslandLayout.buffSlot(i), buffItem(player, island, defs.get(i)));
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

    private ItemStack buffItem(final Player player, final Island island, final IslandUpgradeConfig.BuffDef def) {
        final List<String> lore = new ArrayList<>();
        lore.add(buffEffect(def));
        if (buffs.isActive(island, def.id())) {
            lore.add("&aActive for &f" + Math.max(1, buffs.remainingMinutes(island, def.id()))
                    + " &aminute(s) more");
            lore.add("&eClick to restart the timer");
        } else {
            lore.add("&8Inactive");
        }
        lore.add(priceLine(Money.format(def.price(), "$"),
                economy != null && economy.has(player.getUniqueId(), def.price())));
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
