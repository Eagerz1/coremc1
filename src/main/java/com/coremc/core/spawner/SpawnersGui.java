package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * {@code /spawners} — unlock progression overview (27-slot single chest).
 *
 * Layout (all positions named constants):
 *   10/12/14/16  spawner entries with kill progress and unlock state
 *   22           "Open the spawner menu" shortcut
 *   26           close
 */
public final class SpawnersGui implements Gui {

    private static final int[] SLOTS_SPAWNERS = {10, 12, 14, 16};
    private static final int SLOT_MENU = 22;
    private static final int SLOT_CLOSE = 26;

    private final CoreMCPlugin plugin;

    public SpawnersGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return com.coremc.core.util.ColorUtil.colorize("&b&lCOREMC &8» &fSpawner Unlocks");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<SpawnerDefinition> defs = plugin.spawners().all();
        for (int i = 0; i < SLOTS_SPAWNERS.length; i++) {
            if (i >= defs.size()) {
                break;
            }
            final SpawnerDefinition def = defs.get(i);
            final long kills = profile == null ? 0L : plugin.spawners().killsOf(profile, def);
            final boolean unlocked = profile != null && plugin.spawners().isUnlocked(profile, def);
            final String state = unlocked
                    ? "&a&lUNLOCKED"
                    : "&c&lLOCKED &8(&7" + kills + "&8/&7" + def.requiredKills() + " kills&8)";
            inventory.setItem(SLOTS_SPAWNERS[i], GuiService.item(
                    def.icon(),
                    def.display(),
                    List.of(
                            "&7Kill &f" + def.requiredKills() + "&7 " + def.killKey() + "s to unlock.",
                            "&7Progress: &f" + kills + "&7 of &f" + def.requiredKills(),
                            state)));
        }
        inventory.setItem(SLOT_MENU, GuiService.item(
                Material.NETHER_STAR,
                "&b&lSpawner Menu",
                List.of("&7Buy unlocked spawners with Sky Tokens.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_MENU) {
            plugin.gui().open(viewer, new SpawnerMenuGui(plugin));
            return false;
        }
        // entry clicks act as info/deny hints — purchases happen in the menu
        for (int i = 0; i < SLOTS_SPAWNERS.length; i++) {
            if (slot == SLOTS_SPAWNERS[i]) {
                plugin.gui().open(viewer, new SpawnerMenuGui(plugin));
                return false;
            }
        }
        return false;
    }
}
