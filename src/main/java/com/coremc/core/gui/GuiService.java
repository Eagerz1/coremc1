package com.coremc.core.gui;

import com.coremc.core.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * GUI runtime: opens panels and dispatches clicks to the bound Gui.
 *
 * Design guarantees:
 *  - every click inside a CoreMC GUI is cancelled (menus are read-only),
 *  - no per-player maps are kept (the InventoryHolder carries the
 *    binding), so there is no cleanup to forget and no leak on quit,
 *  - GUIs are rebuilt after handling clicks when requested.
 */
public final class GuiService implements Listener {

    private final JavaPlugin plugin;

    public GuiService(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(final Player player, final Gui gui) {
        final GuiHolder holder = new GuiHolder(gui);
        final Inventory inventory =
                Bukkit.createInventory(holder, gui.size(), ColorUtil.colorize(gui.title()));
        holder.bind(inventory);
        gui.build(player, inventory);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true); // all CoreMC menus are read-only
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) {
            return; // click in the player inventory below — ignore
        }
        final boolean refresh = event.isRightClick()
                ? holder.gui().onRightClick(player, event.getRawSlot())
                : holder.gui().onClick(player, event.getRawSlot());
        if (refresh) {
            // re-render same inventory
            final Inventory inventory = event.getInventory();
            inventory.clear();
            holder.gui().build(player, inventory);
        }
    }

    /**
     * Theft shield for drag gestures: dropping cursor items INTO a CoreMC
     * menu (top inventory) is always cancelled. Drags confined to the
     * player's own inventory proceed untouched. Without this a client could
     * smuggle an item onto a panel slot and re-take it on the next render.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof GuiHolder)) {
            return;
        }
        final int topSize = event.getInventory().getSize();
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Decorative backdrop: fills every still-empty slot with a blank grey
     * pane. Call at the END of {@code build()} — slots the GUI set are
     * preserved, untouched air slots become readable decoration. Every
     * CoreMC panel uses the same pane so the UI reads as one family.
     */
    /** Total panes used by a full GUI: {@value #MAX_FILL_PANES} (<= player inventory). */
    public static final int MAX_FILL_PANES = 45;

    /** Title for every transparent filler showing the pane budget (lives as an item name). */
    public static final String FILL_TITLE = "&8" + MAX_FILL_PANES + " fill panes (still cheap)";

    /**
     * Background-panes every empty slot with a title that tells the fill
     * budget: a full double chest uses 45 panes, well under a vanilla
     * inventory's worth of items, keeping click visuals consistent without
     * heavy inventory traffic.
     */
    public static void fillGaps(final Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null
                    || inventory.getItem(slot).getType() == org.bukkit.Material.AIR) {
                inventory.setItem(slot, item(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, FILL_TITLE, List.of()));
            }
        }
    }

    /** Item helper for GUI classes: named item stack with lore. */
    public static ItemStack item(
            final org.bukkit.Material material, final String name, final List<String> lore) {
        final ItemStack stack = new ItemStack(material);
        final var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream().map(ColorUtil::colorize).toList());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
