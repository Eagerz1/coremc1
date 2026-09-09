package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Island permissions panel ({@code /is} → Permissions) — 54-slot double chest.
 *
 * Top: the role summary (what owners vs members may do).
 * Toggles persisted on the island; enforced live by
 * {@link IslandProtectionListener}:
 *   13 Role summary
 *   29 Members Build      — break/place blocks
 *   33 Members Containers — open chests, use doors/buttons
 *
 * Members may view; only the owner toggles.
 *   45 back   53 close
 */
public final class IslandPermissionsGui implements Gui {

    private static final int SLOT_ROLE_SUMMARY = 13;
    private static final int SLOT_MEMBER_BUILD = 29;
    private static final int SLOT_MEMBER_CONTAINERS = 33;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_GATE = 22;

    private final CoreMCPlugin plugin;

    public IslandPermissionsGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &fIsland Permissions";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().islandOf(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(SLOT_GATE, GuiService.item(
                    Material.BARRIER, "&cNo island", List.of("&7Create one with &f/is create")));
            return;
        }
        final Island value = island.get();
        final IslandRole role = value.roleOf(viewer.getUniqueId());
        final boolean owner = role == IslandRole.OWNER;

        inventory.setItem(SLOT_ROLE_SUMMARY, GuiService.item(
                Material.SHIELD,
                "&9Your role: &f" + (role == null ? "visitor" : role.name().toLowerCase()),
                role == IslandRole.OWNER
                        ? List.of("&7Everything: build, containers,",
                                "&7settings, permissions, invites,",
                                "&7kicks and deletion.")
                        : role == IslandRole.MEMBER
                                ? List.of(
                                        "&7Build: " + state(value.setting(Island.Setting.MEMBERS_BUILD)),
                                        "&7Containers: " + state(value.setting(Island.Setting.MEMBERS_CONTAINERS)),
                                        "&8Settings & permissions are owner-only.")
                                : List.of(
                                        "&7You are not part of this island.",
                                        "&7Visitors: " + state(value.setting(Island.Setting.VISITORS)))));

        toggle(inventory, SLOT_MEMBER_BUILD, value, Island.Setting.MEMBERS_BUILD,
                Material.IRON_PICKAXE, "&9Members Build", owner,
                List.of("&7May members break and place", "&7blocks on the island?"));
        toggle(inventory, SLOT_MEMBER_CONTAINERS, value, Island.Setting.MEMBERS_CONTAINERS,
                Material.CHEST, "&9Members Containers", owner,
                List.of("&7May members open chests and use", "&7doors, buttons and levers?"));
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to the island menu.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    private String state(final boolean on) {
        return on ? "&aallowed" : "&cblocked";
    }

    private void toggle(
            final Inventory inventory,
            final int slot,
            final Island island,
            final Island.Setting setting,
            final Material icon,
            final String name,
            final boolean canEdit,
            final List<String> description) {
        final boolean on = island.setting(setting);
        final java.util.List<String> lore = new java.util.ArrayList<>(description);
        lore.add("");
        lore.add((on ? "&a&lALLOWED" : "&c&lBLOCKED") + (canEdit ? " &8(click to toggle)" : " &8(owner only)"));
        inventory.setItem(slot, GuiService.item(icon, name, lore));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new IslandMainGui(plugin));
            return false;
        }
        final Island.Setting setting = switch (slot) {
            case SLOT_MEMBER_BUILD -> Island.Setting.MEMBERS_BUILD;
            case SLOT_MEMBER_CONTAINERS -> Island.Setting.MEMBERS_CONTAINERS;
            default -> null;
        };
        if (setting == null) {
            return false;
        }
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            plugin.messages().sendPrefixed(viewer, "island.not-owner", Map.of());
            return false;
        }
        final Island value = island.get();
        final boolean next = !value.setting(setting);
        value.setting(setting, next);
        plugin.islands().flush(value);
        plugin.messages().sendPrefixed(viewer, "island.permission-toggled", Map.of(
                "setting", setting.key(), "state", next ? "&aallowed" : "&cblocked"));
        return true;
    }
}
