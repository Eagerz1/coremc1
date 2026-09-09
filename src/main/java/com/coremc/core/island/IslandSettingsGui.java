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
 * Island settings panel ({@code /is} → Settings).
 *
 * Real, enforced toggles persisted on the island file:
 *   11  Mob spawning   — natural mob spawns inside the border
 *   15  Visitors       — non-team players may enter & interact
 *
 * Members can VIEW the settings; only the owner toggles them.
 *   22  back
 */
public final class IslandSettingsGui implements Gui {

    private static final int SLOT_MOB_SPAWNING = 11;
    private static final int SLOT_VISITORS = 15;
    private static final int SLOT_BACK = 22;

    private final CoreMCPlugin plugin;

    public IslandSettingsGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &fIsland Settings";
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().islandOf(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(SLOT_VISITORS, GuiService.item(
                    Material.BARRIER, "&cNo island", List.of("&7Create one with &f/is create")));
            return;
        }
        final Island value = island.get();
        final boolean owner = value.owner().equals(viewer.getUniqueId());
        final String suffix = owner ? "" : " &8(owner only)";

        toggle(inventory, SLOT_MOB_SPAWNING, value, Island.Setting.MOB_SPAWNING,
                Material.ZOMBIE_HEAD, "&2Mob Spawning",
                List.of("&7Natural mob spawns inside", "&7your island border."), suffix);
        toggle(inventory, SLOT_VISITORS, value, Island.Setting.VISITORS,
                Material.OAK_DOOR, "&eVisitors",
                List.of("&7Allow players outside your team", "&7to enter and interact."), suffix);
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&eBack", List.of()));
        GuiService.fillGaps(inventory);
    }

    private void toggle(
            final Inventory inventory,
            final int slot,
            final Island island,
            final Island.Setting setting,
            final Material icon,
            final String name,
            final List<String> description,
            final String suffix) {
        final boolean on = island.setting(setting);
        final java.util.List<String> lore = new java.util.ArrayList<>(description);
        lore.add("");
        lore.add(on ? "&a&lENABLED &8(click to disable)" : "&c&lDISABLED &8(click to enable)");
        inventory.setItem(slot, GuiService.item(icon, name + suffix, lore));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new IslandMainGui(plugin));
            return false;
        }
        final Island.Setting setting = switch (slot) {
            case SLOT_MOB_SPAWNING -> Island.Setting.MOB_SPAWNING;
            case SLOT_VISITORS -> Island.Setting.VISITORS;
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
        plugin.messages().sendPrefixed(viewer, "island.setting-toggled", Map.of(
                "setting", setting.key(), "state", next ? "&aenabled" : "&cdisabled"));
        return true; // re-render with the new state
    }
}
