package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.OmniUpgradeCatalog;
import com.coremc.core.role.Role;
import com.coremc.core.role.RoleSelectGui;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The OmniTool main menu for one enchant track (the player's role, or universal): double chest
 * (54) with explicit slot constants. The 15 track enchants sit on a
 * centred 5-column grid; left-click buys the next level, right-click
 * shows the full stat block in chat.
 *
 * Role/tool progress and all three OmniTool upgrades live here so shift-right-click
 * never detours through a second overview menu. Only the selected role and the
 * universal track can be viewed; changing role is an explicit separate action.
 */
public final class EnchantGui implements Gui {

    // ---------------- layout constants ----------------
    private static final int SLOT_HEADER = 4;
    private static final int[] GRID_SLOTS = {
        11, 12, 13, 14, 15,
        20, 21, 22, 23, 24,
        29, 30, 31, 32, 33,
    };
    private static final int SLOT_ROLE_INFO = 36;
    private static final int SLOT_UPGRADE_1 = 38;
    private static final int SLOT_UPGRADE_2 = 40;
    private static final int SLOT_UPGRADE_3 = 42;
    private static final int SLOT_CHANGE_ROLE = 45;
    private static final int SLOT_SWITCH = 49;
    private static final int SLOT_CLOSE = 53;
    // ----------------------------------------------------

    private final CoreMCPlugin plugin;
    private final String roleKey;

    public EnchantGui(final CoreMCPlugin plugin, final String roleKey) {
        this.plugin = plugin;
        this.roleKey = roleKey;
    }

    @Override
    public String title() {
        return "&b&lOMNI-TOOL &8— &7" + plugin.enchants().roleLabel(roleKey);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final List<Enchant> track = plugin.enchants().registry().forRole(roleKey);
        final int owned = plugin.enchants().ownedOf(profile, roleKey).size();

        final Role selectedRole = plugin.roles().roleOf(profile).orElse(null);
        final var roleView = selectedRole == null ? null : plugin.roles().roleView(profile, selectedRole);
        final var toolView = plugin.roles().toolView(profile);
        inventory.setItem(
                SLOT_HEADER,
                GuiService.item(
                        selectedRole == null ? Material.ENCHANTED_BOOK : selectedRole.toolMaterial(),
                        plugin.enchants().roleLabel(roleKey) + " &7enchants &8(&b" + owned + "&7/&b"
                                + track.size() + "&8)",
                        List.of(
                                "&7Balance: &f" + String.format(java.util.Locale.ROOT, "%,d",
                                        profile.skyTokens()) + " Sky Tokens",
                                "&7Role level: &f" + (roleView == null ? 0 : roleView.level()),
                                "&7OmniTool level: &f" + toolView.level(),
                                "&7Left-click an enchant to upgrade it.",
                                "&7Right-click an enchant for details.")));

        for (int index = 0; index < GRID_SLOTS.length && index < track.size(); index++) {
            final Enchant enchant = track.get(index);
            final int level = profile.enchantLevel(enchant.id());
            final boolean locked = level <= 0 && !plugin.enchants().levelGateMet(profile, enchant);
            final List<String> lore = new ArrayList<>(plugin.enchants().lore(profile, enchant));
            Material icon = iconOf(enchant);
            String name = enchant.display();
            if (locked) {
                icon = Material.GRAY_DYE;
                name = "&8&lLOCKED: " + name;
            }
            final org.bukkit.inventory.ItemStack enchantItem = GuiService.item(icon, name, lore);
            if (level >= enchant.maxLevel() && !locked) {
                final var meta = enchantItem.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                enchantItem.setItemMeta(meta);
            }
            inventory.setItem(GRID_SLOTS[index], enchantItem);
        }

        inventory.setItem(SLOT_ROLE_INFO, roleInfo(profile, selectedRole, roleView, toolView));
        upgradeEntry(inventory, profile, SLOT_UPGRADE_1, OmniUpgradeCatalog.EFFICIENCY);
        upgradeEntry(inventory, profile, SLOT_UPGRADE_2, OmniUpgradeCatalog.FORTUNE);
        upgradeEntry(inventory, profile, SLOT_UPGRADE_3, OmniUpgradeCatalog.SMELTER);

        inventory.setItem(SLOT_CHANGE_ROLE, GuiService.item(
                Material.COMPASS, "&eChange Role", List.of("&7Open the role selector.", "&7Progress is never reset.")));
        if (roleKey.equals("universal")) {
            inventory.setItem(
                    SLOT_SWITCH,
                    GuiService.item(
                            Material.ENCHANTED_BOOK,
                            "&dRole enchants",
                            List.of("&7View your role's track.")));
        } else {
            inventory.setItem(
                    SLOT_SWITCH,
                    GuiService.item(
                            Material.AMETHYST_SHARD,
                            "&dUniversal enchants",
                            List.of("&7Works with every role.")));
        }
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&cClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    private org.bukkit.inventory.ItemStack roleInfo(
            final PlayerProfile profile,
            final Role selectedRole,
            final com.coremc.core.role.RoleService.ProgressView roleView,
            final com.coremc.core.role.RoleService.ProgressView toolView) {
        final List<String> lore = new ArrayList<>();
        if (selectedRole == null || roleView == null) {
            lore.add("&7No role selected.");
        } else {
            lore.add("&7Role level: &f" + roleView.level() + (roleView.maxed() ? " &8(MAX)" : ""));
            lore.add("&7Role XP: &f" + roleView.xp() + (roleView.maxed() ? "" : "&7/&f" + roleView.xpToNext()));
        }
        lore.add("&7Tool level: &f" + toolView.level() + (toolView.maxed() ? " &8(MAX)" : ""));
        lore.add("&7Tool XP: &f" + toolView.xp() + (toolView.maxed() ? "" : "&7/&f" + toolView.xpToNext()));
        return GuiService.item(
                selectedRole == null ? Material.GRAY_DYE : selectedRole.icon(),
                selectedRole == null ? "&7No role selected" : selectedRole.display(), lore);
    }

    private void upgradeEntry(
            final Inventory inventory, final PlayerProfile profile, final int slot, final String upgradeId) {
        final var found = plugin.omniTool().upgrades().upgrade(upgradeId);
        if (found.isEmpty()) {
            inventory.setItem(slot, GuiService.item(
                    Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8Upgrade", List.of("&7Not configured.")));
            return;
        }
        final OmniUpgradeCatalog.Upgrade def = found.get();
        final int level = profile.omniUpgrade(upgradeId);
        final Material icon = switch (upgradeId) {
            case OmniUpgradeCatalog.FORTUNE -> Material.AMETHYST_CLUSTER;
            case OmniUpgradeCatalog.SMELTER -> Material.BLAZE_ROD;
            default -> Material.GOLDEN_PICKAXE;
        };
        final List<String> lore = new ArrayList<>();
        lore.add("&7Level: &b" + level + "&7/&b" + def.maxLevel());
        if (def.maxed(level)) {
            lore.add("&a&lMAXED OUT");
        } else {
            lore.add("&7Next level: &a" + String.format(java.util.Locale.ROOT, "%,d", def.costForNextLevel(level))
                    + " Credits");
            lore.add("&eClick to purchase.");
        }
        inventory.setItem(slot, GuiService.item(icon, def.display(), lore));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_CHANGE_ROLE) {
            plugin.gui().open(viewer, new RoleSelectGui(plugin));
            return false;
        }
        if (slot == SLOT_SWITCH) {
            if (roleKey.equals("universal")) {
                final String current = plugin.playerData().profileOf(viewer.getUniqueId())
                        .map(PlayerProfile::roleId).orElse("none");
                if (Role.byKey(current).isEmpty() || "universal".equals(current)) {
                    plugin.gui().open(viewer, new EnchantGui(plugin, "miner"));
                } else {
                    plugin.gui().open(viewer, new EnchantGui(plugin, current));
                }
            } else {
                plugin.gui().open(viewer, new EnchantGui(plugin, "universal"));
            }
            return false;
        }
        final String upgradeId = switch (slot) {
            case SLOT_UPGRADE_1 -> OmniUpgradeCatalog.EFFICIENCY;
            case SLOT_UPGRADE_2 -> OmniUpgradeCatalog.FORTUNE;
            case SLOT_UPGRADE_3 -> OmniUpgradeCatalog.SMELTER;
            default -> null;
        };
        if (upgradeId != null) {
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile != null) {
                plugin.omniTool().purchaseUpgrade(viewer, profile, upgradeId);
            }
            return true;
        }
        final Enchant enchant = gridEnchant(slot);
        if (enchant == null) {
            return false;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        plugin.enchants().purchase(viewer, profile, enchant);
        return true; // re-render: level line + balance update immediately
    }

    @Override
    public boolean onRightClick(final Player viewer, final int slot) {
        final Enchant enchant = gridEnchant(slot);
        if (enchant == null) {
            return onClick(viewer, slot);
        }
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile != null) {
            plugin.enchants().sendDetails(viewer, profile, enchant);
        }
        return false; // details print to chat; no re-render needed
    }

    private Enchant gridEnchant(final int slot) {
        final List<Enchant> track = plugin.enchants().registry().forRole(roleKey);
        for (int index = 0; index < GRID_SLOTS.length && index < track.size(); index++) {
            if (GRID_SLOTS[index] == slot) {
                return track.get(index);
            }
        }
        return null;
    }

    private static Material iconOf(final Enchant enchant) {
        final Material material = Material.matchMaterial(enchant.icon());
        return material == null || material.isAir() ? Material.ENCHANTED_BOOK : material;
    }
}
