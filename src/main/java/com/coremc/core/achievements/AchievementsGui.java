package com.coremc.core.achievements;

import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiGrid;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders the Achievement menus: the category overview with the
 * player's Achievement Points, a paged view of each category, and the
 * secrets page.
 *
 * <p>Four states are always visually distinct — earned (green, glowing
 * when a reward is waiting), in progress (coloured by difficulty with
 * a progress bar), locked/not started (grey pane), and secret (a dull
 * grey dye with no details at all).</p>
 */
public final class AchievementsGui {

    private static final String PREFIX = "&3&lCOREMC &8— &d";

    private final AchievementConfig config;
    private final AchievementService achievements;
    private final RewardService rewards;

    public AchievementsGui(final AchievementConfig config, final AchievementService achievements,
                           final RewardService rewards) {
        this.config = config;
        this.achievements = achievements;
        this.rewards = rewards;
    }

    /** Opens the category overview. */
    public void openRoot(final Player player) {
        final AchievementsHolder holder =
                new AchievementsHolder(AchievementsHolder.View.ROOT, null, 0);
        final Inventory inventory = create(holder, "Achievements");

        inventory.setItem(AchievementsLayout.PANEL, panel(player));
        final List<AchievementCategory> categories = config.categories();
        for (int index = 0; index < categories.size() && index < GuiGrid.PER_PAGE; index++) {
            inventory.setItem(AchievementsLayout.contentSlot(index),
                    categoryItem(player, categories.get(index)));
        }
        inventory.setItem(AchievementsLayout.EXTRA, secretsButton(player));
        inventory.setItem(AchievementsLayout.PENDING, pendingButton(player));
        inventory.setItem(AchievementsLayout.BACK, GuiItems.back("island menu"));
        inventory.setItem(AchievementsLayout.CLOSE, GuiItems.close());
        finish(player, inventory);
    }

    /** Opens one category, paged. */
    public void openCategory(final Player player, final AchievementCategory category,
                             final int rawPage) {
        final List<Achievement> all = config.byCategory(category);
        final int page = GuiGrid.clampPage(rawPage, all.size());
        final AchievementsHolder holder =
                new AchievementsHolder(AchievementsHolder.View.CATEGORY, category, page);
        final Inventory inventory = create(holder, category.display());

        inventory.setItem(AchievementsLayout.PANEL, categoryPanel(player, category));
        render(player, inventory, all, page);
        navigation(inventory, page, GuiGrid.pages(all.size()), "achievements menu");
        inventory.setItem(AchievementsLayout.EXTRA, pendingButton(player));
        finish(player, inventory);
    }

    /** Opens the secrets page. */
    public void openSecrets(final Player player, final int rawPage) {
        final List<Achievement> secrets = new ArrayList<>();
        for (final Achievement achievement : config.all()) {
            if (achievement.secret()) {
                secrets.add(achievement);
            }
        }
        final int page = GuiGrid.clampPage(rawPage, secrets.size());
        final AchievementsHolder holder =
                new AchievementsHolder(AchievementsHolder.View.SECRETS, null, page);
        final Inventory inventory = create(holder, "Secrets");

        int found = 0;
        for (final Achievement achievement : secrets) {
            if (achievements.profile(player.getUniqueId()).earned(achievement.id())) {
                found++;
            }
        }
        inventory.setItem(AchievementsLayout.PANEL, GuiItems.item(Material.ENDER_EYE,
                "&8&l" + GuiText.caps("Secret Achievements"),
                GuiText.value("Found", "&f", GuiText.progress(found, secrets.size())),
                GuiText.blank(),
                "&7" + GuiText.caps("Secrets stay hidden until"),
                "&7" + GuiText.caps("you stumble into them."),
                GuiText.blank(),
                GuiText.hint("No hints are given. Ever.")));
        render(player, inventory, secrets, page);
        navigation(inventory, page, GuiGrid.pages(secrets.size()), "achievements menu");
        finish(player, inventory);
    }

    private void render(final Player player, final Inventory inventory,
                        final List<Achievement> list, final int page) {
        final int offset = GuiGrid.offset(page, GuiGrid.PER_PAGE);
        for (int index = 0; index < GuiGrid.PER_PAGE; index++) {
            final int achievementIndex = offset + index;
            if (achievementIndex >= list.size()) {
                break;
            }
            inventory.setItem(AchievementsLayout.contentSlot(index),
                    achievementItem(player, list.get(achievementIndex)));
        }
    }

    // ------------------------------------------------------------------
    // items
    // ------------------------------------------------------------------

