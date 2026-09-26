package com.coremc.core.gens;

import com.coremc.core.shop.EconomyService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The generator management window (small chest, 27 slots) opened by
 * right-clicking a placed generator:
 *
 * <pre>
 * [11] upgrade   [13] the generator itself   [15] pick up   [22] close
 * </pre>
 *
 * Every line re-renders from live state on each open, so the stack
 * count, the payout and the ✔ / ✖ upgrade cost always tell the truth.
 */
public final class GeneratorManageGui {

    private static final String TITLE = "&3&lCOREMC &8— &bGenerator";

    private final GeneratorService generators;
    private final EconomyService economy;

    public GeneratorManageGui(final GeneratorService generators, final EconomyService economy) {
        this.generators = generators;
        this.economy = economy;
    }

    /** Opens (or refreshes) the management window for one placed generator. */
    public void open(final Player player, final GeneratorEntry entry) {
        final GeneratorTier tier = generators.config().byId(entry.genId());
        if (tier == null) {
            return;
        }
        final GeneratorManageHolder holder = new GeneratorManageHolder(entry.key());
        final Inventory inventory = Bukkit.createInventory(holder, GensLayout.MANAGE_SIZE,
                ColorUtil.colorize(TITLE));
        holder.inventory(inventory);

        inventory.setItem(GensLayout.MANAGE_INFO, infoItem(entry, tier));
        inventory.setItem(GensLayout.MANAGE_UPGRADE, upgradeItem(player, entry, tier));
        inventory.setItem(GensLayout.MANAGE_PICKUP, pickupItem(player, entry, tier));
        inventory.setItem(GensLayout.MANAGE_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory);

        player.openInventory(inventory);
    }

    private ItemStack infoItem(final GeneratorEntry entry, final GeneratorTier tier) {
        final List<String> lore = new ArrayList<>(GeneratorLore.manage(tier, entry.amount(),
                generators.ownerName(entry), generators.islandName(entry)));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Next payout", "&e",
                GuiText.seconds(generators.secondsUntilNext(entry))));
        return GuiItems.item(tier.icon(), GeneratorLore.title(tier, entry.amount()), lore);
    }

    private ItemStack upgradeItem(final Player player, final GeneratorEntry entry,
                                  final GeneratorTier tier) {
        final GeneratorTier next = generators.config().next(tier);
        if (next == null) {
            return GuiItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    "&8&l" + GuiText.caps("Upgrade Generator"),
                    GeneratorLore.upgrade(tier, null, entry.amount(), 0, false, false));
        }
        final double cost = tier.upgradeCost() * entry.amount();
        final boolean affordable = economy != null && economy.has(player.getUniqueId(), cost);
        final boolean unlocked = generators.unlocked(generators.islandOf(entry), next);
        final List<String> lore = GeneratorLore.upgrade(tier, next, entry.amount(), cost,
                affordable, unlocked);
        final ItemStack item = GuiItems.item(unlocked ? next.icon() : Material.GRAY_STAINED_GLASS_PANE,
                (unlocked ? "&e&l" : "&8&l") + GuiText.caps("Upgrade Generator"), lore);
        return unlocked && affordable ? GuiItems.glow(item) : item;
    }

    private ItemStack pickupItem(final Player player, final GeneratorEntry entry,
                                 final GeneratorTier tier) {
        final boolean allowed = generators.mayManage(player, entry);
        return GuiItems.item(allowed ? Material.HOPPER : Material.GRAY_STAINED_GLASS_PANE,
                (allowed ? "&c&l" : "&8&l") + GuiText.caps("Pick Up Generator"),
                GeneratorLore.pickup(tier, entry.amount(), allowed));
    }
}
