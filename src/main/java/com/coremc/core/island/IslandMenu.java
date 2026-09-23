package com.coremc.core.island;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the island menu GUIs. Carries the window state
 * (which sub-menu, and for the members menu which member is armed for
 * a kick confirmation) so the click controller knows what a clicked
 * slot means — same pattern as the shop's {@code ShopMenu}.
 */
public final class IslandMenu implements InventoryHolder {

    /** Which island-menu window this is. */
    public enum Kind {
        ISLAND, UPGRADES, BUFFS, MEMBERS, INVITE
    }

    private final Kind kind;
    private Inventory inventory;
    /** Member armed for a kick confirmation (members menu only). */
    private UUID armedKick;
    private long armedKickAt;

    public IslandMenu(final Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Arms (or disarms) the kick confirmation for a member. */
    public void armKick(final UUID member) {
        this.armedKick = member;
        this.armedKickAt = System.currentTimeMillis();
    }

    /** The armed member if the confirmation is still fresh (within the window), else null. */
    public UUID armedKick(final long windowMillis) {
        if (armedKick == null || System.currentTimeMillis() - armedKickAt > windowMillis) {
            return null;
        }
        return armedKick;
    }

    public void clearArmedKick() {
        this.armedKick = null;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
