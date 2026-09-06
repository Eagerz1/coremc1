package com.coremc.core.gen;

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
 * {@code /gens} — the generator market (27-slot, single chest).
 *
 * Layout (all positions named constants, no arithmetic):
 *   10/12/14/16  generator entries (max 4 — matches config catalogue)
 *   22           your Credits balance display
 *   26           close
 */
public final class GensGui implements Gui {

    private static final int[] SLOTS_GENS = {10, 12, 14, 16};
    private static final int SLOT_BALANCE = 22;
    private static final int SLOT_CLOSE = 26;

    private final CoreMCPlugin plugin;

    public GensGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fGenerator Market");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<GeneratorDefinition> gens = plugin.generators().all();
        for (int i = 0; i < SLOTS_GENS.length; i++) {
            if (i >= gens.size()) {
                break;
            }
            final GeneratorDefinition def = gens.get(i);
            final String price = String.format(Locale.ROOT, "%,d", def.priceCredits());
            inventory.setItem(SLOTS_GENS[i], GuiService.item(
                    def.blockMaterial(),
                    def.display(),
                    List.of(
                            "&7Produces: &f" + def.productName(),
                            "&7Cooldown: &f" + def.cooldownSeconds() + "s",
                            "",
                            "&7Price: &a" + price + " Credits",
                            "&eClick to purchase.")));
        }
        final long balance = profile == null ? 0L : profile.credits();
        inventory.setItem(SLOT_BALANCE, GuiService.item(
                Material.GOLD_INGOT,
                "&6Your Credits",
                List.of("&7Balance: &a" + String.format(Locale.ROOT, "%,d", balance),
                        "&7Generators are bought with Credits.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        for (int i = 0; i < SLOTS_GENS.length; i++) {
            if (slot != SLOTS_GENS[i]) {
                continue;
            }
            final List<GeneratorDefinition> gens = plugin.generators().all();
            if (i >= gens.size()) {
                return false;
            }
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile == null) {
                return false;
            }
            plugin.generators().buy(viewer, profile, gens.get(i));
            return true; // re-render: balance display must refresh
        }
        return false;
    }
}
