package com.coremc.core.store;

import com.coremc.core.crate.CrateConfig;
import com.coremc.core.crate.CrateDef;
import com.coremc.core.crate.KeyDef;
import com.coremc.core.lootbox.LootboxDef;
import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.WeightedTable;
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
 * The transparency window: every crate and lootbox shows its full
 * reward pool — display, amount/range, rarity, weight and the exact
 * calculated chance. The odds come from the same {@link WeightedTable}
 * the rolls use, so what is shown is always what is rolled. Odds are
 * never hidden.
 */
public final class PreviewGui {

    private final CrateConfig crates;

    public PreviewGui(final CrateConfig crates) {
        this.crates = crates;
    }

    /** Opens a crate's odds window. */
    public void openCrate(final Player player, final CrateDef crate,
                          final StoreHolder.Page backPage) {
        final PreviewHolder holder = new PreviewHolder(backPage);
        final Inventory inventory = Bukkit.createInventory(holder, 54,
                ColorUtil.colorize("&3&lCOREMC &8— " + GuiText.caps(crate.name())
                        + " &7" + GuiText.caps("odds")));
        holder.inventory(inventory);
        final KeyDef key = crates.key(crate.keyId());
        inventory.setItem(4, GuiItems.item(crate.displayMaterial(),
                GuiText.caps(crate.name()),
                GuiText.line("Opens with", "&f", stripCodes(key == null ? "?" : key.name())),
                GuiText.blank(),
                "&7" + GuiText.caps("One key = one roll from this pool."),
                "&7" + GuiText.caps("All odds are exact.")));
        int slot = 9;
        for (final RewardDef def : crate.pool()) {
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot++, rewardItem(def, crate.rewards()));
        }
        finish(player, inventory);
    }

    /** Opens a lootbox's odds window: normal pool + the guaranteed rare pool. */
    public void openLootbox(final Player player, final LootboxDef box,
                            final StoreHolder.Page backPage) {
        final PreviewHolder holder = new PreviewHolder(backPage);
        final Inventory inventory = Bukkit.createInventory(holder, 54,
                ColorUtil.colorize("&3&lCOREMC &8— " + GuiText.caps(box.name())
                        + " &7" + GuiText.caps("odds")));
        holder.inventory(inventory);
        inventory.setItem(4, GuiItems.item(Material.ENDER_CHEST, GuiText.caps(box.name()),
                "&f8 " + GuiText.caps("rolls from the normal pool"),
                "&d1 " + GuiText.caps("guaranteed roll from the rare pool"),
                GuiText.blank(),
                "&7" + GuiText.caps("All odds are exact.")));
        int slot = 9;
        for (final RewardDef def : box.normalPool()) {
            if (slot >= 27) {
                break;
            }
            inventory.setItem(slot++, rewardItem(def, box.normal()));
        }
        inventory.setItem(31, GuiItems.item(Material.NETHER_STAR,
                "&d&l" + GuiText.caps("Guaranteed rare"),
                "&7" + GuiText.caps("Exactly one of these, every box:")));
        slot = 36;
        for (final RewardDef def : box.rarePool()) {
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot++, rewardItem(def, box.rare()));
        }
        finish(player, inventory);
    }

    private void finish(final Player player, final Inventory inventory) {
        inventory.setItem(StoreLayout.BACK_SLOT, GuiItems.back("store"));
        inventory.setItem(StoreLayout.CLOSE_SLOT, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    /** One pool entry: amount/range, rarity, weight and exact chance. */
    private ItemStack rewardItem(final RewardDef def, final WeightedTable table) {
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Amount", "&f", def.amountText()));
        lore.add(GuiText.line("Rarity", def.rarityColor(), def.rarity()));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Weight", "&f", GuiText.number(def.weight())));
        lore.add(GuiText.value("Chance", "&e", table.chanceText(def)));
        final ItemStack item = GuiItems.item(iconOf(def),
                def.rarityColor() + GuiText.caps(stripCodes(def.display())), lore);
        return def.rare() ? GuiItems.glow(item) : item;
    }

    /** A sensible icon per reward type. */
    private Material iconOf(final RewardDef def) {
        return switch (def.type()) {
            case MONEY -> Material.GOLD_INGOT;
            case SKY_TOKENS -> Material.PRISMARINE_CRYSTALS;
            case CREDITS -> Material.SUNFLOWER;
            case KEY -> keyMaterial(def.id());
            case LOOTBOX -> Material.ENDER_CHEST;
            case ITEM -> def.material() == null ? Material.PAPER : def.material();
            case COMMAND -> Material.PAPER;
        };
    }

    private Material keyMaterial(final String keyId) {
        final KeyDef key = crates.key(keyId);
        return key == null ? Material.TRIPWIRE_HOOK : key.material();
    }

    /** Drops colour codes from a configured display for re-colouring. */
    private static String stripCodes(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '&' && index + 1 < text.length()) {
                index++;
                continue;
            }
            out.append(text.charAt(index));
        }
        return out.toString();
    }
}
