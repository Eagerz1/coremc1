package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.crate.CrateDefinition;
import com.coremc.core.crate.CratePreviewGui;
import com.coremc.core.economy.Currency;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.ItemDelivery;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Credit store for crate keys, lootboxes, and bundles. */
public final class StoreGui implements Gui {
    private record Grant(String keyId, int amount) {}
    private record Product(String id, String name, Material icon, long price, String kind, List<Grant> grants) {}
    private static final int[] KEY_SLOTS = {10, 11, 12, 13, 14};
    private static final int[] BOX_SLOTS = {20, 22, 24};
    private static final int[] BUNDLE_SLOTS = {29, 31, 33, 35};
    private static final List<Product> PRODUCTS = List.of(
        new Product("vote", "&eVote Key", Material.TRIPWIRE_HOOK, 75, "key", List.of(new Grant("vote", 1))),
        new Product("river", "&bRiver Key", Material.TRIPWIRE_HOOK, 125, "key", List.of(new Grant("river", 1))),
        new Product("sky", "&bSky Key", Material.TRIPWIRE_HOOK, 250, "key", List.of(new Grant("sky", 1))),
        new Product("crimson", "&cCrimson Key", Material.TRIPWIRE_HOOK, 400, "key", List.of(new Grant("crimson", 1))),
        new Product("boost", "&dBoost Key", Material.TRIPWIRE_HOOK, 300, "key", List.of(new Grant("boost", 1))),
        new Product("box-core", "&bCore Lootbox", Material.ENDER_CHEST, 500, "box", List.of(new Grant("core", 1))),
        new Product("box-monthly", "&dMonthly Lootbox", Material.ENDER_CHEST, 1000, "box", List.of(new Grant("monthly", 1))),
        new Product("box-seasonal", "&6Seasonal Lootbox", Material.ENDER_CHEST, 1500, "box", List.of(new Grant("seasonal", 1))),
        new Product("starter", "&aStarter Bundle", Material.CHEST, 500, "bundle", List.of(new Grant("vote", 1), new Grant("core", 1))),
        new Product("sky-bundle", "&bSky Bundle", Material.CHEST, 1000, "bundle", List.of(new Grant("river", 2), new Grant("sky", 2))),
        new Product("crimson-bundle", "&cCrimson Bundle", Material.CHEST, 1750, "bundle", List.of(new Grant("crimson", 2), new Grant("boost", 2))),
        new Product("season-bundle", "&dSeason Bundle", Material.CHEST, 3000, "bundle", List.of(new Grant("core", 2), new Grant("monthly", 2), new Grant("seasonal", 2)))
    );
    private final CoreMCPlugin plugin;
    public StoreGui(final CoreMCPlugin plugin) { this.plugin = plugin; }
    @Override public String title() { return "&b&lCOREMC &8» &7Credits Store"; }
    @Override public int size() { return 54; }
    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        for (int slot = 0; slot < size(); slot++)
            inventory.setItem(slot, GuiService.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        inventory.setItem(4, GuiService.item(Material.GOLD_INGOT, "&6&lYOUR CREDITS",
                List.of("&7Balance: &f" + (profile == null ? 0 : profile.credits()),
                        "&7Left-click a product to purchase.",
                        "&7Right-click a lootbox to preview rewards.")));
        putProducts(inventory, viewer, KEY_SLOTS, "key");
        putProducts(inventory, viewer, BOX_SLOTS, "box");
        putProducts(inventory, viewer, BUNDLE_SLOTS, "bundle");
        inventory.setItem(40, GuiService.item(Material.NETHER_STAR, "&d&lCOSMETICS",
                List.of("&7Skins, hats, tags and chat styles.", "&eClick to browse.")));
        inventory.setItem(49, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }
    private void putProducts(final Inventory inventory, final Player viewer, final int[] slots, final String kind) {
        int index = 0;
        for (final Product product : PRODUCTS) {
            if (!product.kind().equals(kind) || index >= slots.length || !available(product)) continue;
            final long balance = plugin.playerData().profileOf(viewer.getUniqueId())
                    .map(PlayerProfile::credits).orElse(0L);
            ItemStack icon;
            if (product.kind().equals("key") || product.kind().equals("box")) {
                icon = plugin.keys().mint(product.grants().get(0).keyId(), 1);
            } else {
                icon = new ItemStack(product.icon());
            }
            if (icon == null) icon = new ItemStack(product.icon());
            final var meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ColorUtil.colorize(product.name()));
                final java.util.List<String> lore = new java.util.ArrayList<>();
                if ((product.kind().equals("key") || product.kind().equals("box"))
                        && meta.hasLore() && meta.getLore() != null) lore.addAll(meta.getLore());
                lore.add(ColorUtil.colorize("&7&lPrice: &f&l" + String.format(Locale.ROOT, "%,d", product.price()) + " Credits"));
                lore.add(ColorUtil.colorize("&7&lBalance: &f&l" + String.format(Locale.ROOT, "%,d", balance)));
                lore.add(ColorUtil.colorize(balance >= product.price() ? "&a&l✔ Affordable" : "&c&l✖ Need more Credits"));
                lore.add(ColorUtil.colorize("&e&lLeft-click to buy."));
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            inventory.setItem(slots[index++], icon);
        }
    }
    private boolean available(final Product product) {
        return product.grants().stream().allMatch(grant -> plugin.keys().key(grant.keyId()).isPresent());
    }
    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == 49) { viewer.closeInventory(); return false; }
        if (slot == 40) { plugin.gui().open(viewer, new CosmeticStoreGui(plugin)); return false; }
        final Product product = productAt(slot);
        return product != null && purchase(viewer, product);
    }
    @Override
    public boolean onRightClick(final Player viewer, final int slot) {
        final Product product = productAt(slot);
        if (product == null) return false;
        if (product.kind().equals("bundle")) return false;
        final String keyId = product.grants().get(0).keyId();
        final CrateDefinition crate = plugin.crates().all().stream()
                .filter(candidate -> candidate.keys().contains(keyId)).findFirst().orElse(null);
        if (crate == null) {
            plugin.messages().sendPrefixed(viewer, "store.no-lootbox", java.util.Map.of());
            return false;
        }
        plugin.gui().open(viewer, new CratePreviewGui(plugin, crate.id()));
        return false;
    }
    private Product productAt(final int slot) {
        for (int i = 0; i < KEY_SLOTS.length; i++) if (slot == KEY_SLOTS[i] && i < 5) return PRODUCTS.get(i);
        for (int i = 0; i < BOX_SLOTS.length; i++) if (slot == BOX_SLOTS[i]) return PRODUCTS.get(5 + i);
        for (int i = 0; i < BUNDLE_SLOTS.length; i++) if (slot == BUNDLE_SLOTS[i]) return PRODUCTS.get(8 + i);
        return null;
    }
    private boolean purchase(final Player viewer, final Product product) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null || !available(product)) {
            plugin.messages().sendPrefixed(viewer, "shop.unavailable", java.util.Map.of());
            return false;
        }
        if (!plugin.economy().withdraw(profile, Currency.CREDITS, product.price())) {
            plugin.messages().sendPrefixed(viewer, "shop.insufficient", java.util.Map.of(
                    "price", String.format(Locale.ROOT, "%,d", product.price()), "currency", "Credits"));
            return false;
        }
        final ItemStack[] items = product.grants().stream()
                .map(grant -> plugin.keys().mint(grant.keyId(), grant.amount()))
                .toArray(ItemStack[]::new);
        if (java.util.Arrays.stream(items).anyMatch(java.util.Objects::isNull)
                || !ItemDelivery.deliver(viewer, items)) {
            plugin.economy().deposit(profile, Currency.CREDITS, product.price());
            plugin.messages().sendPrefixed(viewer, "shop.overflow", java.util.Map.of());
            return false;
        }
        plugin.messages().sendPrefixed(viewer, "store.purchased", java.util.Map.of(
                "item", ColorUtil.colorize(product.name()),
                "price", String.format(Locale.ROOT, "%,d", product.price())));
        return true;
    }
}