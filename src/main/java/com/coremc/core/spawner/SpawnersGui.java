package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** {@code /spawners} — direct-purchase, paginated regular spawner market. */
public final class SpawnersGui implements Gui {

    private static final int[] LANE_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int SLOT_BALANCE = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final int page;

    public SpawnersGui(final CoreMCPlugin plugin) {
        this(plugin, 0);
    }

    SpawnersGui(final CoreMCPlugin plugin, final int page) {
        this.plugin = plugin;
        this.page = Math.max(0, page);
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fSpawner Market");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<SpawnerDefinition> lanes = plugin.spawners().all();
        final int pages = Math.max(1, (lanes.size() + LANE_SLOTS.length - 1) / LANE_SLOTS.length);
        final int safePage = Math.min(page, pages - 1);
        final int offset = safePage * LANE_SLOTS.length;

        for (int i = 0; i < LANE_SLOTS.length && offset + i < lanes.size(); i++) {
            final SpawnerDefinition mob = lanes.get(offset + i);
            if (mob.tiers().isEmpty()) {
                continue;
            }
            final SpawnerTier spawner = mob.tiers().get(0);
            final long kills = profile == null ? 0L : plugin.spawners().killsOf(profile, mob);
            final boolean unlocked = profile != null && kills >= spawner.requiredKills();
            final boolean affordable = unlocked && profile.skyTokens() >= spawner.priceSkyTokens();
            final long remaining = Math.max(0L, spawner.requiredKills() - kills);
            final String price = String.format(Locale.ROOT, "%,d", spawner.priceSkyTokens());
            final List<String> lore = new ArrayList<>();
            lore.add("&8Regular Spawner");
            lore.add("");
            lore.add("&7Kill progress: &f" + String.format(Locale.ROOT, "%,d", kills)
                    + "&7/&f" + String.format(Locale.ROOT, "%,d", spawner.requiredKills()));
            lore.add(unlocked
                    ? "&a✔ Kill requirement complete"
                    : "&c✖ " + String.format(Locale.ROOT, "%,d", remaining) + " more kills required");
            lore.add("&7Price: &b" + price + " Sky Tokens");
            lore.add(affordable
                    ? "&a✔ You can afford this"
                    : unlocked ? "&c✖ Not enough Sky Tokens" : "&c✖ Complete the kill requirement first");
            lore.add("");
            lore.add(unlocked && affordable ? "&eClick to purchase." : "&8Locked");
            final String status = unlocked && affordable ? "&a✔ " : "&c✖ ";
            inventory.setItem(LANE_SLOTS[i], GuiService.item(
                    mob.icon(), status + mob.display() + " &7Spawner", lore));
        }

        final long balance = profile == null ? 0L : profile.skyTokens();
        inventory.setItem(SLOT_BALANCE, GuiService.item(Material.NETHER_STAR, "&bYour Sky Tokens",
                List.of("&7Balance: &b" + String.format(Locale.ROOT, "%,d", balance),
                        "&7Kill mobs to unlock spawners.")));
        if (safePage > 0) {
            inventory.setItem(SLOT_PREVIOUS, GuiService.item(Material.ARROW, "&ePrevious Page",
                    List.of("&7Page " + safePage + " of " + pages)));
        }
        inventory.setItem(SLOT_PAGE, GuiService.item(Material.PAPER,
                "&fPage " + (safePage + 1) + "&7/&f" + pages,
                List.of("&7" + lanes.size() + " regular spawners.")));
        if (safePage + 1 < pages) {
            inventory.setItem(SLOT_NEXT, GuiService.item(Material.ARROW, "&eNext Page",
                    List.of("&7Page " + (safePage + 2) + " of " + pages)));
        }
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        final List<SpawnerDefinition> lanes = plugin.spawners().all();
        final int pages = Math.max(1, (lanes.size() + LANE_SLOTS.length - 1) / LANE_SLOTS.length);
        final int safePage = Math.min(page, pages - 1);
        if (slot == SLOT_PREVIOUS && safePage > 0) {
            plugin.gui().open(viewer, new SpawnersGui(plugin, safePage - 1));
            return false;
        }
        if (slot == SLOT_NEXT && safePage + 1 < pages) {
            plugin.gui().open(viewer, new SpawnersGui(plugin, safePage + 1));
            return false;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        final int offset = safePage * LANE_SLOTS.length;
        for (int i = 0; i < LANE_SLOTS.length; i++) {
            if (slot != LANE_SLOTS[i] || offset + i >= lanes.size()) {
                continue;
            }
            final SpawnerDefinition mob = lanes.get(offset + i);
            if (!mob.tiers().isEmpty()) {
                plugin.spawners().tierFor(mob.tiers().get(0).tierId())
                        .ifPresent(ref -> plugin.spawners().buy(viewer, profile, ref));
            }
            return true;
        }
        return false;
    }
}
