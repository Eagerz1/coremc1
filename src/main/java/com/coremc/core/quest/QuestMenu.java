package com.coremc.core.quest;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marker holder for /quests windows. */
public final class QuestMenu implements InventoryHolder {

    public enum Kind {
        ROOT, DAILY, WEEKLY, ISLAND, COMPLETED
    }

    private final Kind kind;
    private Inventory inventory;

    public QuestMenu(final Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
