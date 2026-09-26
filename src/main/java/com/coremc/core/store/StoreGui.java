package com.coremc.core.store;

import com.coremc.core.credits.CreditService;
import com.coremc.core.crate.CrateConfig;
import com.coremc.core.crate.KeyDef;
import com.coremc.core.lootbox.LootboxConfig;
import com.coremc.core.lootbox.LootboxDef;
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
 * Renders /store: the Credits header, the three categories (Crate
 * Keys, Lootboxes, Bundles) and every purchasable entry with a live
 * ✔ / ✖ Credit price — the CoreMC GUI design language throughout.
 *
 * <p>Clicks are routed by {@link StoreListener}; every buy goes
 * through {@link PurchaseService} so rules and messages never
 * fork.</p>
 */
public final class StoreGui {

    private static final String TITLE_ROOT = "&3&lCOREMC &8— &eStore";
    private static final String TITLE_KEYS = "&3&lCOREMC &8— &aCrate Keys";
    private static final String TITLE_LOOTBOXES = "&3&lCOREMC &8— &bLootboxes";
    private static final String TITLE_BUNDLES = "&3&lCOREMC &8— &6Bundles";

    private final CrateConfig crates;
    private final LootboxConfig lootboxes;
    private final BundleConfig bundles;
    private final CreditService credits;

    public StoreGui(final CrateConfig crates, final LootboxConfig lootboxes,
                    final BundleConfig bundles, final CreditService credits) {
        this.crates = crates;
        this.lootboxes = lootboxes;
        this.bundles = bundles;
        this.credits = credits;
    }

    // ------------------------------------------------------------------
    // pages
    // ------------------------------------------------------------------

