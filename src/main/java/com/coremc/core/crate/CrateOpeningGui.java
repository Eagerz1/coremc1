package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

/** Short, cancellable lootbox animation; the key is consumed only at the end. */
public final class CrateOpeningGui implements Gui {
    private static final int[] RING = {10, 11, 12, 14, 15, 16, 22, 21, 20, 19};
    private final CoreMCPlugin plugin;
    private final String crateId;
    private final String preferredKeyId;
    private BukkitTask task;
    private int frame;
    public CrateOpeningGui(final CoreMCPlugin plugin, final String crateId) {
        this(plugin, crateId, null);
    }
    public CrateOpeningGui(final CoreMCPlugin plugin, final String crateId, final String preferredKeyId) {
        this.plugin = plugin;
        this.crateId = crateId;
        this.preferredKeyId = preferredKeyId;
    }
    @Override public String title() { return "&b&lLOOTBOX &8» &fOpening..."; }
    @Override public int size() { return 27; }
    @Override
    public void build(final Player viewer, final Inventory inventory) {
        for (int slot = 0; slot < size(); slot++)
            inventory.setItem(slot, GuiService.item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
        inventory.setItem(13, GuiService.item(Material.ENDER_CHEST, "&d&lLootbox",
                List.of("&7The reward is rolling...", "&8Close to cancel safely.")));
    }
    public void start(final Player viewer) {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline() || !ColorUtil.colorize(title()).equals(
                    viewer.getOpenInventory().getTitle())) {
                cancel();
                return;
            }
            final Inventory inventory = viewer.getOpenInventory().getTopInventory();
            for (final int slot : RING)
                inventory.setItem(slot, GuiService.item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
            final int current = RING[frame % RING.length];
            inventory.setItem(current, GuiService.item(Material.ENDER_CHEST,
                    frame % 4 == 0 ? "&d&lLootbox" : "&5Lootbox", List.of("&7Rolling...")));
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f,
                    0.7f + Math.min(frame, 20) * 0.04f);
            frame++;
            if (frame >= 24) {
                cancel();
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1.1f);
                plugin.crates().crate(crateId).ifPresent(crate -> plugin.crates().open(viewer, crate, preferredKeyId));
                if (viewer.isOnline()) viewer.closeInventory();
            }
        }, 0L, 2L);
    }
    private void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
    @Override public boolean onClick(final Player viewer, final int slot) { return false; }
}