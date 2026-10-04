package com.coremc.core.quest;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Three-objective daily mission board with direct claim actions. */
public final class QuestsGui implements Gui {

    private static final int[] SLOTS = {20, 22, 24};
    private static final int SLOT_INFO = 4;
    private static final int SLOT_CLOSE = 53;
    private final CoreMCPlugin plugin;

    public QuestsGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fDaily Missions");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        plugin.quests().ensureToday(profile);
        inventory.setItem(SLOT_INFO, GuiService.item(Material.CLOCK, "&eDaily Missions",
                List.of("&7Three objectives reset each UTC day.", "&7Progress and claims persist.")));
        final List<String> assigned = profile.dailyQuests();
        for (int i = 0; i < SLOTS.length && i < assigned.size(); i++) {
            final QuestDefinition quest = plugin.quests().definition(assigned.get(i)).orElse(null);
            if (quest == null) {
                continue;
            }
            final QuestRotation.Progress progress = plugin.quests().progress(profile, quest.id());
            final List<String> lore = new ArrayList<>();
            lore.add("&7Progress: &f" + progress.amount() + "&7/&f" + quest.target());
            lore.add("&7Reward: &e" + quest.rewardCredits() + " Credits &8+ &b"
                    + quest.rewardTokens() + " Sky Tokens");
            lore.add("");
            if (progress.claimed()) {
                lore.add("&a✔ Reward claimed");
            } else if (progress.done()) {
                lore.add("&a✔ Click to claim reward.");
            } else {
                lore.add("&c✖ Objective incomplete");
                lore.add("&7Keep playing to complete this.");
            }
            final String state = progress.claimed() || progress.done() ? "&a✔ " : "&c✖ ";
            inventory.setItem(SLOTS[i], GuiService.item(quest.icon(), state + quest.display(), lore));
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
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        plugin.quests().ensureToday(profile);
        final List<String> assigned = profile.dailyQuests();
        for (int i = 0; i < SLOTS.length && i < assigned.size(); i++) {
            if (slot == SLOTS[i]) {
                return plugin.quests().definition(assigned.get(i))
                        .map(quest -> plugin.quests().claim(viewer, profile, quest)).orElse(false);
            }
        }
        return false;
    }
}
