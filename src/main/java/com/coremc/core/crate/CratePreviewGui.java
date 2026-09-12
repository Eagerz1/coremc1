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
 * One crate's preview + open panel (54-slot double chest).
 *
 *   rows 2-4 (21 slots)  rewards with live drop chances
 *   40                   open button (key balance aware)
 *   45                   back to the lineup
 *   53                   close
 */
public final class CratePreviewGui implements Gui {

    private static final int[] REWARD_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };
    private static final int SLOT_OPEN = 40;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final String crateId;

    public CratePreviewGui(final CoreMCPlugin plugin, final String crateId) {
        this.plugin = plugin;
        this.crateId = crateId;
    }

    @Override
    public String title() {
        final String display = plugin.crates().crate(crateId)
                .map(CrateDefinition::display).orElse("&fCrate");
        return ColorUtil.colorize("&b&lCOREMC &8» " + display);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var crate = plugin.crates().crate(crateId);
        if (crate.isEmpty()) {
            inventory.setItem(22, GuiService.item(
                    Material.BARRIER, "&cUnknown crate", List.of("&7It may have been removed.")));
            GuiService.fillGaps(inventory);
            return;
        }
        final CrateDefinition value = crate.get();
        final List<CrateReward> rewards = value.rewards();
        for (int i = 0; i < REWARD_SLOTS.length && i < rewards.size(); i++) {
            final CrateReward reward = rewards.get(i);
            final String color = CrateReward.rarityColor(reward.rarity());
            final List<String> lore = new ArrayList<>();
            lore.add(color + capitalize(reward.rarity()) + " &8— &f"
                    + CrateService.chancePct(value, reward) + "%");
            if (value.pityReward() != null && reward.label().equals(value.pityReward().label())
                    && reward.type() == value.pityReward().type()) {
                lore.add("&7Also the pity guarantee.");
            }
            inventory.setItem(REWARD_SLOTS[i], GuiService.item(
                    plugin.crates().previewIcon(reward), reward.label(), lore));
        }

        int owned = 0;
        for (final String keyId : value.keys()) {
            owned += plugin.keys().countKeys(viewer, keyId);
        }
        final String keyName = plugin.keys().key(value.keys().get(0))
                .map(CrateKey::display).orElse("&e" + value.keys().get(0));
        final long pitySeen = plugin.playerData().profileOf(viewer.getUniqueId())
                .map(profile -> profile.statOf(value.pityStatKey())).orElse(0L);
        if (owned > 0) {
            final List<String> lore = new ArrayList<>();
            lore.add("&7Consumes &f1x " + keyName + " &8(&7you have &f" + owned + "&8)");
            if (value.pityReward() != null && value.pityCount() > 0) {
                lore.add("&7Pity: &f" + ColorUtil.colorize(value.pityReward().label())
                        + " &7in &f" + Math.max(1, value.pityCount() - pitySeen) + " &7opens");
            }
            lore.add("");
            lore.add("&eClick to open!");
            inventory.setItem(SLOT_OPEN, GuiService.item(Material.EMERALD_BLOCK, "&a&lOPEN", lore));
        } else {
            inventory.setItem(SLOT_OPEN, GuiService.item(
                    Material.REDSTONE_BLOCK,
                    "&c&lNO KEYS",
                    List.of("&7You need " + keyName + "&7.", "&7Keys drop from playtime & rewards.")));
        }
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to the crates.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    private static String capitalize(final String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new CratesGui(plugin));
            return false;
        }
        if (slot == SLOT_OPEN) {
            return plugin.crates().crate(crateId)
                    .map(crate -> plugin.crates().open(viewer, crate))
                    .orElse(false);
        }
        return false;
    }
}
