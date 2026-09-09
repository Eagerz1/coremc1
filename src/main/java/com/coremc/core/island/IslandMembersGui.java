package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Island members panel ({@code /is} → Members).
 *
 *   13           owner head
 *   28..33,37..42 member heads (capacity 12 — effectively unbounded next to
 *                the configured member cap)
 *   49           invite hint
 *   45           back to the main menu
 *   53           close
 *
 * Owner clicks on a member head KICK that member — a two-click confirm
 * arm (5s window) guards against accidental kicks.
 */
public final class IslandMembersGui implements Gui {

    private static final int SLOT_OWNER = 13;
    private static final int[] MEMBER_SLOTS = {28, 29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42};
    private static final int SLOT_INVITE = 49;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;
    private static final long CONFIRM_MILLIS = 5000L;

    private final CoreMCPlugin plugin;
    /** owner uuid -> (target member, confirm expiry) for the two-click kick. */
    private final Map<UUID, java.util.Map.Entry<UUID, Long>> pendingKicks = new ConcurrentHashMap<>();

    public IslandMembersGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &fIsland Members";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().islandOf(viewer.getUniqueId());
        if (island.isEmpty()) {
            inventory.setItem(SLOT_OWNER, GuiService.item(
                    Material.BARRIER, "&cNo island", List.of("&7Create one with &f/is create")));
            return;
        }
        final Island value = island.get();
        final boolean owner = value.owner().equals(viewer.getUniqueId());

        inventory.setItem(SLOT_OWNER, GuiService.item(
                Material.PLAYER_HEAD,
                "&6Owner &8— &f" + nameOf(value.owner()),
                List.of("&7Full control over the island.")));

        int index = 0;
        for (final UUID member : value.members()) {
            if (index >= MEMBER_SLOTS.length) {
                break;
            }
            final List<String> lore = new ArrayList<>();
            lore.add("&7Island member");
            if (owner) {
                lore.add("");
                lore.add("&cClick twice to kick.");
            }
            inventory.setItem(MEMBER_SLOTS[index++], GuiService.item(
                    Material.PLAYER_HEAD, "&dMember &8— &f" + nameOf(member), lore));
        }
        if (value.members().isEmpty()) {
            inventory.setItem(MEMBER_SLOTS[0], GuiService.item(
                    Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "&8No members yet",
                    List.of("&7Invite friends with &f/is invite <player>&7.")));
        }

        final int capacity = plugin.islands().memberCapacity(value);
        inventory.setItem(SLOT_INVITE, GuiService.item(
                Material.ENDER_EYE,
                "&dInvite",
                List.of(
                        "&7Team: &f" + value.members().size() + "&7/&f" + capacity,
                        "&7/is invite <player>")));
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to the island menu.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new IslandMainGui(plugin));
            return false;
        }
        if (slot == SLOT_INVITE) {
            plugin.messages().sendPrefixed(viewer, "island.invite-hint", Map.of());
            return false;
        }
        final var island = plugin.islands().ownedIsland(viewer.getUniqueId());
        if (island.isEmpty()) {
            return false; // only the owner may kick
        }
        final List<UUID> ordered = new ArrayList<>(island.get().members());
        for (int i = 0; i < ordered.size() && i < MEMBER_SLOTS.length; i++) {
            if (slot != MEMBER_SLOTS[i]) {
                continue;
            }
            final UUID target = ordered.get(i);
            final var pending = pendingKicks.get(viewer.getUniqueId());
            final long now = System.currentTimeMillis();
            if (pending == null || !pending.getKey().equals(target) || pending.getValue() < now) {
                pendingKicks.put(viewer.getUniqueId(), Map.entry(target, now + CONFIRM_MILLIS));
                plugin.messages().sendPrefixed(
                        viewer, "island.kick-confirm", Map.of("player", nameOf(target)));
                return false;
            }
            pendingKicks.remove(viewer.getUniqueId());
            plugin.islands().kick(viewer, target);
            final Player online = Bukkit.getPlayer(target);
            if (online != null) {
                plugin.messages().sendPrefixed(online, "island.kicked.you", Map.of());
            }
            plugin.messages().sendPrefixed(
                    viewer, "island.kicked.owner", Map.of("player", nameOf(target)));
            return true; // re-render without the kicked member
        }
        return false;
    }

    private String nameOf(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        final var offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
    }
}
