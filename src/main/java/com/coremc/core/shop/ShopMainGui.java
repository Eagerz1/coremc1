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
 * {@code /shop} — 27-slot hub.
 *
 *   11 Gear &nbsp; 12 Food &nbsp; 14 Tokens &nbsp; 15 End &nbsp; 16 Nether
 *   22 balances &nbsp; 26 close
 */
public final class ShopMainGui implements Gui {

    private static final int SLOT_GEAR = 11;
    private static final int SLOT_FOOD = 12;
    private static final int SLOT_TOKENS = 14;
    private static final int SLOT_END = 15;
    private static final int SLOT_NETHER = 16;
    private static final int SLOT_BALANCE = 22;
    private static final int SLOT_CLOSE = 26;

    private final CoreMCPlugin plugin;

    public ShopMainGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fShop");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        inventory.setItem(SLOT_GEAR, GuiService.item(
                Material.IRON_CHESTPLATE, "&6Gear", List.of("&7Weapons, armour, tools.")));
        inventory.setItem(SLOT_FOOD, GuiService.item(
                Material.COOKED_BEEF, "&eFood", List.of("&7Food and farming goods.")));
        inventory.setItem(SLOT_TOKENS, GuiService.item(
                Material.SUNFLOWER, "&bToken Exchange", List.of("&7Sky Token packs & conversions.")));
        inventory.setItem(SLOT_END, GuiService.item(
                Material.ENDER_PEARL, "&5End", List.of("&7End-dimension goods.")));
        inventory.setItem(SLOT_NETHER, GuiService.item(
                Material.NETHERRACK, "&cNether", List.of("&7Nether-dimension goods.")));

        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final String money = profile == null ? "0" : String.format(Locale.ROOT, "%,d", profile.money());
        final String credits = profile == null ? "0" : String.format(Locale.ROOT, "%,d", profile.credits());
        final String tokens = profile == null ? "0" : String.format(Locale.ROOT, "%,d", profile.skyTokens());
        inventory.setItem(SLOT_BALANCE, GuiService.item(
                Material.GOLD_INGOT,
                "&6Your Balances",
                List.of("&7Money: &a$" + money,
                        "&7Credits: &a" + credits,
                        "&7Sky Tokens: &b" + tokens)));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final ShopCategory category = switch (slot) {
            case SLOT_GEAR -> ShopCategory.GEAR;
            case SLOT_FOOD -> ShopCategory.FOOD;
            case SLOT_TOKENS -> ShopCategory.TOKENS;
            case SLOT_END -> ShopCategory.END;
            case SLOT_NETHER -> ShopCategory.NETHER;
            default -> null;
        };
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (category != null) {
            if (category == ShopCategory.TOKENS) {
                plugin.gui().open(viewer, new TokenShopGui(plugin));
            } else {
                plugin.gui().open(viewer, new ShopCategoryGui(plugin, category));
            }
        }
        return false;
    }
}
