package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.OmniToolGui;
import com.coremc.core.role.Role;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * One enchant track (the player's role, or universal): double chest
 * (54) with explicit slot constants. The 15 track enchants sit on a
 * centred 5-column grid; left-click buys the next level, right-click
 * shows the full stat block in chat.
 *
 * A role-switcher row previews every track: the player's own track
 * and universal are live, other roles' tracks are visible-but-locked
 * (buying there explains the role requirement instead of charging).
 */
public final class EnchantGui implements Gui {

    // ---------------- layout constants ----------------
    private static final int SLOT_HEADER = 4;
    private static final int[] GRID_SLOTS = {
        11, 12, 13, 14, 15,
        20, 21, 22, 23, 24,
        29, 30, 31, 32, 33,
    };
    private static final int SLOT_NOTE = 40;
    private static final int SLOT_BACK = 47;
    private static final int SLOT_SWITCH = 49;
    private static final int SLOT_CLOSE = 53;
    /** Role-switcher row (row 5): miner logger fisher slayer | farmer universal. */
    private static final int[] ROLE_SLOTS = {36, 37, 38, 39, 41, 42};
    private static final String[] ROLE_KEYS = {"miner", "logger", "fisher", "slayer", "farmer", "universal"};
    // ----------------------------------------------------

    private final CoreMCPlugin plugin;
    private final String roleKey;

    public EnchantGui(final CoreMCPlugin plugin, final String roleKey) {
        this.plugin = plugin;
        this.roleKey = roleKey;
    }

    @Override
    public String title() {
        return "&5&lENCHANTS &8— &7" + plugin.enchants().roleLabel(roleKey);
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

        final Role role = Role.byKey(roleKey).orElse(null);
        inventory.setItem(
                SLOT_HEADER,
                GuiService.item(
                        role == null ? Material.ENCHANTED_BOOK : role.icon(),
                        plugin.enchants().roleLabel(roleKey) + " &7enchants &8(&b" + owned + "&7/&b"
                                + track.size() + "&8)",
                        List.of(
                                "&7Balance: &f" + String.format(java.util.Locale.ROOT, "%,d",
                                        profile.skyTokens()) + " Sky Tokens",
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
            } else if (level >= enchant.maxLevel()) {
                icon = Material.NETHER_STAR;
            }
            inventory.setItem(GRID_SLOTS[index], GuiService.item(icon, name, lore));
        }

        if (roleKey.equals("universal")) {
            inventory.setItem(
                    SLOT_NOTE,
                    GuiService.item(
                            Material.AMETHYST_SHARD,
                            "&7Universal enchants work with &fany role&7.",
                            List.of("&7Your role: &f" + plugin.enchants().roleLabel(profile.roleId()))));
        } else {
            inventory.setItem(
                    SLOT_NOTE,
                    GuiService.item(
                            Material.BOOK,
                            "&8Other roles' enchants unlock via &7/role&8.",
                            List.of("&7Universal enchants: click below.")));
        }

        for (int index = 0; index < ROLE_SLOTS.length; index++) {
            inventory.setItem(ROLE_SLOTS[index], roleSwitcherItem(profile, ROLE_KEYS[index]));
        }

        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&cBack", List.of("&7Omni-Tool panel.")));
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

    /** One role-switcher icon: viewing-state, own-role and locked flavours. */
    private org.bukkit.inventory.ItemStack roleSwitcherItem(final PlayerProfile profile, final String key) {
        final Role role = Role.byKey(key).orElse(null);
        final Material icon = role == null ? Material.ENCHANTED_BOOK : role.icon();
        final String label = plugin.enchants().roleLabel(key);
        if (key.equals(roleKey)) {
            return GuiService.item(icon, "&a▶ " + label, List.of("&7Currently viewing."));
        }
        if (key.equals(profile.roleId()) || "universal".equals(key)) {
            return GuiService.item(icon, "&e" + label, List.of("&7Click to view."));
        }
        return GuiService.item(Material.GRAY_DYE, "&8" + label, List.of("&7Click to preview.", "&8Locked — switch via /role to buy."));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new OmniToolGui(plugin));
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
        for (int index = 0; index < ROLE_SLOTS.length; index++) {
            if (slot == ROLE_SLOTS[index] && !ROLE_KEYS[index].equals(roleKey)) {
                plugin.gui().open(viewer, new EnchantGui(plugin, ROLE_KEYS[index]));
                return false;
            }
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
