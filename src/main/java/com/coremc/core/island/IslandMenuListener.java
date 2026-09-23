package com.coremc.core.island;

import com.coremc.core.config.MessageService;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the island menu GUIs. Cancels every
 * interaction that would touch a menu window (items can never be
 * taken out of or moved into a menu), then routes the click by slot:
 * the island menu's buttons, upgrade/buff purchases, the two-click
 * kick confirmation, and invite clicks.
 *
 * All player feedback goes through MessageService and therefore
 * carries the {@code COREMC >>>} prefix.
 */
public final class IslandMenuListener implements Listener {

    /** How long a kick (and the GUI delete) confirmation stays armed. */
    private static final long CONFIRM_WINDOW_MILLIS = 30_000L;

    private final IslandService islands;
    private final IslandGui gui;
    private final IslandUpgradeService upgrades;
    private final IslandUpgradeConfig config;
    private final MessageService messages;

    public IslandMenuListener(final IslandService islands, final IslandGui gui,
                              final IslandUpgradeService upgrades, final IslandUpgradeConfig config,
                              final MessageService messages) {
        this.islands = islands;
        this.gui = gui;
        this.upgrades = upgrades;
        this.config = config;
        this.messages = messages;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IslandMenu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            // Menu contents can never be picked up, swapped or hot-keyed away.
            event.setCancelled(true);
            handleClick(menu, event, player);
        } else if (clicked != null && event.getClick().isShiftClick()) {
            // Player items can never be moved into a menu window.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IslandMenu)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleClick(final IslandMenu menu, final InventoryClickEvent event,
                             final Player player) {
        switch (menu.kind()) {
            case ISLAND -> handleIslandClick(event, player);
            case UPGRADES -> handleUpgradesClick(event, player);
            case BUFFS -> handleBuffsClick(menu, event, player);
            case MEMBERS -> handleMembersClick(menu, event, player);
            case INVITE -> handleInviteClick(event, player);
        }
    }

    // ------------------------------------------------------------------
    // island menu
    // ------------------------------------------------------------------

    private void handleIslandClick(final InventoryClickEvent event, final Player player) {
        final int slot = event.getSlot();
        switch (slot) {
            case IslandLayout.MENU_CLOSE -> {
                player.closeInventory();
                clickSound(player);
            }
            case IslandLayout.MENU_GO_HOME -> {
                clickSound(player);
                player.closeInventory();
                islands.goHome(player);
            }
            case IslandLayout.MENU_INVITE -> {
                clickSound(player);
                gui.openInvite(player);
            }
            case IslandLayout.MENU_MEMBERS -> {
                clickSound(player);
                gui.openMembers(player);
            }
            case IslandLayout.MENU_BORDER -> {
                clickSound(player);
                toggleBorder(player);
            }
            case IslandLayout.MENU_UPGRADES -> {
                clickSound(player);
                gui.openUpgrades(player);
            }
            case IslandLayout.MENU_BUFFS -> {
                clickSound(player);
                gui.openBuffs(player);
            }
            case IslandLayout.MENU_SPAWNERS -> {
                clickSound(player);
                gui.openSpawnerMenu(player);
            }
            case IslandLayout.MENU_DELETE -> {
                clickSound(player);
                // Reuses the /is delete arming: first click arms, the
                // second within the window deletes and evicts everyone.
                islands.delete(player, menuDeleteArmed(player));
                if (islands.islandOf(player.getUniqueId()) == null) {
                    player.closeInventory();
                }
            }
            default -> {
                // info / filler: nothing
            }
        }
    }

    /** Whether the player's delete confirmation is armed (reuses /is delete state). */
    private boolean menuDeleteArmed(final Player player) {
        return islands.deleteArmed(player);
    }

    private void toggleBorder(final Player player) {
        if (player.getWorldBorder() == player.getWorld().getWorldBorder()) {
            final Island island = islands.islandOf(player.getUniqueId());
            if (island != null) {
                islands.applyBorder(player, island);
                messages.sendPrefixed(player, "island.menu.border-shown");
            }
        } else {
            islands.clearBorder(player);
            messages.sendPrefixed(player, "island.menu.border-hidden");
        }
    }

    // ------------------------------------------------------------------
    // upgrades menu
    // ------------------------------------------------------------------

