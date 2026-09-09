package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * {@code /is buffs} — the 12 island buffs (54 slots).
 *
 *   rows 2-3 (12 slots)  buffs, click = buy next level
 *   40                   Sky Token balance
 *   44                   back to the island menu
 *
 * Owner-only (members see the gate message); purchases refresh the
 * panel so tiers and balances update immediately.
 */
public final class IslandBuffsGui implements Gui {

    private static final int[] BUFF_SLOTS =
            {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
    private static final int SLOT_BALANCE = 40;
    private static final int SLOT_BACK = 44;

    private final CoreMCPlugin plugin;

    public IslandBuffsGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &dIsland Buffs";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(22, GuiService.item(
                    Material.BARRIER,
                    "&cNot available",
                    List.of("&7Only the island owner can buy buffs.")));
            GuiService.fillGaps(inventory);
            return;
        }
        final Island value = island.get();
        final List<BuffCatalog.Buff> buffs = BuffCatalog.all();

        for (int i = 0; i < buffs.size() && i < BUFF_SLOTS.length; i++) {
            inventory.setItem(BUFF_SLOTS[i], render(buffs.get(i), value));
        }

        final long tokens = plugin.playerData()
                .profileOf(viewer.getUniqueId())
                .map(com.coremc.core.player.PlayerProfile::skyTokens)
                .orElse(0L);
        inventory.setItem(SLOT_BALANCE, GuiService.item(
                Material.NETHER_STAR,
                "&bYour balance",
                List.of("&b" + tokens + " Sky Tokens",
                        "&8Buffs apply to members on the island.")));
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&eBack", List.of()));

        GuiService.fillGaps(inventory);
    }

    private org.bukkit.inventory.ItemStack render(final BuffCatalog.Buff buff, final Island island) {
        final int tier = island.buffs().getOrDefault(buff.id(), 0);
        final int max = plugin.coreConfig().buffMaxTier(buff.id());
        final var cost = plugin.coreConfig().buffCost(buff.id(), tier);
        final int pct = plugin.coreConfig().buffPercent(buff.id());

        final List<String> lore = new ArrayList<>();
        lore.add("&7" + buff.description());
        lore.add("");
        final String current = buff.effectText(tier, pct);
        if (!current.isEmpty()) {
            lore.add("&7Current: " + current);
        }
        if (tier >= max || cost.isEmpty()) {
            lore.add("&a&lMAXED OUT");
        } else {
            lore.add("&7Next: " + buff.nextText(tier, pct));
            lore.add("&7Cost: &b" + cost.getAsLong() + " Sky Tokens");
            lore.add("&eClick to purchase.");
        }
        return GuiService.item(
                buff.icon(),
                buff.display() + (tier > 0 ? " &8[&f" + tier + "&8/&7" + max + "&8]" : ""),
                lore);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new IslandMainGui(plugin));
            return false;
        }
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            return false;
        }
        final List<BuffCatalog.Buff> buffs = BuffCatalog.all();
        for (int i = 0; i < buffs.size() && i < BUFF_SLOTS.length; i++) {
            if (slot == BUFF_SLOTS[i]) {
                return plugin.islands().purchaseBuff(viewer, island.get(), buffs.get(i).id());
            }
        }
        return false;
    }
}
