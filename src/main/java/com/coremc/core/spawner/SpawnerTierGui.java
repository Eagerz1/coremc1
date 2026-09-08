package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
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
 * Per-mob spawner submenu — SMALL single chest (9 slots), the mob's
 * spawner tiers visually separated by air columns:
 *
 *   1 / 3 / 5 / 7  spawner tiers I–IV (locked = barrier + kill goal,
 *                  unlocked = spawner item + price, buy on click)
 *   8              back to the mob lanes
 *
 * All positions are named constants — no arithmetic, no off-by-one risk.
 * The tier list is captured from the catalogue at open-time and rebound
 * on re-render (unlocks/prices always reflect live configuration).
 */
public final class SpawnerTierGui implements Gui {

    private static final int[] SLOTS_TIERS = {1, 3, 5, 7};
    private static final int SLOT_BACK = 8;

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
        return 9;
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
                inventory.setItem(SLOTS_TIERS[i], GuiService.item(
                        Material.SPAWNER,
                        tier.display(),
                        List.of(
                                "&a&lUNLOCKED",
                                "&7" + tier.throughputLine(),
                                "&7Price: &b" + price + " Sky Tokens",
                                "&eClick to purchase.")));
            } else {
                inventory.setItem(SLOTS_TIERS[i], GuiService.item(
                        Material.BARRIER,
                        "&c&lLOCKED — " + tier.display(),
                        List.of(
                                "&7Unlocks at &f" + tier.requiredKills() + "&7 "
                                        + mob.killKey() + " kills",
                                "&7Progress: &f" + kills + "&7/&f" + tier.requiredKills())));
            }
        }
        inventory.setItem(SLOT_BACK, GuiService.item(
                Material.ARROW, "&e&lBack", List.of("&7Return to the mob lanes.")));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
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
