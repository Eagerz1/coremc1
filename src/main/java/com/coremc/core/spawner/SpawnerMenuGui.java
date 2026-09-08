package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Spawner purchase menu — SMALL single chest (9 slots), the four spawners
 * visually separated by air columns:
 *
 *   1 / 3 / 5 / 7  the four spawner types (buy on click)
 *   8              back to overview
 *
 * All positions are named constants — no arithmetic, no off-by-one risk.
 */
public final class SpawnerMenuGui implements Gui {

    private static final int[] SLOTS_SPAWNERS = {1, 3, 5, 7};
    private static final int SLOT_BACK = 8;

    private final CoreMCPlugin plugin;

    public SpawnerMenuGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fSpawner Menu");
    }

    @Override
    public int size() {
        return 9;
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
            final String price = String.format(Locale.ROOT, "%,d", def.priceSkyTokens());
            inventory.setItem(SLOTS_SPAWNERS[i], GuiService.item(
                    Material.SPAWNER,
                    def.display(),
                    unlocked
                            ? List.of("&a&lUNLOCKED", "&7Price: &b" + price + " Sky Tokens", "&eClick to purchase.")
                            : List.of("&c&lLOCKED",
                                    "&7Requires &f" + def.requiredKills() + "&7 kills &8(&f" + kills
                                            + "&8/&7" + def.requiredKills() + "&8)")));
        }
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to unlock progress.")));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new SpawnersGui(plugin));
            return false;
        }
        for (int i = 0; i < SLOTS_SPAWNERS.length; i++) {
            if (slot != SLOTS_SPAWNERS[i]) {
                continue;
            }
            final List<SpawnerDefinition> defs = plugin.spawners().all();
            if (i >= defs.size()) {
                return false;
            }
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile == null) {
                return false;
            }
            plugin.spawners().buy(viewer, profile, defs.get(i));
            return true; // re-render (locked items re-render, balance holders refresh)
        }
        return false;
    }
}
