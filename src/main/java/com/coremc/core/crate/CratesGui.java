package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * {@code /crates} — the crate lineup (54-slot double chest).
 *
 *   19/20/21/23/24/25  crates in file order (click = preview + open)
 *   53                 close
 */
public final class CratesGui implements Gui {

    private static final int[] SLOTS_CRATES = {19, 20, 21, 23, 24, 25};
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;

    public CratesGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fCrates");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final List<CrateDefinition> crates = plugin.crates().all();
        for (int i = 0; i < SLOTS_CRATES.length && i < crates.size(); i++) {
            final CrateDefinition crate = crates.get(i);
            final List<String> lore = new ArrayList<>();
            int owned = 0;
            final List<String> keyNames = new ArrayList<>();
            for (final String keyId : crate.keys()) {
                owned += plugin.keys().countKeys(viewer, keyId);
                plugin.keys().key(keyId).ifPresent(key -> keyNames.add(key.display()));
            }
            lore.add("&7Opens with: " + String.join("&7, ", keyNames));
            lore.add("&7Your keys: &f" + owned);
            lore.add("&7Rewards: &f" + crate.rewards().size() + " &8(&7pity every &f"
                    + crate.pityCount() + "&8)");
            lore.add("");
            lore.add("&eClick to preview & open.");
            Material icon = Material.matchMaterial(crate.icon());
            if (icon == null || icon.isAir()) {
                icon = Material.ENDER_CHEST;
            }
            inventory.setItem(SLOTS_CRATES[i], GuiService.item(icon, crate.display(), lore));
        }
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        final List<CrateDefinition> crates = plugin.crates().all();
        for (int i = 0; i < SLOTS_CRATES.length && i < crates.size(); i++) {
            if (slot == SLOTS_CRATES[i]) {
                plugin.gui().open(viewer, new CratePreviewGui(plugin, crates.get(i).id()));
                return false;
            }
        }
        return false;
    }
}
