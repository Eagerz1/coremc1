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
    private final boolean weekly;

    public QuestsGui(final CoreMCPlugin plugin) { this(plugin, false); }

    public QuestsGui(final CoreMCPlugin plugin, final boolean weekly) {
        this.plugin = plugin;
        this.weekly = weekly;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &f" + (weekly ? "Weekly Missions" : "Daily Missions"));
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
        if (weekly) plugin.quests().ensureWeekly(profile); else plugin.quests().ensureToday(profile);
        inventory.setItem(SLOT_INFO, GuiService.item(Material.CLOCK, weekly ? "&eWeekly Missions" : "&eDaily Missions",
                List.of(weekly ? "&7Three objectives reset each UTC week." : "&7Three objectives reset each UTC day.", "&7Progress and claims persist.")));
        final List<String> assigned = weekly ? plugin.quests().weeklyAssigned(profile) : profile.dailyQuests();
        for (int i = 0; i < SLOTS.length && i < assigned.size(); i++) {
            final QuestDefinition quest = (weekly ? plugin.quests().weeklyDefinition(assigned.get(i)) : plugin.quests().definition(assigned.get(i))).orElse(null);
            if (quest == null) {
                continue;
            }
            final QuestRotation.Progress progress = weekly ? plugin.quests().weeklyProgress(profile, quest.id()) : plugin.quests().progress(profile, quest.id());
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
        if (weekly) plugin.quests().ensureWeekly(profile); else plugin.quests().ensureToday(profile);
        final List<String> assigned = weekly ? plugin.quests().weeklyAssigned(profile) : profile.dailyQuests();
        for (int i = 0; i < SLOTS.length && i < assigned.size(); i++) {
            if (slot == SLOTS[i]) {
                return (weekly ? plugin.quests().weeklyDefinition(assigned.get(i)) : plugin.quests().definition(assigned.get(i)))
                        .map(quest -> weekly ? plugin.quests().claimWeekly(viewer, profile, quest) : plugin.quests().claim(viewer, profile, quest)).orElse(false);
            }
        }
        return false;
    }
}
