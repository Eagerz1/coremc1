package com.coremc.core.shop;

import com.coremc.core.config.MessageService;
import com.coremc.core.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The /sell window: an empty double chest. Players drop items in at
 * their leisure; closing the window pays them ({@link SellListener}).
 * Every slot is free — no filler, no nav — so a full load of 54 stacks
 * fits.
 */
public final class SellGui {

    /** The sell window is a double chest. */
    public static final int SIZE = 54;

    private static final String TITLE = "&3&lCOREMC &8— &bSell";

    private final MessageService messages;

    public SellGui(final MessageService messages) {
        this.messages = messages;
    }

    /** Opens the sell window (a hint line explains how it works). */
    public void open(final Player player) {
        final SellMenuHolder holder = new SellMenuHolder();
        final Inventory inventory = Bukkit.createInventory(holder, SIZE,
                ColorUtil.colorize(TITLE));
        holder.inventory(inventory);
        player.openInventory(inventory);
        messages.sendPrefixed(player, "sell.gui-open");
    }
}
