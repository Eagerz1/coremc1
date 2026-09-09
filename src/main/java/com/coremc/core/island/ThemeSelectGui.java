package com.coremc.core.island;

import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Theme selection shown when a player without an island clicks
 * "Create your island" — 54-slot double chest. One button per theme
 * from themes.yml, centred on the middle row — future themes appear
 * here automatically.
 *
 *   20, 22, 24 ...  theme buttons (start 20, step 2)
 *   45              back to the island menu
 *   53              close
 *
 * Clicking a theme creates the island and teleports the player there
 * (same code path as {@code /is create <theme>}).
 */
public final class ThemeSelectGui implements Gui {

    /** Theme buttons start at slot 20 and step by 2 (clear spacing). */
    private static final int SLOT_START = 20;
    private static final int SLOT_STEP = 2;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_GATE = 22;

    private final CoreMCPlugin plugin;

    public ThemeSelectGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &fChoose your Theme";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final List<IslandTheme> themes = plugin.themes().all();
        for (int i = 0; i < themes.size(); i++) {
            final IslandTheme theme = themes.get(i);
            final List<String> lore = new ArrayList<>(theme.description());
            lore.add("");
            lore.add("&eClick to build this island.");
            inventory.setItem(SLOT_START + i * SLOT_STEP, GuiService.item(theme.icon(), theme.display(), lore));
        }
        if (themes.isEmpty()) {
            inventory.setItem(SLOT_GATE, GuiService.item(
                    Material.BARRIER,
                    "&cNo themes configured",
                    List.of("&7Ask an administrator to check themes.yml.")));
        }
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
        final List<IslandTheme> themes = plugin.themes().all();
        for (int i = 0; i < themes.size(); i++) {
            if (slot != SLOT_START + i * SLOT_STEP) {
                continue;
            }
            final IslandTheme theme = themes.get(i);
            viewer.closeInventory();
            final IslandService.CreateResult result =
                    plugin.islands().createIsland(viewer, theme, island -> {
                        plugin.messages().sendPrefixed(viewer, "island.created-themed",
                                Map.of("theme", ColorUtil.colorize(theme.display())));
                        plugin.islands().teleportHome(viewer, island);
                        plugin.messages().sendPrefixed(viewer, "island.teleported", Map.of());
                    });
            switch (result) {
                case ALREADY_ISLAND -> plugin.messages().sendPrefixed(viewer, "island.already-have", Map.of());
                case WORLD_MISSING -> plugin.messages().sendPrefixed(viewer, "island.world-missing", Map.of());
                case CREATED -> { /* handled in callback */ }
            }
            return false;
        }
        return false;
    }
}
