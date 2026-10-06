package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.role.Role;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** First page of /skins: choose one of the six Omni-Tools. */
public final class SkinsToolSelectGui implements Gui {
    private static final int[] SLOTS = {20, 22, 24, 29, 31, 33};
    private static final int SLOT_CLOSE = 53;
    private final CoreMCPlugin plugin;

    public SkinsToolSelectGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &7Choose a Tool";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        for (int slot = 0; slot < size(); slot++) {
            inventory.setItem(slot, GuiService.item(
                    Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        inventory.setItem(4, GuiService.item(Material.BOOK, "&b&lCHOOSE AN OMNITOOL",
                List.of("&7Choose a tool to browse its skins.")));
        final var profile = plugin.playerData()
                .profileOf(viewer.getUniqueId()).orElse(null);
        final Role[] roles = Role.values();
        for (int i = 0; i < roles.length; i++) {
            final Role role = roles[i];
            final long owned = plugin.skins().toolSkins(null, role).stream()
                    .filter(skin -> profile != null && profile.ownsSkin(skin.id()))
                    .count();
            final int count = plugin.skins().toolSkins(null, role).size();
            inventory.setItem(SLOTS[i], GuiService.item(
                    role.toolMaterial(), role.display(),
                    List.of("&7Owned skins: &f" + owned + "&7/&f" + count,
                            "&eClick to browse this tool.")));
        }
        inventory.setItem(49, GuiService.item(Material.PAPER, "&7Skin collection",
                List.of("&7Select a tool to see its available skins.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(
                Material.BARRIER, "&c&lClose", List.of()));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        final Role[] roles = Role.values();
        for (int i = 0; i < SLOTS.length; i++) {
            if (slot == SLOTS[i]) {
                plugin.gui().open(viewer, new SkinsGui(plugin, roles[i]));
                return false;
            }
        }
        return false;
    }
}