    private ItemStack achievementItem(final Player player, final Achievement achievement) {
        if (achievements.hidden(player.getUniqueId(), achievement)) {
            return GuiItems.item(Material.GRAY_DYE, AchievementLore.secretTitle(),
                    AchievementLore.secret());
        }
        final boolean earned = achievements.profile(player.getUniqueId())
                .earned(achievement.id());
        final boolean claimable = achievements.claimable(player.getUniqueId(), achievement);
        final List<String> lore = AchievementLore.achievement(achievement,
                achievements.progress(player.getUniqueId(), achievement), earned, claimable,
                achievements.earnedSeason(player.getUniqueId(), achievement));
        if (!earned) {
            final long progress = achievements.progress(player.getUniqueId(), achievement);
            // not started looks clearly different from in progress
            return GuiItems.item(progress > 0 ? achievement.icon()
                            : Material.GRAY_STAINED_GLASS_PANE,
                    AchievementLore.title(achievement, false), lore);
        }
        final ItemStack item = GuiItems.item(achievement.icon(),
                AchievementLore.title(achievement, true), lore);
        return claimable ? GuiItems.glow(item) : item;
    }

    private ItemStack categoryItem(final Player player, final AchievementCategory category) {
        final List<Achievement> list = config.byCategory(category);
        int points = 0;
        int claimable = 0;
        for (final Achievement achievement : list) {
            if (achievements.profile(player.getUniqueId()).earned(achievement.id())) {
                points += achievement.points();
            }
            if (achievements.claimable(player.getUniqueId(), achievement)) {
                claimable++;
            }
        }
        final ItemStack item = GuiItems.item(category.icon(),
                category.color() + "&l" + GuiText.caps(category.display()),
                AchievementLore.category(category, list.size(),
                        achievements.earnedIn(player.getUniqueId(), category), points, claimable));
        return claimable > 0 ? GuiItems.glow(item) : item;
    }

    private ItemStack categoryPanel(final Player player, final AchievementCategory category) {
        final List<Achievement> list = config.byCategory(category);
        int points = 0;
        for (final Achievement achievement : list) {
            if (achievements.profile(player.getUniqueId()).earned(achievement.id())) {
                points += achievement.points();
            }
        }
        final List<String> lore = new ArrayList<>(AchievementLore.category(category, list.size(),
                achievements.earnedIn(player.getUniqueId(), category), points, 0));
        lore.remove(lore.size() - 1);
        lore.remove(lore.size() - 1);
        return GuiItems.item(category.icon(),
                category.color() + "&l" + GuiText.caps(category.display() + " Achievements"), lore);
    }

    private ItemStack panel(final Player player) {
        final int pending = rewards == null ? 0 : rewards.pending().count(player.getUniqueId());
        return GuiItems.head(player, "&d&l" + GuiText.caps("Your Achievements"),
                AchievementLore.panel(achievements.points(player.getUniqueId()),
                        config.maxPoints(), achievements.earnedCount(player.getUniqueId()),
                        config.all().size(), achievements.claimableCount(player.getUniqueId()),
                        pending));
    }

    private ItemStack secretsButton(final Player player) {
        int secrets = 0;
        int found = 0;
        for (final Achievement achievement : config.all()) {
            if (achievement.secret()) {
                secrets++;
                if (achievements.profile(player.getUniqueId()).earned(achievement.id())) {
                    found++;
                }
            }
        }
        return GuiItems.item(Material.ENDER_EYE, "&8&l" + GuiText.caps("Secrets"),
                GuiText.value("Found", "&f", GuiText.progress(found, secrets)),
                GuiText.blank(),
                GuiText.click("Click to view secrets"));
    }

    private ItemStack pendingButton(final Player player) {
        final int held = rewards == null ? 0 : rewards.pending().count(player.getUniqueId());
        final ItemStack item = GuiItems.item(Material.ENDER_CHEST,
                "&e&l" + GuiText.caps("Held Rewards"),
                GuiText.value("Waiting", "&f", GuiText.number(held)),
                GuiText.blank(),
                held > 0 ? GuiText.click("Click to collect")
                        : "&8" + GuiText.caps("Nothing waiting"));
        return held > 0 ? GuiItems.glow(item) : item;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private Inventory create(final AchievementsHolder holder, final String title) {
        final Inventory inventory = Bukkit.createInventory(holder, AchievementsLayout.SIZE,
                ColorUtil.colorize(PREFIX + title));
        holder.inventory(inventory);
        return inventory;
    }

    private void navigation(final Inventory inventory, final int page, final int pages,
                            final String backTarget) {
        inventory.setItem(AchievementsLayout.BACK, GuiItems.back(backTarget));
        inventory.setItem(AchievementsLayout.CLOSE, GuiItems.close());
        if (page > 0) {
            inventory.setItem(AchievementsLayout.PREVIOUS,
                    GuiItems.item(Material.SPECTRAL_ARROW, "&e&l" + GuiText.caps("Previous page"),
                            GuiText.value("Page", "&f", GuiText.progress(page, pages))));
        }
        if (page + 1 < pages) {
            inventory.setItem(AchievementsLayout.NEXT,
                    GuiItems.item(Material.SPECTRAL_ARROW, "&e&l" + GuiText.caps("Next page"),
                            GuiText.value("Page", "&f", GuiText.progress(page + 2, pages))));
        }
    }

    private void finish(final Player player, final Inventory inventory) {
        GuiItems.frame(inventory, AchievementsLayout.frameSlots());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }
}
