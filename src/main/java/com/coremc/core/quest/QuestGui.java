package com.coremc.core.quest;

import com.coremc.core.guide.HelpLinks;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Chest GUI for Daily Quests, Weekly Quests and shared Island Challenges. */
public final class QuestGui {

    public static final int SIZE = 54;
    public static final int SLOT_STREAK = 4;
    public static final int SLOT_DAILY = 20;
    public static final int SLOT_WEEKLY = 22;
    public static final int SLOT_ISLAND = 24;
    public static final int SLOT_COMPLETED = 40;
    public static final int SLOT_BACK = 45;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_HELP = 53;
    private static final int[] QUEST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34};

    private final QuestService quests;
    private final IslandService islands;

    public QuestGui(final QuestService quests, final IslandService islands) {
        this.quests = quests;
        this.islands = islands;
    }

    public void openRoot(final Player player) {
        quests.ensure(player);
        final QuestMenu menu = new QuestMenu(QuestMenu.Kind.ROOT);
        final Inventory inv = Bukkit.createInventory(menu, SIZE, ColorUtil.colorize("&3&lCOREMC &8— &bQuests"));
        menu.inventory(inv);
        final QuestState.PlayerState state = quests.playerState(player.getUniqueId());
        inv.setItem(SLOT_STREAK, GuiItems.item(Material.CLOCK, "&b&lQuest Streak",
                "&7ᴅᴀɪʟʏ sᴛʀᴇᴀᴋ: &f" + state.streak(),
                "&7ᴄᴏᴍᴘʟᴇᴛᴇ ᴀᴛ ʟᴇᴀsᴛ ᴏɴᴇ",
                "&7ᴅᴀɪʟʏ ǫᴜᴇsᴛ ᴛᴏ ᴀᴅᴠᴀɴᴄᴇ."));
        inv.setItem(SLOT_DAILY, summary(Material.LIGHT_BLUE_DYE, "&b&lᴅᴀɪʟʏ ǫᴜᴇsᴛs",
                quests.daily(player), quests.nextDailyReset(player)));
        inv.setItem(SLOT_WEEKLY, summary(Material.PURPLE_DYE, "&d&lᴡᴇᴇᴋʟʏ ǫᴜᴇsᴛs",
                quests.weekly(player), quests.nextWeeklyReset(player)));
        inv.setItem(SLOT_ISLAND, summary(Material.GRASS_BLOCK, "&a&lɪsʟᴀɴᴅ ᴄʜᴀʟʟᴇɴɢᴇs",
                quests.challenges(player), quests.nextIslandReset(player)));
        inv.setItem(SLOT_COMPLETED, GuiItems.item(Material.CHEST,
                "&e&lᴄᴏᴍᴘʟᴇᴛᴇᴅ / ᴄʟᴀɪᴍᴀʙʟᴇ",
                "&7ᴠɪᴇᴡ ǫᴜᴇsᴛs ʀᴇᴀᴅʏ ᴛᴏ ᴄʟᴀɪᴍ.",
                "&eClick to view"));
        inv.setItem(SLOT_HELP, HelpLinks.icon("quests"));
        inv.setItem(SLOT_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inv);
        player.openInventory(inv);
    }

    public void openDaily(final Player player) {
        openList(player, QuestMenu.Kind.DAILY, "&3&lQuests &8— &bDaily", quests.daily(player));
    }

    public void openWeekly(final Player player) {
        openList(player, QuestMenu.Kind.WEEKLY, "&3&lQuests &8— &dWeekly", quests.weekly(player));
    }

    public void openIsland(final Player player) {
        openList(player, QuestMenu.Kind.ISLAND, "&3&lQuests &8— &aIsland", quests.challenges(player));
    }

    public void openCompleted(final Player player) {
        quests.ensure(player);
        final List<QuestState.Assignment> list = new ArrayList<>();
        for (final QuestState.Assignment assignment : quests.daily(player)) {
            if (assignment.completed() && !assignment.claimed()) {
                list.add(assignment);
            }
        }
        for (final QuestState.Assignment assignment : quests.weekly(player)) {
            if (assignment.completed() && !assignment.claimed()) {
                list.add(assignment);
            }
        }
        for (final QuestState.Assignment assignment : quests.challenges(player)) {
            if (assignment.completed()) {
                list.add(assignment);
            }
        }
        openList(player, QuestMenu.Kind.COMPLETED, "&3&lQuests &8— &eClaimable", list);
    }

    private void openList(final Player player, final QuestMenu.Kind kind, final String title,
                          final List<QuestState.Assignment> assignments) {
        quests.ensure(player);
        final QuestMenu menu = new QuestMenu(kind);
        final Inventory inv = Bukkit.createInventory(menu, SIZE, ColorUtil.colorize(title));
        menu.inventory(inv);
        int i = 0;
        for (final QuestState.Assignment assignment : assignments) {
            if (i >= QUEST_SLOTS.length) {
                break;
            }
            inv.setItem(QUEST_SLOTS[i++], questItem(player, assignment, kind == QuestMenu.Kind.ISLAND));
        }
        if (i == 0) {
            inv.setItem(22, GuiItems.item(Material.PAPER, "&7No quests here",
                    "&7ᴄʜᴇᴄᴋ ʙᴀᴄᴋ ᴀꜰᴛᴇʀ ᴛʜᴇ ɴᴇxᴛ ʀᴇsᴇᴛ."));
        }
        inv.setItem(SLOT_BACK, GuiItems.back());
        inv.setItem(SLOT_CLOSE, GuiItems.close());
        inv.setItem(SLOT_HELP, HelpLinks.icon("quests"));
        GuiItems.fillEmpty(inv);
        player.openInventory(inv);
    }

    public QuestState.Assignment assignmentAt(final Player player, final QuestMenu.Kind kind, final int slot) {
        final int ordinal = ordinal(slot);
        if (ordinal < 0) {
            return null;
        }
        final List<QuestState.Assignment> assignments = switch (kind) {
            case DAILY -> quests.daily(player);
            case WEEKLY -> quests.weekly(player);
            case ISLAND -> quests.challenges(player);
            case COMPLETED -> completed(player);
            case ROOT -> List.of();
        };
        return ordinal < assignments.size() ? assignments.get(ordinal) : null;
    }

    private List<QuestState.Assignment> completed(final Player player) {
        final List<QuestState.Assignment> list = new ArrayList<>();
        for (final QuestState.Assignment assignment : quests.daily(player)) {
            if (assignment.completed() && !assignment.claimed()) {
                list.add(assignment);
            }
        }
        for (final QuestState.Assignment assignment : quests.weekly(player)) {
            if (assignment.completed() && !assignment.claimed()) {
                list.add(assignment);
            }
        }
        for (final QuestState.Assignment assignment : quests.challenges(player)) {
            if (assignment.completed()) {
                list.add(assignment);
            }
        }
        return list;
    }

    private int ordinal(final int slot) {
        for (int i = 0; i < QUEST_SLOTS.length; i++) {
            if (QUEST_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack summary(final Material icon, final String name,
                              final List<QuestState.Assignment> assignments, final long resetAt) {
        int complete = 0;
        for (final QuestState.Assignment assignment : assignments) {
            if (assignment.completed()) {
                complete++;
            }
        }
        return GuiItems.item(icon, name,
                "&7ᴘʀᴏɢʀᴇss: &f" + complete + "&7/&f" + assignments.size(),
                "&7ʀᴇsᴇᴛ: &f" + formatReset(resetAt),
                "&eClick to view");
    }

    private ItemStack questItem(final Player player, final QuestState.Assignment assignment,
                                final boolean islandChallenge) {
        final QuestConfig.QuestTemplate template = quests.config().template(assignment.templateId());
        if (template == null) {
            return GuiItems.item(Material.BARRIER, "&cRemoved Quest",
                    "&7ɪᴅ: &f" + assignment.templateId(),
                    "&7ᴛʜɪs ᴛᴇᴍᴘʟᴀᴛᴇ ɴᴏ ʟᴏɴɢᴇʀ ᴇxɪsᴛs.");
        }
        final List<String> lore = new ArrayList<>();
        lore.addAll(template.lore());
        lore.add(" ");
        for (final QuestConfig.ObjectiveDef objective : template.objectives()) {
            lore.add("&7ᴘʀᴏɢʀᴇss: &f" + format(assignment.progress(objective.id()))
                    + "&7/&f" + format(objective.target()));
        }
        lore.add("&7ʀᴇᴡᴀʀᴅs:");
        for (final QuestConfig.RewardDef reward : template.rewards()) {
            lore.add("&8• &f" + format(reward.amount()) + " " + rewardName(reward));
        }
        lore.add(" ");
        if (assignment.claimed()) {
            lore.add("&7sᴛᴀᴛᴜs: &aᴄʟᴀɪᴍᴇᴅ ✔");
        } else if (assignment.completed()) {
            lore.add("&7sᴛᴀᴛᴜs: &aᴄᴏᴍᴘʟᴇᴛᴇ ✔");
            if (quests.hasPendingReward(assignment)) {
                lore.add("&eᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴀɪᴍ / ʀᴇᴛʀʏ ᴘᴇɴᴅɪɴɢ");
            } else {
                lore.add("&eᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴀɪᴍ");
            }
        } else {
            lore.add("&7sᴛᴀᴛᴜs: &cɪɴᴄᴏᴍᴘʟᴇᴛᴇ ✖");
        }
        if (islandChallenge) {
            final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
            if (island != null) {
                final long contribution = quests.islandState(island).contributors(assignment.templateId())
                        .getOrDefault(player.getUniqueId(), 0L);
                lore.add("&7ʏᴏᴜʀ ᴘᴀʀᴛ: &f" + format(contribution));
            }
        }
        return GuiItems.item(template.icon(), "&b&l" + template.name(), lore.toArray(new String[0]));
    }

    private String rewardName(final QuestConfig.RewardDef reward) {
        final String type = reward.type();
        if ("sky-tokens".equals(type)) {
            return "sᴋʏ ᴛᴏᴋᴇɴs";
        }
        if ("credits".equals(type)) {
            return "ᴄʀᴇᴅɪᴛs";
        }
        if ("island-xp".equals(type)) {
            return "ɪsʟᴀɴᴅ xᴘ";
        }
        if (!reward.item().isBlank()) {
            return reward.item().replace('-', ' ');
        }
        return type.replace('-', ' ');
    }

    private String formatReset(final long resetAt) {
        final long millis = Math.max(0L, resetAt - System.currentTimeMillis());
        final long minutes = millis / 60_000L;
        if (minutes >= 60L) {
            return (minutes / 60L) + "h " + (minutes % 60L) + "m";
        }
        return minutes + "m";
    }

    private String format(final long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