    private void handleUpgradesClick(final InventoryClickEvent event, final Player player) {
        final int slot = event.getSlot();
        if (slot == IslandLayout.SUB_CLOSE) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        if (slot == IslandLayout.SUB_BACK) {
            clickSound(player);
            gui.openIslandMenu(player);
            return;
        }
        if (slot == IslandLayout.UPGRADE_CLAIM) {
            clickSound(player);
            upgrades.buyClaimSize(player);
            gui.openUpgrades(player);
        } else if (slot == IslandLayout.UPGRADE_SLOTS) {
            clickSound(player);
            upgrades.buyMemberSlots(player);
            gui.openUpgrades(player);
        }
    }

    // ------------------------------------------------------------------
    // buffs menu
    // ------------------------------------------------------------------

    private void handleBuffsClick(final IslandMenu menu, final InventoryClickEvent event,
                                  final Player player) {
        final int slot = event.getSlot();
        if (slot == IslandLayout.SUB_CLOSE) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        if (slot == IslandLayout.SUB_BACK) {
            clickSound(player);
            gui.openIslandMenu(player);
            return;
        }
        if (!config.enabled()) {
            return;
        }
        final var buffList = config.buffs();
        for (int i = 0; i < buffList.size() && i < 3; i++) {
            if (IslandLayout.buffSlot(i) == slot) {
                clickSound(player);
                upgrades.buyBuff(player, buffList.get(i).id());
                gui.openBuffs(player);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // members menu (two-click kick)
    // ------------------------------------------------------------------

    private void handleMembersClick(final IslandMenu menu, final InventoryClickEvent event,
                                    final Player player) {
        final int slot = event.getSlot();
        if (slot == IslandLayout.SUB_CLOSE) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        if (slot == IslandLayout.SUB_BACK) {
            clickSound(player);
            gui.openIslandMenu(player);
            return;
        }
        if (slot == IslandLayout.MEMBERS_OWNER) {
            return;
        }
        final int ordinal = slot - IslandLayout.MEMBERS_FIRST;
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null || ordinal < 0 || ordinal >= IslandLayout.MEMBERS_MAX) {
            return;
        }
        final UUID[] memberIds = island.members().toArray(new UUID[0]);
        if (ordinal >= memberIds.length) {
            return;
        }
        final UUID target = memberIds[ordinal];
        if (!island.isOwner(player.getUniqueId())) {
            messages.sendPrefixed(player, "island.not-owner");
            return;
        }
        final UUID armed = menu.armedKick(CONFIRM_WINDOW_MILLIS);
        if (armed != null && armed.equals(target)) {
            clickSound(player);
            final String name = playerName(target);
            islands.kick(player, target, name);
            gui.openMembers(player);
            return;
        }
        menu.armKick(target);
        clickSound(player);
        messages.sendPrefixed(player, "island.kick-confirm", Map.of(
                "player", playerName(target),
                "seconds", String.valueOf(CONFIRM_WINDOW_MILLIS / 1000L)));
    }

    private String playerName(final UUID playerId) {
        final String name = org.bukkit.Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? "member" : name;
    }

    // ------------------------------------------------------------------
    // invite menu
    // ------------------------------------------------------------------

    private void handleInviteClick(final InventoryClickEvent event, final Player player) {
        final int slot = event.getSlot();
        if (slot == IslandLayout.SUB_CLOSE) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        if (slot == IslandLayout.SUB_BACK) {
            clickSound(player);
            gui.openIslandMenu(player);
            return;
        }
        final int ordinal = slot - IslandLayout.INVITE_FIRST;
        if (ordinal < 0 || ordinal >= IslandLayout.INVITE_MAX) {
            return;
        }
        // Candidates were rendered in online-player order; re-resolve the
        // same list so the head always matches a live player.
        final var candidates = new java.util.ArrayList<Player>();
        for (final Player candidate : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (candidate.getUniqueId().equals(player.getUniqueId())
                    || islands.islandOf(candidate.getUniqueId()) != null) {
                continue;
            }
            candidates.add(candidate);
        }
        if (ordinal >= candidates.size()) {
            return;
        }
        clickSound(player);
        islands.invite(player, new String[]{"invite", candidates.get(ordinal).getName()});
        gui.openInvite(player);
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
