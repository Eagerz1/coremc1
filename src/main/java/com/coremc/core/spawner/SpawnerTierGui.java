package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Per-mob spawner submenu — 54-slot double chest, the mob's spawner
 * tiers in a row:
 *
 *   19..23  spawner tiers I–IV plus the Ancient variant (locked =
 *           barrier + kill goal, unlocked = spawner item + price,
 *           buy on click)
 *   45      back to the mob lanes
 *   53      close
 *
 * All positions are named constants — no arithmetic, no off-by-one
 * risk. The tier list is captured from the catalogue at open-time and
 * rebound on re-render (unlocks/prices always reflect live
 * configuration).
 */
public final class SpawnerTierGui implements Gui {

    private static final int[] SLOTS_TIERS = {19, 20, 21, 22, 23};
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final SpawnerDefinition mob;

    public SpawnerTierGui(final CoreMCPlugin plugin, final SpawnerDefinition mob) {
        this.plugin = plugin;
        this.mob = mob;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» " + mob.colouredDisplay());
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final long kills = profile == null ? 0L : plugin.spawners().killsOf(profile, mob);
        for (int i = 0; i < SLOTS_TIERS.length; i++) {
            if (i >= mob.tiers().size()) {
                break;
            }
            final SpawnerTier tier = mob.tiers().get(i);
            final boolean unlocked = profile != null && kills >= tier.requiredKills();
            if (unlocked) {
                final String price = String.format(Locale.ROOT, "%,d", tier.priceSkyTokens());
                final List<String> lore = new ArrayList<>();
                lore.add("&a&lUNLOCKED");
                lore.add("&7" + tier.throughputLine());
                if (tier.ancient()) {
                    lore.add("&5Ancient kill grants triple progress");
                    lore.add("&5toward the next mob lane.");
                }
                lore.add("&7Price: &b" + price + " Sky Tokens");
                lore.add("&eClick to purchase.");
                inventory.setItem(SLOTS_TIERS[i], GuiService.item(Material.SPAWNER, tier.display(), lore));
            } else {
                final List<String> lore = new ArrayList<>();
                lore.add(tier.ancient() ? "&5&lANCIENT VARIANT" : "");
                lore.add("&7Unlocks at &f" + tier.requiredKills() + "&7 "
                        + mob.killKey() + " kills");
                lore.add("&7Progress: &f" + kills + "&7/&f" + tier.requiredKills());
                if (tier.ancient()) {
                    lore.add("&5One Ancient kill counts as triple PROGRESS");
                    lore.add("&5toward the next mob (never three kills).");
                }
                inventory.setItem(SLOTS_TIERS[i], GuiService.item(
                        Material.BARRIER, "&c&lLOCKED — " + tier.display(),
                        lore.stream().filter(line -> !line.isEmpty()).toList()));
            }
        }
        inventory.setItem(SLOT_BACK, GuiService.item(
                Material.ARROW, "&e&lBack", List.of("&7Return to the mob lanes.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new SpawnersGui(plugin));
            return false;
        }
        for (int i = 0; i < SLOTS_TIERS.length; i++) {
            if (slot != SLOTS_TIERS[i] || i >= mob.tiers().size()) {
                continue;
            }
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile == null) {
                return false;
            }
            plugin.spawners().tierFor(mob.tiers().get(i).tierId())
                    .ifPresent(ref -> plugin.spawners().buy(viewer, profile, ref));
            return true; // re-render: locked/unlocked + balance lines update
        }
        return false;
    }
}