    /** Opens the /store root: categories + the Credits panel. */
    public void openRoot(final Player player) {
        final StoreHolder holder = new StoreHolder(StoreHolder.Page.ROOT);
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_ROOT));
        holder.inventory(inventory);

        inventory.setItem(StoreLayout.CREDITS_SLOT, creditsItem(player));
        inventory.setItem(StoreLayout.ROOT_KEYS_SLOT, GuiItems.item(Material.TRIPWIRE_HOOK,
                "&a&l" + GuiText.caps("Crate Keys"),
                "&7" + GuiText.caps("The five CoreMC crate keys:"),
                "&7" + GuiText.caps("Vote, River, Sky, Crimson, Boost."),
                GuiText.blank(),
                GuiText.click("Click to browse")));
        inventory.setItem(StoreLayout.ROOT_LOOTBOXES_SLOT, GuiItems.item(Material.ENDER_CHEST,
                "&b&l" + GuiText.caps("Lootboxes"),
                "&7" + GuiText.caps("Core, Monthly and Seasonal boxes."),
                "&f8 " + GuiText.caps("rewards") + " &7+ &d1 " + GuiText.caps("guaranteed rare"),
                GuiText.blank(),
                GuiText.click("Click to browse")));
        inventory.setItem(StoreLayout.ROOT_BUNDLES_SLOT, GuiItems.item(Material.CHEST,
                "&6&l" + GuiText.caps("Bundles"),
                "&7" + GuiText.caps("Key and lootbox packs with"),
                "&7" + GuiText.caps("a modest, honest discount."),
                GuiText.blank(),
                GuiText.click("Click to browse")));
        inventory.setItem(StoreLayout.INFO_SLOT, earnInfoItem());
        inventory.setItem(StoreLayout.CLOSE_SLOT, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    /** Opens the Crate Keys category. */
    public void openKeys(final Player player) {
        final StoreHolder holder = new StoreHolder(StoreHolder.Page.KEYS);
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_KEYS));
        holder.inventory(inventory);
        inventory.setItem(StoreLayout.CREDITS_SLOT, creditsItem(player));

        final List<KeyDef> all = crates.keys();
        final List<KeyDef> listed = new ArrayList<>();
        for (final KeyDef key : all) {
            if (key.sellable()) {
                listed.add(key);
            }
        }
        final int[] slots = StoreLayout.contentSlots(listed.size());
        for (int index = 0; index < slots.length; index++) {
            final KeyDef key = listed.get(index);
            inventory.setItem(slots[index], keyItem(player, key));
            holder.put(slots[index], new StoreHolder.Entry(StoreHolder.Kind.KEY, key.id()));
        }
        finishPage(player, inventory);
    }

    /** Opens the Lootboxes category. */
    public void openLootboxes(final Player player) {
        final StoreHolder holder = new StoreHolder(StoreHolder.Page.LOOTBOXES);
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_LOOTBOXES));
        holder.inventory(inventory);
        inventory.setItem(StoreLayout.CREDITS_SLOT, creditsItem(player));

        final List<LootboxDef> all = lootboxes.all();
        final int[] slots = StoreLayout.contentSlots(all.size());
        for (int index = 0; index < slots.length; index++) {
            final LootboxDef box = all.get(index);
            inventory.setItem(slots[index], lootboxItem(player, box));
            holder.put(slots[index], new StoreHolder.Entry(StoreHolder.Kind.LOOTBOX, box.id()));
        }
        finishPage(player, inventory);
    }

    /** Opens the Bundles category. */
    public void openBundles(final Player player) {
        final StoreHolder holder = new StoreHolder(StoreHolder.Page.BUNDLES);
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_BUNDLES));
        holder.inventory(inventory);
        inventory.setItem(StoreLayout.CREDITS_SLOT, creditsItem(player));

        final List<BundleDef> all = bundles.all();
        final int[] slots = StoreLayout.contentSlots(all.size());
        for (int index = 0; index < slots.length; index++) {
            final BundleDef bundle = all.get(index);
            inventory.setItem(slots[index], bundleItem(player, bundle));
            holder.put(slots[index], new StoreHolder.Entry(StoreHolder.Kind.BUNDLE, bundle.id()));
        }
        finishPage(player, inventory);
    }

    private void finishPage(final Player player, final Inventory inventory) {
        inventory.setItem(StoreLayout.BACK_SLOT, GuiItems.back("store"));
        inventory.setItem(StoreLayout.INFO_SLOT, earnInfoItem());
        inventory.setItem(StoreLayout.CLOSE_SLOT, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------
    // items
    // ------------------------------------------------------------------

    /** The prominent Credits panel: balance + the 100 = €1 conversion. */
    private ItemStack creditsItem(final Player player) {
        return GuiItems.item(Material.SUNFLOWER,
                "&e&l" + GuiText.caps("Credits"),
                GuiText.value("Balance", "&f",
                        CreditService.format(credits.balance(player.getUniqueId()))),
                GuiText.blank(),
                "&7100 " + GuiText.caps("Credits") + " = &f\u20AC1");
    }

    /** How Credits are earned — every store item is reachable without paying. */
    private ItemStack earnInfoItem() {
        return GuiItems.item(Material.BOOK,
                "&e&l" + GuiText.caps("Earning Credits"),
                "&7" + GuiText.caps("Credits also come from quests,"),
                "&7" + GuiText.caps("island milestones, seasons,"),
                "&7" + GuiText.caps("events and voting."),
                GuiText.blank(),
                "&7" + GuiText.caps("Everything here can be earned."));
    }

    private ItemStack keyItem(final Player player, final KeyDef key) {
        final boolean affordable = credits.has(player.getUniqueId(), key.price());
        final List<String> lore = new ArrayList<>();
        for (final String line : key.description()) {
            lore.add("&7" + GuiText.caps(line));
        }
        lore.add(GuiText.blank());
        lore.add(priceLine(key.price(), affordable));
        lore.add(GuiText.blank());
        lore.add(GuiText.click("Left-click to purchase"));
        lore.add(GuiText.hint("Right-click to preview rewards"));
        final ItemStack item = GuiItems.item(key.material(), GuiText.caps(key.name()), lore);
        return affordable ? GuiItems.glow(item) : item;
    }

    private ItemStack lootboxItem(final Player player, final LootboxDef box) {
        final boolean affordable = credits.has(player.getUniqueId(), box.price());
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Contains:"));
        for (final String category : box.categories()) {
            lore.add("&8- &7" + GuiText.caps(category));
        }
        lore.add(GuiText.blank());
        lore.add("&f8 " + GuiText.caps("rewards") + " &7+ &d1 " + GuiText.caps("guaranteed rare"));
        lore.add(GuiText.blank());
        lore.add(priceLine(box.price(), affordable));
        lore.add(GuiText.blank());
        lore.add(GuiText.click("Left-click to purchase"));
        lore.add(GuiText.hint("Right-click to preview odds"));
        final ItemStack item = GuiItems.item(Material.ENDER_CHEST, GuiText.caps(box.name()), lore);
        return affordable ? GuiItems.glow(item) : item;
    }

    private ItemStack bundleItem(final Player player, final BundleDef bundle) {
        final boolean affordable = credits.has(player.getUniqueId(), bundle.price());
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Contains exactly:"));
        for (final BundleDef.Content content : bundle.contents()) {
            lore.add("&8- &f" + content.amount() + "x " + GuiText.caps(content.display()));
        }
        lore.add(GuiText.blank());
        lore.add(priceLine(bundle.price(), affordable));
        lore.add(GuiText.blank());
        lore.add(GuiText.click("Click to purchase"));
        final ItemStack item = GuiItems.item(bundle.icon(), GuiText.caps(bundle.name()), lore);
        return affordable ? GuiItems.glow(item) : item;
    }

    /** {@code &7ᴘʀɪᴄᴇ: &a250 ᴄʀᴇᴅɪᴛs &a✔} / {@code &7ᴘʀɪᴄᴇ: &c250 ᴄʀᴇᴅɪᴛs &c✖}. */
    public static String priceLine(final long price, final boolean affordable) {
        return GuiText.cost("Price",
                CreditService.format(price) + " " + GuiText.caps("Credits"), affordable);
    }
}
