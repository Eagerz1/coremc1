package com.coremc.core.achievements;

import com.coremc.core.collections.ClaimResult;
import com.coremc.core.collections.CollectionsGui;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandGui;
import com.coremc.core.util.GuiGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the Achievement menus: protects the window,
 * routes navigation and runs the same claim flow the command uses.
 */
public final class AchievementsListener implements Listener {

    private final AchievementConfig config;
    private final AchievementService achievements;
    private final AchievementsGui gui;
    private final CollectionsGui collectionsGui;
    private final MessageService messages;
    private final IslandGui islandGui;

    public AchievementsListener(final AchievementConfig config,
                                final AchievementService achievements, final AchievementsGui gui,
                                final CollectionsGui collectionsGui, final MessageService messages,
                                final IslandGui islandGui) {
        this.config = config;
        this.achievements = achievements;
        this.gui = gui;
        this.collectionsGui = collectionsGui;
        this.messages = messages;
        this.islandGui = islandGui;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AchievementsHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            event.setCancelled(true);
            route(player, holder, event.getRawSlot());
        } else if (clicked != null && event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AchievementsHolder)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void route(final Player player, final AchievementsHolder holder, final int slot) {
        if (slot == AchievementsLayout.CLOSE) {
            player.closeInventory();
            return;
        }
        switch (holder.view()) {
            case ROOT -> root(player, slot);
            case CATEGORY -> paged(player, holder, slot, config.byCategory(holder.category()));
            case SECRETS -> paged(player, holder, slot, secrets());
            default -> { }
        }
    }

    private void root(final Player player, final int slot) {
        if (slot == AchievementsLayout.BACK) {
            if (islandGui != null) {
                islandGui.openIslandMenu(player);
            } else {
                player.closeInventory();
            }
            return;
        }
        if (slot == AchievementsLayout.EXTRA) {
            gui.openSecrets(player, 0);
            click(player);
            return;
        }
        if (slot == AchievementsLayout.PENDING) {
            if (collectionsGui != null) {
                collectionsGui.openPending(player, 0);
                click(player);
            }
            return;
        }
        final int index = GuiGrid.indexAt(slot);
        final List<AchievementCategory> categories = config.categories();
        if (index >= 0 && index < categories.size()) {
            gui.openCategory(player, categories.get(index), 0);
            click(player);
        }
    }

    private void paged(final Player player, final AchievementsHolder holder, final int slot,
                       final List<Achievement> list) {
        final boolean secrets = holder.view() == AchievementsHolder.View.SECRETS;
        if (slot == AchievementsLayout.BACK) {
            gui.openRoot(player);
            return;
        }
        if (slot == AchievementsLayout.EXTRA && !secrets) {
            if (collectionsGui != null) {
                collectionsGui.openPending(player, 0);
                click(player);
            }
            return;
        }
        if (slot == AchievementsLayout.PREVIOUS && holder.page() > 0) {
            reopen(player, holder, holder.page() - 1);
            return;
        }
        if (slot == AchievementsLayout.NEXT && holder.page() + 1 < GuiGrid.pages(list.size())) {
            reopen(player, holder, holder.page() + 1);
            return;
        }
        final int index = GuiGrid.indexAt(slot);
        if (index < 0) {
            return;
        }
        final int achievementIndex = GuiGrid.offset(holder.page(), GuiGrid.PER_PAGE) + index;
        if (achievementIndex >= list.size()) {
            return;
        }
        claim(player, list.get(achievementIndex));
        reopen(player, holder, holder.page());
    }

    private void reopen(final Player player, final AchievementsHolder holder, final int page) {
        if (holder.view() == AchievementsHolder.View.SECRETS) {
            gui.openSecrets(player, page);
        } else {
            gui.openCategory(player, holder.category(), page);
        }
    }

    private void claim(final Player player, final Achievement achievement) {
        if (achievements.hidden(player.getUniqueId(), achievement)) {
            deny(player);
            return;
        }
        final ClaimResult result = achievements.claim(player.getUniqueId(), achievement.id());
        if (messages == null) {
            return;
        }
        final Map<String, String> placeholders = Map.of("achievement", achievement.display());
        switch (result) {
            case CLAIMED -> {
                messages.sendPrefixed(player, "achievements.claimed", placeholders);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
            case PENDING -> messages.sendPrefixed(player, "achievements.claim-held", placeholders);
            case ALREADY_CLAIMED ->
                    messages.sendPrefixed(player, "achievements.claim-already", placeholders);
            case NOT_REACHED -> deny(player);
            default -> { }
        }
    }

    private List<Achievement> secrets() {
        final List<Achievement> secrets = new ArrayList<>();
        for (final Achievement achievement : config.all()) {
            if (achievement.secret()) {
                secrets.add(achievement);
            }
        }
        return secrets;
    }

    private void click(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    private void deny(final Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
    }
}
