package com.coremc.core.season;

import com.coremc.core.guide.HelpLinks;
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

/** Paginated Season Journey reward track GUI. */
public final class SeasonJourneyGui {

    public static final int SIZE = 54;
    public static final int SLOT_INFO = 4;
    public static final int SLOT_QUESTS = 45;
    public static final int SLOT_EVENTS = 46;
    public static final int SLOT_ISLAND = 47;
    public static final int SLOT_PREV = 48;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_NEXT = 50;
    public static final int SLOT_ROLES = 51;
    public static final int SLOT_HELP = 53;
    private static final int[] TIER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34};

    private final SeasonJourneyService journey;

    public SeasonJourneyGui(final SeasonJourneyService journey) {
        this.journey = journey;
    }

    public void open(final Player player, final int page) {
        final int safePage = Math.max(0, Math.min(maxPage(), page));
        final SeasonJourneyMenu menu = new SeasonJourneyMenu(safePage);
        final Inventory inv = Bukkit.createInventory(menu, SIZE,
                ColorUtil.colorize("&3&lCOREMC &8— &bSeason Journey"));
        menu.inventory(inv);
        inv.setItem(SLOT_INFO, infoItem(player));
        final int startLevel = safePage * TIER_SLOTS.length + 1;
        for (int i = 0; i < TIER_SLOTS.length; i++) {
            final int level = startLevel + i;
            if (level > journey.config().maxLevel()) {
                break;
            }
            inv.setItem(TIER_SLOTS[i], tierItem(player, level));
        }
        inv.setItem(SLOT_QUESTS, GuiItems.item(Material.WRITABLE_BOOK, "&b&lǫᴜᴇsᴛs",
                "&7ᴇᴀʀɴ sᴇᴀsᴏɴ xᴘ ꜰʀᴏᴍ", "&7ᴅᴀɪʟʏ ᴀɴᴅ ᴡᴇᴇᴋʟʏ ǫᴜᴇsᴛs.", "&eClick for /quests"));
        inv.setItem(SLOT_EVENTS, GuiItems.item(Material.BELL, "&d&lᴇᴠᴇɴᴛs",
                "&7ᴀᴄᴛɪᴠᴇ ᴇᴠᴇɴᴛ ᴘʟᴀʏ", "&7ᴄᴀɴ ᴇᴀʀɴ sᴇᴀsᴏɴ xᴘ.", "&eClick for /coreevent status"));
        inv.setItem(SLOT_ISLAND, GuiItems.item(Material.NETHER_STAR, "&a&lɪsʟᴀɴᴅ ᴘʀᴏɢʀᴇss",
                "&7ʟᴇᴠᴇʟ, ᴍᴀsᴛᴇʀʏ ᴀɴᴅ ᴄᴏʀᴇ", "&7ᴍɪʟᴇsᴛᴏɴᴇs ꜰᴇᴇᴅ ᴛʜᴇ ᴊᴏᴜʀɴᴇʏ.", "&eClick for /is upgrades"));
        inv.setItem(SLOT_ROLES, GuiItems.item(Material.PLAYER_HEAD, "&e&lʀᴏʟᴇs",
                "&7ʀᴏʟᴇ ᴍɪʟᴇsᴛᴏɴᴇ ʜᴏᴏᴋs", "&7ᴀʀᴇ ʀᴇᴀᴅʏ ꜰᴏʀ ʟᴀᴛᴇʀ ʙʀᴀɴᴄʜᴇs."));
        if (safePage > 0) {
            inv.setItem(SLOT_PREV, GuiItems.item(Material.ARROW, "&e&lPrevious Page"));
        }
        if (safePage < maxPage()) {
            inv.setItem(SLOT_NEXT, GuiItems.item(Material.ARROW, "&e&lNext Page"));
        }
        inv.setItem(SLOT_CLOSE, GuiItems.close());
        inv.setItem(SLOT_HELP, HelpLinks.icon("season-journey"));
        GuiItems.fillEmpty(inv);
        player.openInventory(inv);
    }

    public int levelAt(final int page, final int slot) {
        for (int i = 0; i < TIER_SLOTS.length; i++) {
            if (TIER_SLOTS[i] == slot) {
                final int level = page * TIER_SLOTS.length + 1 + i;
                return level <= journey.config().maxLevel() ? level : -1;
            }
        }
        return -1;
    }

    public int maxPage() {
        return Math.max(0, (journey.config().maxLevel() - 1) / TIER_SLOTS.length);
    }

    public double progressPercent(final Player player) {
        return Math.min(1.0D, journey.xp(player.getUniqueId()) / (double) Math.max(1L, journey.config().maxXp()));
    }

    private ItemStack infoItem(final Player player) {
        final long xp = journey.xp(player.getUniqueId());
        final int level = journey.level(player.getUniqueId());
        final long next = journey.nextLevelXp(player.getUniqueId());
        return GuiItems.item(Material.NETHER_STAR, "&b&l" + journey.season().name(),
                "&7ʟᴇᴠᴇʟ: &b" + level + "&7/&b" + journey.config().maxLevel(),
                "&7xᴘ: &f" + format(xp) + "&7/&f" + format(next),
                "&7ʀᴇᴍᴀɪɴɪɴɢ: &f" + formatDuration(journey.remainingMillis()),
                progressBar(progressPercent(player)),
                "&7ᴘʀᴇᴍɪᴜᴍ: " + (journey.config().premiumEnabled() ? "&aᴇɴᴀʙʟᴇᴅ" : "&8ᴅɪsᴀʙʟᴇᴅ"));
    }

    private ItemStack tierItem(final Player player, final int level) {
        final SeasonConfig.Tier tier = journey.config().tier(level);
        final int playerLevel = journey.level(player.getUniqueId());
        final SeasonJourneyProfile profile = journey.profile(player.getUniqueId());
        final boolean unlocked = playerLevel >= level;
        final boolean claimed = profile.claimedFree().contains(level);
        final List<String> lore = new ArrayList<>();
        lore.add("&7ʀᴇᴡᴀʀᴅ:");
        if (tier == null || tier.freeRewards().isEmpty()) {
            lore.add("&8• &7Utility reward");
        } else {
            for (final SeasonConfig.Reward reward : tier.freeRewards()) {
                lore.add("&8• &f" + rewardLine(reward));
            }
        }
        lore.add(" ");
        if (claimed) {
            lore.add("&7sᴛᴀᴛᴜs: &aᴄʟᴀɪᴍᴇᴅ ✔");
        } else if (unlocked) {
            lore.add("&7sᴛᴀᴛᴜs: &aʀᴇᴀᴅʏ ✔");
            lore.add("&eᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴀɪᴍ");
        } else {
            lore.add("&7ʀᴇǫᴜɪʀᴇs ʟᴇᴠᴇʟ: &c" + playerLevel + "/" + level + " ✖");
        }
        if (journey.config().premiumEnabled() && tier != null && !tier.premiumRewards().isEmpty()) {
            lore.add(" ");
            lore.add("&6ᴘʀᴇᴍɪᴜᴍ:");
            for (final SeasonConfig.Reward reward : tier.premiumRewards()) {
                lore.add("&8• &f" + rewardLine(reward));
            }
            if (profile.claimedPremium().contains(level)) {
                lore.add("&7ᴘʀᴇᴍɪᴜᴍ: &aᴄʟᴀɪᴍᴇᴅ ✔");
            } else if (unlocked) {
                lore.add("&7ᴘʀᴇᴍɪᴜᴍ: &eʀᴇᴀᴅʏ / ʀɪɢʜᴛ-ᴄʟɪᴄᴋ");
            } else {
                lore.add("&7ᴘʀᴇᴍɪᴜᴍ: &cʟᴏᴄᴋᴇᴅ ✖");
            }
        }
        return GuiItems.item(tier == null ? Material.CHEST : tier.icon(),
                (claimed ? "&a&l" : unlocked ? "&e&l" : "&c&l") + "ʟᴇᴠᴇʟ " + level,
                lore.toArray(new String[0]));
    }

    private String rewardLine(final SeasonConfig.Reward reward) {
        if (reward.display() != null && !reward.display().isBlank()) {
            return reward.display();
        }
        final String name = !reward.item().isBlank() ? reward.item().replace('-', ' ') : reward.type().replace('-', ' ');
        return format(reward.amount()) + "x " + smallCaps(name);
    }

    private String progressBar(final double pct) {
        final int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, pct)) * 20.0D);
        return "&8[&a" + "|".repeat(filled) + "&7" + "|".repeat(20 - filled) + "&8] &f"
                + Math.round(pct * 100.0D) + "%";
    }

    private String formatDuration(final long millis) {
        final long minutes = Math.max(0L, millis / 60_000L);
        final long days = minutes / (60L * 24L);
        final long hours = (minutes / 60L) % 24L;
        return days > 0 ? days + "d " + hours + "h" : hours + "h " + (minutes % 60L) + "m";
    }

    private String format(final long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String smallCaps(final String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('a', 'ᴀ').replace('b', 'ʙ').replace('c', 'ᴄ').replace('d', 'ᴅ')
                .replace('e', 'ᴇ').replace('f', 'ꜰ').replace('g', 'ɢ').replace('h', 'ʜ')
                .replace('i', 'ɪ').replace('l', 'ʟ').replace('m', 'ᴍ').replace('n', 'ɴ')
                .replace('o', 'ᴏ').replace('p', 'ᴘ').replace('r', 'ʀ').replace('s', 's')
                .replace('t', 'ᴛ').replace('u', 'ᴜ').replace('v', 'ᴠ').replace('y', 'ʏ');
    }
}
