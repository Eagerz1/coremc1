package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The OmniTool panel (shift right-click with the Omni-Tool). Double
 * chest (54) as specified. Every slot is a named constant:
 *
 *   top-left:    selected role + role level progress
 *   top-right:   OmniTool level progress
 *   middle row:  per-role level overview (6 panes)
 *   bottom row:  OmniTool upgrades (Efficient Material / Fortune Coating /
 *                Auto-Smelter) — click to buy the next level with Credits
 *   bottom-centre: close
 */
public final class OmniToolGui implements Gui {

    // ---------------- layout constants ----------------
    private static final int SLOT_ROLE = 19;
    private static final int SLOT_TOOL = 25;
    private static final int SLOT_PROGRESS_MINER = 29;
    private static final int SLOT_PROGRESS_LOGGER = 30;
    private static final int SLOT_PROGRESS_FISHER = 31;
    private static final int SLOT_PROGRESS_SLAYER = 32;
    private static final int SLOT_PROGRESS_FARMER = 33;
    private static final int SLOT_PROGRESS_UNIVERSAL = 42;
    private static final int SLOT_UPGRADE_1 = 47;
    private static final int SLOT_UPGRADE_2 = 49;
    private static final int SLOT_UPGRADE_3 = 51;
    private static final int SLOT_CLOSE = 53;
    // ----------------------------------------------------

    private final CoreMCPlugin plugin;

    public OmniToolGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lOMNI-TOOL &8— &7Panel";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final Role current = plugin.roles().roleOf(profile).orElse(null);

        inventory.setItem(
                SLOT_ROLE,
                GuiService.item(
                        current == null ? Material.GRAY_DYE : current.icon(),
                        "&eSelected role: " + (current == null ? "&7none" : current.display()),
                        current == null ? List.of("&7Choose one with &f/role") : progressLore(profile, current)));

        final RoleService.ProgressView tool = plugin.roles().toolView(profile);
        inventory.setItem(
                SLOT_TOOL,
                GuiService.item(
                        Material.NETHERITE_PICKAXE,
                        "&bOmniTool level: &f" + tool.level() + (tool.maxed() ? " &8(MAX)" : ""),
                        List.of(
                                "&7XP: &f" + tool.xp() + (tool.maxed() ? "" : "&7/&f" + tool.xpToNext()),
                                "&7Progress: " + bar(tool))));

        progressPane(inventory, profile, current, Role.MINER, SLOT_PROGRESS_MINER);
        progressPane(inventory, profile, current, Role.LOGGER, SLOT_PROGRESS_LOGGER);
        progressPane(inventory, profile, current, Role.FISHER, SLOT_PROGRESS_FISHER);
        progressPane(inventory, profile, current, Role.SLAYER, SLOT_PROGRESS_SLAYER);
        progressPane(inventory, profile, current, Role.FARMER, SLOT_PROGRESS_FARMER);
        progressPane(inventory, profile, current, Role.UNIVERSAL, SLOT_PROGRESS_UNIVERSAL);

        upgradeEntry(inventory, profile, SLOT_UPGRADE_1, OmniUpgradeCatalog.EFFICIENCY);
        upgradeEntry(inventory, profile, SLOT_UPGRADE_2, OmniUpgradeCatalog.FORTUNE);
        upgradeEntry(inventory, profile, SLOT_UPGRADE_3, OmniUpgradeCatalog.SMELTER);

        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&cClose", List.of()));
    }

    private List<String> progressLore(final PlayerProfile profile, final Role role) {
        final RoleService.ProgressView view = plugin.roles().roleView(profile, role);
        final List<String> lore = new ArrayList<>();
        lore.add("&7Level: &f" + view.level() + (view.maxed() ? " &8(MAX)" : ""));
        lore.add("&7XP: &f" + view.xp() + (view.maxed() ? "" : "&7/&f" + view.xpToNext()));
        lore.add("&7Progress: " + bar(view));
        return lore;
    }

    private void progressPane(
            final Inventory inventory,
            final PlayerProfile profile,
            final Role current,
            final Role role,
            final int slot) {
        final RoleService.ProgressView view = plugin.roles().roleView(profile, role);
        inventory.setItem(
                slot,
                GuiService.item(
                        role.icon(),
                        role.display() + " &7lvl &f" + view.level(),
                        List.of(
                                role == current ? "&a(selected)" : "&8not selected",
                                "&7XP: &f" + view.xp() + (view.maxed() ? "" : "&7/&f" + view.xpToNext()))));
    }

    /** Per-upgrade pane: icon + purchased level + next-level price (or MAXED). */
    private void upgradeEntry(
            final Inventory inventory, final PlayerProfile profile, final int slot, final String upgradeId) {
        final var found = plugin.omniTool().upgrades().upgrade(upgradeId);
        if (found.isEmpty()) {
            inventory.setItem(slot, GuiService.item(
                    Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8Upgrade", List.of("&7Not configured.")));
            return;
        }
        final OmniUpgradeCatalog.Upgrade def = found.get();
        final int level = profile.omniUpgrade(upgradeId);
        final Material icon = switch (upgradeId) {
            case OmniUpgradeCatalog.FORTUNE -> Material.AMETHYST_CLUSTER;
            case OmniUpgradeCatalog.SMELTER -> Material.BLAZE_ROD;
            default -> Material.GOLDEN_PICKAXE; // efficiency: better material
        };
        final List<String> lore = new ArrayList<>();
        lore.add("&7Level: &b" + level + "&7/&b" + def.maxLevel());
        lore.add("&7Effect: " + effectLine(upgradeId, Math.max(level, 1)));
        if (def.maxed(level)) {
            lore.add("&a&lMAXED OUT");
        } else {
            final long price = def.costForNextLevel(level);
            lore.add("&7Next level: &a" + String.format(java.util.Locale.ROOT, "%,d", price) + " Credits");
            lore.add("&eClick to purchase.");
        }
        inventory.setItem(slot, GuiService.item(icon, def.display(), lore));
    }

    /** Human-readable effect line for an upgrade level (mirrors the listener behaviour). */
    private String effectLine(final String upgradeId, final int level) {
        return switch (upgradeId) {
            case OmniUpgradeCatalog.FORTUNE ->
                "&bExtra ingots on smelted ores &8(avg +" + switch (level) {
                    case 1 -> "0.33";
                    case 2 -> "0.75";
                    default -> "1.20";
                } + "&8)";
            case OmniUpgradeCatalog.SMELTER -> "&bOres drop pre-smelted";
            default -> "&bEfficiency " + level + " mining speed";
        };
    }

    private String bar(final RoleService.ProgressView view) {
        final int segments = 10;
        final int filled = view.maxed()
                ? segments
                : (int) Math.min(
                        segments,
                        Math.floor(segments * (view.xpToNext() <= 0 ? 1.0 : (double) view.xp() / view.xpToNext())));
        return "&a" + "|".repeat(filled) + "&8" + "|".repeat(segments - filled);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        final String upgradeId = switch (slot) {
            case SLOT_UPGRADE_1 -> OmniUpgradeCatalog.EFFICIENCY;
            case SLOT_UPGRADE_2 -> OmniUpgradeCatalog.FORTUNE;
            case SLOT_UPGRADE_3 -> OmniUpgradeCatalog.SMELTER;
            default -> null;
        };
        if (upgradeId != null) {
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile != null) {
                plugin.omniTool().purchaseUpgrade(viewer, profile, upgradeId);
            }
            return true; // re-render: level/price line updates immediately
        }
        return false; // all other panes are display-only
    }
}
