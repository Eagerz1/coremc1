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
 * The main island menu opened by bare {@code /is}.
 *
 * Full section layout (27 slots, every position a named constant):
 * <pre>
 *   10 Home / Create     11 Members      12 Info      13 Upgrades
 *   14 Settings          15 Permissions  16 Invite    22 Delete
 * </pre>
 * Actions route through the chat commands (single source of behaviour)
 * except the panel-to-panel opens (theme select / members / upgrades /
 * settings / permissions).
 */
public final class IslandMainGui implements Gui {

    private static final int SLOT_HOME = 10;
    private static final int SLOT_MEMBERS = 11;
    private static final int SLOT_INFO = 12;
    private static final int SLOT_UPGRADES = 13;
    private static final int SLOT_SETTINGS = 14;
    private static final int SLOT_PERMISSIONS = 15;
    private static final int SLOT_INVITE = 16;
    private static final int SLOT_DELETE = 22;

    /** All actionable slots — exported so audits and tests never duplicate the layout. */
    public static final java.util.Set<Integer> ACTION_SLOTS =
            java.util.Set.of(10, 11, 12, 13, 14, 15, 16, 22);

    private final CoreMCPlugin plugin;

    public IslandMainGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &fIsland";
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().islandOf(viewer.getUniqueId());
        final boolean hasIsland = island.isPresent();
        final boolean owner = hasIsland && island.get().owner().equals(viewer.getUniqueId());
        final Map<String, String> placeholders = plugin.islands().placeholdersOf(viewer.getUniqueId());

        if (hasIsland) {
            inventory.setItem(SLOT_HOME, GuiService.item(
                    Material.ENDER_PEARL,
                    "&aHome",
                    List.of("&7Teleport to your island.", "", "&eClick to go home.")));
        } else {
            inventory.setItem(SLOT_HOME, GuiService.item(
                    Material.GRASS_BLOCK,
                    "&aCreate your island",
                    List.of("&7Pick a theme and claim", "&7your place in the sky.", "", "&eClick to choose a theme.")));
        }
        inventory.setItem(SLOT_MEMBERS, GuiService.item(
                Material.PLAYER_HEAD,
                "&dMembers",
                hasIsland
                        ? List.of("&7Manage your island team.", "", "&eClick to view members.")
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_INFO, GuiService.item(
                Material.BOOK,
                "&bInformation",
                List.of(
                        "&7Level: &f" + placeholders.get("island_level"),
                        "&7Border: &f" + placeholders.get("island_border"),
                        "&7Members: &f" + placeholders.get("island_members"),
                        "&7Owner: &f" + placeholders.get("island_owner"),
                        "",
                        "&eClick for full details.")));
        inventory.setItem(SLOT_UPGRADES, GuiService.item(
                Material.CRAFTING_TABLE,
                "&6Upgrades",
                List.of("&7Upgrade categories: Mining, Fishing,", "&7Farming, Slaying, Logging and Island.", "", "&eClick to open upgrades.")));
        inventory.setItem(SLOT_SETTINGS, GuiService.item(
                hasIsland && !owner ? Material.GRAY_DYE : Material.REPEATER,
                "&eSettings",
                hasIsland
                        ? (owner
                                ? List.of("&7Toggle island settings:", "&7visitors, mob spawning.", "", "&eClick to open settings.")
                                : List.of("&7View island settings.", "&8Only the owner can change them."))
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_PERMISSIONS, GuiService.item(
                hasIsland && !owner ? Material.GRAY_DYE : Material.OAK_FENCE_GATE,
                "&9Permissions",
                hasIsland
                        ? (owner
                                ? List.of("&7What members may do on", "&7your island.", "", "&eClick to open permissions.")
                                : List.of("&7View what your role can do here.", "&8Only the owner can change permissions."))
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_INVITE, GuiService.item(
                Material.ENDER_EYE,
                "&dInvite",
                List.of("&7/is invite <player>")));
        inventory.setItem(SLOT_DELETE, GuiService.item(
                Material.BARRIER,
                "&cDelete island",
                List.of("&7Danger zone — asks for confirmation.")));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final boolean hasIsland = plugin.islands().islandOf(viewer.getUniqueId()).isPresent();
        switch (slot) {
            case SLOT_HOME -> {
                if (hasIsland) {
                    viewer.closeInventory();
                    viewer.performCommand("is home");
                } else {
                    plugin.gui().open(viewer, new ThemeSelectGui(plugin));
                }
            }
            case SLOT_MEMBERS -> {
                if (hasIsland) {
                    plugin.gui().open(viewer, new IslandMembersGui(plugin));
                }
            }
            case SLOT_INFO -> {
                viewer.closeInventory();
                viewer.performCommand("is info");
            }
            case SLOT_UPGRADES -> {
                if (hasIsland) {
                    plugin.gui().open(viewer, new IslandUpgradesGui(plugin));
                }
            }
            case SLOT_SETTINGS -> {
                if (hasIsland) {
                    plugin.gui().open(viewer, new IslandSettingsGui(plugin));
                }
            }
            case SLOT_PERMISSIONS -> {
                if (hasIsland) {
                    plugin.gui().open(viewer, new IslandPermissionsGui(plugin));
                }
            }
            case SLOT_INVITE -> plugin.messages().sendPrefixed(viewer, "island.invite-hint", Map.of());
            case SLOT_DELETE -> {
                viewer.closeInventory();
                viewer.performCommand("is delete");
            }
            default -> {
            }
        }
        return false;
    }
}
