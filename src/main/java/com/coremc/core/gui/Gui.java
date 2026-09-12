package com.coremc.core.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * A CoreMC GUI panel.
 *
 * Implementations are short-lived and stateless per open: state lives
 * in the domain services, the GUI only renders and routes clicks.
 * GUIs never retain the Player or the Inventory — they are handed in
 * per call, which keeps the listener registry free of leaks.
 */
public interface Gui {

    /** Legacy-coloured inventory title. */
    String title();

    /** Inventory size (multiple of 9, max 54). */
    int size();

    /** Fills the inventory for display to {@code viewer}. */
    void build(Player viewer, Inventory inventory);

    /**
     * Handles a click in {@code slot}. Return true to re-render
     * (the service re-invokes {@link #build}), false to leave as-is.
     * All clicks in CoreMC GUIs are cancelled — items never move.
     */
    default boolean onClick(final Player viewer, final int slot) {
        return false;
    }

    /**
     * Handles a RIGHT-click in {@code slot}. Defaults to the normal
     * click handler so existing GUIs behave exactly as before; panels
     * with a secondary action (enchant details) override this.
     */
    default boolean onRightClick(final Player viewer, final int slot) {
        return onClick(viewer, slot);
    }
}
