package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * {@code /tokenshop} — the Sky Token exchange (54-slot double chest).
 * Entries come from {@code shop.yml tokens:}; slot positions are constants.
 *
 *   20/22/24  exchange entries
 *   40        balance
 *   45        back to the shop hub
 *   53        close
 */
public final class TokenShopGui implements Gui {

    private static final int[] ITEM_SLOTS = {20, 22, 24};
    private static final int SLOT_BALANCE = 40;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;

    public TokenShopGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fToken Exchange");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final List<ShopEntry> entries = plugin.shop().entriesOf(ShopCategory.TOKENS);
        for (int i = 0; i < ITEM_SLOTS.length && i < entries.size(); i++) {
            final ShopEntry entry = entries.get(i);
            inventory.setItem(ITEM_SLOTS[i], GuiService.item(
                    entry.material(),
                    entry.display(),
                    List.of(
                            "&7Gives: &bx" + entry.amount() + " Sky Token" + (entry.amount() == 1 ? "" : "s"),
                            "&7Price: &a" + String.format(Locale.ROOT, "%,d", entry.price())
                                    + " &7" + entry.currency().displayName(),
                            "",
                            "&eClick to exchange.")));
        }
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final String tokens = profile == null ? "0" : String.format(Locale.ROOT, "%,d", profile.skyTokens());
        inventory.setItem(SLOT_BALANCE, GuiService.item(
                Material.NETHER_STAR,
                "&bYour Sky Tokens",
                List.of("&7Balance: &b" + tokens)));
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to the shop hub.")));
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
            plugin.gui().open(viewer, new ShopMainGui(plugin));
            return false;
        }
        final List<ShopEntry> entries = plugin.shop().entriesOf(ShopCategory.TOKENS);
        for (int i = 0; i < ITEM_SLOTS.length && i < entries.size(); i++) {
            if (slot == ITEM_SLOTS[i]) {
                final ShopEntry entry = entries.get(i);
                // the token desk pays out currency, not an item:
                final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
                if (profile == null) {
                    return false;
                }
                final String price = String.format(Locale.ROOT, "%,d", entry.price());
                if (!plugin.economy().withdraw(profile, entry.currency(), entry.price())) {
                    plugin.messages().sendPrefixed(
                            viewer, "shop.insufficient",
                            java.util.Map.of("price", price, "currency", entry.currency().displayName()));
                    return false;
                }
                if (!plugin.economy().fitsDeposit(
                        profile, com.coremc.core.economy.Currency.SKY_TOKENS, entry.amount())) {
                    // Payout would overflow: exact refund (cannot itself overflow —
                    // it restores the pre-withdraw balance) and a clean refusal.
                    plugin.economy().deposit(profile, entry.currency(), entry.price());
                    plugin.messages().sendPrefixed(viewer, "economy.error.too-large", java.util.Map.of());
                    return false;
                }
                plugin.economy().deposit(profile, com.coremc.core.economy.Currency.SKY_TOKENS, entry.amount());
                plugin.messages().sendPrefixed(viewer, "shop.exchange", java.util.Map.of(
                        "bought", entry.amount() + " Sky Token" + (entry.amount() == 1 ? "" : "s"),
                        "price", price,
                        "currency", entry.currency().displayName()));
                return true;
            }
        }
        return false;
    }
}
