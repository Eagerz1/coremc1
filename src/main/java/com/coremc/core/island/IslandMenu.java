package com.coremc.core.island;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the island menu GUIs. Carries the window state
 * (which sub-menu, current mastery branch, and for the members menu which
 * member is armed for a kick confirmation) so the click controller knows what a clicked
 * slot means — same pattern as the shop's {@code ShopMenu}.
 */
public final class IslandMenu implements InventoryHolder {

    /** Which island-menu window this is. */
    public enum Kind {
        ISLAND, UPGRADES, MASTERY_BRANCH, CORE, CORE_BUFFS, BUFFS, MEMBERS, INVITE
    }

    private final Kind kind;
    /** Optional branch id for a mastery branch window. */
    private final String branchId;
    private Inventory inventory;
    /** Member armed for a kick confirmation (members menu only). */
    private UUID armedKick;
    private long armedKickAt;

    public IslandMenu(final Kind kind) {
        this(kind, null);
    }

    public IslandMenu(final Kind kind, final String branchId) {
        this.kind = kind;
        this.branchId = branchId;
    }

    public Kind kind() {
        return kind;
    }

    public String branchId() {
        return branchId;
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
