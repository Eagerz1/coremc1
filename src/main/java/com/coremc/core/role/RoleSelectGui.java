package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Role selection panel (/role). 54 slots; every position is a named
 * constant — no hard-coded slot arithmetic anywhere in the GUI layer.
 *
 * Layout:
 *   - current role display: top centre
 *   - five category roles in a row (slots 11-15)
 *   - Universal centred beneath them
 *   - explainer book: bottom centre
 *   - close: 53
 */
public final class RoleSelectGui implements Gui {

    // ---------------- layout constants ----------------
    private static final int SLOT_CURRENT = 4;
    private static final int SLOT_MINER = 11;
    private static final int SLOT_LOGGER = 12;
    private static final int SLOT_FISHER = 13;
    private static final int SLOT_SLAYER = 14;
    private static final int SLOT_FARMER = 15;
    private static final int SLOT_UNIVERSAL = 22;
    private static final int SLOT_FOOTER = 40;
    private static final int SLOT_CLOSE = 53;
    // ----------------------------------------------------

    private final CoreMCPlugin plugin;

    public RoleSelectGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &7Choose your Role";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final Role current = profile == null ? null : plugin.roles().roleOf(profile).orElse(null);

        inventory.setItem(
                SLOT_CURRENT,
                GuiService.item(
                        current == null ? Material.GRAY_DYE : current.icon(),
                        current == null ? "&7No role selected" : current.display(),
                        current == null
                                ? List.of("&7Click a role below to get your Omni-Tool!")
                                : List.of("&7This is your current role.")));

        slot(inventory, profile, current, Role.MINER, SLOT_MINER);
        slot(inventory, profile, current, Role.LOGGER, SLOT_LOGGER);
        slot(inventory, profile, current, Role.FISHER, SLOT_FISHER);
        slot(inventory, profile, current, Role.SLAYER, SLOT_SLAYER);
        slot(inventory, profile, current, Role.FARMER, SLOT_FARMER);
        slot(inventory, profile, current, Role.UNIVERSAL, SLOT_UNIVERSAL);

        inventory.setItem(
                SLOT_FOOTER,
                GuiService.item(
                        Material.BOOK,
                        "&bHow roles work",
                        List.of(
                                "&7Selecting a role gives you the Omni-Tool.",
                                "&7Relevant actions earn role XP + Omni-Tool XP.",
                                "&7Switching roles never wipes other roles' levels.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    private void slot(
            final Inventory inventory,
            final PlayerProfile profile,
            final Role current,
            final Role role,
            final int slot) {
        final List<String> lore = new ArrayList<>();
        if (profile != null) {
            final RoleService.ProgressView view = plugin.roles().roleView(profile, role);
            lore.add("&7Level: &f" + view.level() + (view.maxed() ? " &8(MAX)" : ""));
            lore.add("&7XP: &f" + view.xp() + (view.maxed() ? "" : "/" + view.xpToNext()));
        }
        lore.add(
                role == Role.UNIVERSAL
                        ? "&7Progresses a little from EVERY action."
                        : "&7Progresses from " + role.category().name().toLowerCase() + " actions.");
        lore.add(current == role ? "&aCurrently selected" : "&eClick to select this role");
        inventory.setItem(slot, GuiService.item(role.icon(), role.display(), lore));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        final Role role = switch (slot) {
            case SLOT_MINER -> Role.MINER;
            case SLOT_LOGGER -> Role.LOGGER;
            case SLOT_FISHER -> Role.FISHER;
            case SLOT_SLAYER -> Role.SLAYER;
            case SLOT_FARMER -> Role.FARMER;
            case SLOT_UNIVERSAL -> Role.UNIVERSAL;
            default -> null;
        };
        if (role == null) {
            return false;
        }
        final PlayerProfile profile =
                plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        if (plugin.roles().select(viewer, profile, role)) {
            plugin.messages().sendPrefixed(viewer, "role.selected", Map.of("role", role.display()));
            return true; // re-render: current-role marker + omnitool refresh
        }
        plugin.messages().sendPrefixed(viewer, "role.already", Map.of("role", role.display()));
        return false;
    }
}
