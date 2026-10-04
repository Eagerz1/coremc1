package com.coremc.core.companion;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Ownership, purchase, progression and equip surface for companions. */
public final class CompanionsGui implements Gui {

    private static final int[] SLOTS = {10, 12, 14, 16, 29, 33};
    private static final int SLOT_BALANCE = 45;
    private static final int SLOT_UNEQUIP = 49;
    private static final int SLOT_CLOSE = 53;
    private final CoreMCPlugin plugin;

    public CompanionsGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fCompanions");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<CompanionDefinition> all = plugin.companions().all();
        for (int i = 0; i < SLOTS.length && i < all.size(); i++) {
            final CompanionDefinition def = all.get(i);
            final boolean owned = profile != null && plugin.companions().owns(profile, def.id());
            final boolean equipped = owned && def.id().equals(profile.equippedCompanion());
            final boolean affordable = profile != null && profile.skyTokens() >= def.priceTokens();
            final int level = owned ? plugin.companions().level(profile, def.id()) : 1;
            final long xp = owned ? plugin.companions().xp(profile, def.id()) : 0L;
            final List<String> lore = new ArrayList<>();
            lore.add("&7Ability: &a+" + (def.xpBonusPerLevel() * level) + "% "
                    + pretty(def.category()) + " XP");
            lore.add("&7Max level: &f" + def.maxLevel());
            if (!owned) {
                lore.add("");
                lore.add("&7Unlock: &b" + def.priceTokens() + " Sky Tokens");
                lore.add(affordable
                        ? "&a✔ Click to unlock and summon."
                        : "&c✖ You cannot afford this.");
            } else if (level >= def.maxLevel()) {
                lore.add("&7Level: &dMAX");
                lore.add(equipped ? "&a✔ Summoned" : "&eClick to summon.");
            } else {
                lore.add("&7Level: &f" + level + " &8(" + xp + "/"
                        + plugin.companions().xpToNext(level) + " XP)");
                lore.add(equipped ? "&a✔ Summoned" : "&eClick to summon.");
            }
            final String state = owned || affordable ? "&a✔ " : "&c✖ ";
            inventory.setItem(SLOTS[i], GuiService.item(def.icon(), state + def.display(), lore));
        }
        final long tokens = profile == null ? 0L : profile.skyTokens();
        inventory.setItem(SLOT_BALANCE, GuiService.item(Material.NETHER_STAR, "&bYour Sky Tokens",
                List.of("&7Balance: &b" + String.format(java.util.Locale.ROOT, "%,d", tokens))));
        inventory.setItem(SLOT_UNEQUIP, GuiService.item(Material.BARRIER, "&cDismiss Companion",
                List.of("&7Your companion and its ability", "&7remain inactive until summoned.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_UNEQUIP) {
            plugin.companions().unequip(viewer, profile);
            return true;
        }
        final List<CompanionDefinition> all = plugin.companions().all();
        for (int i = 0; i < SLOTS.length && i < all.size(); i++) {
            if (slot != SLOTS[i]) {
                continue;
            }
            final CompanionDefinition def = all.get(i);
            if (plugin.companions().owns(profile, def.id())) {
                plugin.companions().equip(viewer, profile, def.id());
            } else {
                plugin.companions().purchase(viewer, profile, def);
            }
            return true;
        }
        return false;
    }

    private static String pretty(final com.coremc.core.role.RoleCategory category) {
        final String lower = category.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
