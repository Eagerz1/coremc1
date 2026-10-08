package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Player-facing, paginated view of the season island-level track. */
public final class IslandProgressionGui implements Gui {

    private static final int[] LEVEL_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };
    private static final int LAST_PAGE = 1;

    private final CoreMCPlugin plugin;
    private int page;

    public IslandProgressionGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&3&lCOREMC &8» &fIsland Progression";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final Island island = plugin.islands().islandOf(viewer.getUniqueId()).orElse(null);
        final long divisor = plugin.getConfig().getLong("island.level.xp-divisor", 100L);
        final int currentLevel = island == null ? 0 : plugin.islandProgress().levelFor(island);
        final long score = island == null ? 0L : plugin.islandProgress().scoreFor(island);
        if (island == null) {
            inventory.setItem(4, GuiService.item(Material.BOOK, "&bIsland Progression",
                    List.of("&7Create or join an island to", "&7start the 30-level season track.")));
        } else {
            final long currentRequirement = IslandLevelTrack.level(currentLevel, divisor)
                    .orElseThrow().requiredScore();
            inventory.setItem(4, GuiService.item(Material.NETHER_STAR, "&b&lISLAND PROGRESSION",
                    List.of(
                            "&7Current level: &f" + currentLevel + " &8» &b"
                                    + IslandLevelTrack.level(currentLevel, divisor).orElseThrow().title(),
                            "&7Season score: &f" + score,
                            currentLevel >= IslandLevelTrack.MAX_LEVEL
                                    ? "&aSeason track complete"
                                    : progressLine(score, currentRequirement,
                                            IslandLevelTrack.level(currentLevel + 1, divisor)
                                                    .orElseThrow().requiredScore()))));
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != 4) {
                inventory.setItem(slot, GuiService.item(Material.CYAN_STAINED_GLASS_PANE, "&8✦", List.of()));
            }
        }

        final List<IslandLevelTrack.Level> levels = IslandLevelTrack.levels(divisor);
        final int start = page * LEVEL_SLOTS.length;
        for (int i = 0; i < LEVEL_SLOTS.length; i++) {
            final int index = start + i;
            if (index >= levels.size()) {
                break;
            }
            final IslandLevelTrack.Level level = levels.get(index);
            final boolean completed = currentLevel > level.level();
            final boolean current = currentLevel == level.level();
            final Material icon = completed ? Material.LIME_DYE : current
                    ? Material.EXPERIENCE_BOTTLE : Material.GRAY_DYE;
            final List<String> lore = new ArrayList<>();
            lore.add("&7Required score: &f" + level.requiredScore());
            lore.add("");
            if (level.unlocks().isEmpty()) {
                lore.add("&7Keep building your island score.");
            } else {
                lore.add("&7Track milestones:");
                for (final String unlock : level.unlocks()) {
                    lore.add("&b• &f" + displayName(unlock));
                }
            }
            lore.add("");
            lore.add(completed ? "&aCompleted" : current ? "&eCurrent level" : "&8Locked");
            inventory.setItem(LEVEL_SLOTS[i],
                    GuiService.item(icon, "&bLevel " + level.level() + " &8» &f" + level.title(), lore));
        }

        inventory.setItem(45, GuiService.item(
                Material.ARROW, page == 0 ? "&8First page" : "&ePrevious page", List.of()));
        inventory.setItem(49, GuiService.item(
                Material.PAPER, "&bPage " + (page + 1) + "&7/&b2",
                List.of("&7Twenty-one levels per page.")));
        inventory.setItem(50, GuiService.item(
                Material.BARRIER, "&cBack to island", List.of("&7Return to the island menu.")));
        inventory.setItem(53, GuiService.item(
                Material.ARROW, page == LAST_PAGE ? "&8Last page" : "&eNext page", List.of()));
        for (int slot = 45; slot < 54; slot++) {
            if (slot != 45 && slot != 49 && slot != 50 && slot != 53) {
                inventory.setItem(slot,
                        GuiService.item(Material.BLUE_STAINED_GLASS_PANE, "&8✦", List.of()));
            }
        }
        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == 45 && page > 0) {
            page--;
            return true;
        }
        if (slot == 53 && page < LAST_PAGE) {
            page++;
            return true;
        }
        if (slot == 50) {
            plugin.gui().open(viewer, new IslandMainGui(plugin));
        }
        return false;
    }

    private static String progressLine(final long score, final long from, final long to) {
        final long span = Math.max(1L, to - from);
        final long progress = Math.max(0L, Math.min(span, score - from));
        final int filled = (int) Math.min(10L, Math.round(progress * 10.0 / span));
        return "&7Next level: &f" + Math.max(0L, to - score) + " score &8["
                + "&a" + "|".repeat(filled) + "&7" + "|".repeat(10 - filled) + "&8]";
    }

    private static String displayName(final String key) {
        return switch (key) {
            case "generator-tier-2" -> "Generator tier 2";
            case "generator-tier-3" -> "Generator tier 3";
            case "generator-tier-4" -> "Generator tier 4";
            case "generator-tier-5" -> "Generator tier 5";
            case "slayer-section-2" -> "Slaying section 2";
            case "slayer-section-3" -> "Slaying section 3";
            case "advanced-spawners" -> "Advanced spawners";
            case "equipment-sets" -> "Equipment sets";
            case "advanced-equipment" -> "Advanced equipment";
            case "companions" -> "Companions";
            case "island-mastery" -> "Island mastery";
            case "mastery-rewards" -> "Mastery rewards";
            case "weekly-missions" -> "Weekly missions";
            case "season-cosmetics" -> "Season cosmetics";
            case "season-milestones" -> "Season milestones";
            case "season-finale" -> "Season finale";
            default -> key.replace('-', ' ');
        };
    }
}
