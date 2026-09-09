package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * {@code /spawners} — per-mob progression overview (54-slot double chest).
 *
 * Each mob lane shows the player's kill count for the entity, how many
 * spawner tiers are unlocked, and the next unlockable tier. Clicking a
 * lane opens its {@link SpawnerTierGui} (the submenu with the mob's
 * four spawners).
 *
 * Layout (all positions named constants):
 *   20/21/23/24  mob lanes (zombie / skeleton / spider / creeper)
 *   53           close
 */
public final class SpawnersGui implements Gui {

    private static final int[] SLOTS_LANES = {20, 21, 23, 24};
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;

    public SpawnersGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return com.coremc.core.util.ColorUtil.colorize("&b&lCOREMC &8» &fSpawners");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<SpawnerDefinition> lanes = plugin.spawners().all();
        for (int i = 0; i < SLOTS_LANES.length; i++) {
            if (i >= lanes.size()) {
                break;
            }
            final SpawnerDefinition mob = lanes.get(i);
            final long kills = profile == null ? 0L : plugin.spawners().killsOf(profile, mob);
            final int unlocked = mob.unlockedTierCount(kills);
            final List<String> lore = new ArrayList<>();
            lore.add("&7Kills: &f" + kills + " &8(" + mob.killKey() + ")");
            lore.add("&7Spawner tiers unlocked: &b" + unlocked + "&7/&b" + mob.tiers().size());
            final var next = mob.nextLockedTier(kills);
            if (next.isPresent()) {
                lore.add("&7Next: " + next.get().display()
                        + " &7at &f" + next.get().requiredKills() + "&7 kills");
            } else {
                lore.add("&aAll spawner tiers unlocked!");
            }
            lore.add("&eClick to view this mob's spawners.");
            inventory.setItem(SLOTS_LANES[i], GuiService.item(
                    mob.icon(),
                    mob.display(),
                    lore));
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
        for (int i = 0; i < SLOTS_LANES.length; i++) {
            if (slot == SLOTS_LANES[i]) {
                final List<SpawnerDefinition> lanes = plugin.spawners().all();
                if (i < lanes.size()) {
                    plugin.gui().open(viewer, new SpawnerTierGui(plugin, lanes.get(i)));
                }
                return false;
            }
        }
        return false;
    }
}
