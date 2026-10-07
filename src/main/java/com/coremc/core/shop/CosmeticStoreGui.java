package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.chat.ChatStyle;
import com.coremc.core.chat.TagDefinition;
import com.coremc.core.cosmetic.Skin;
import com.coremc.core.cosmetic.SkinType;
import com.coremc.core.economy.Currency;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Config-priced unlocks for the plugin's existing cosmetic catalogues. */
public final class CosmeticStoreGui implements Gui {
    private enum Category { MENU, TOOL_SKIN, HAT, TAG, CHAT_STYLE }
    private static final int[] ITEMS = {
        10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34
    };
    private record Offer(String id, String name, Material icon, String detail, boolean owned) {}

    private final CoreMCPlugin plugin;
    private final Category category;
    private final int page;

    public CosmeticStoreGui(final CoreMCPlugin plugin) {
        this(plugin, Category.MENU, 0);
    }

    private CosmeticStoreGui(final CoreMCPlugin plugin, final Category category, final int page) {
        this.plugin = plugin;
        this.category = category;
        this.page = Math.max(0, page);
    }

    @Override public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &f" + (category == Category.MENU ? "Cosmetics Store" : category.name().replace('_', ' ')));
    }
    @Override public int size() { return 54; }

    @Override public void build(final Player viewer, final Inventory inventory) {
        for (int slot = 0; slot < size(); slot++) inventory.setItem(slot,
                GuiService.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        if (category == Category.MENU) {
            inventory.setItem(4, GuiService.item(Material.GOLD_INGOT, "&6&lCOSMETIC STORE",
                    List.of("&7Cosmetic unlocks use Credits.", "&7No gameplay stats are included.")));
            inventory.setItem(20, GuiService.item(Material.NETHERITE_PICKAXE, "&bTool Skins", List.of("&eClick to browse.")));
            inventory.setItem(22, GuiService.item(Material.CARVED_PUMPKIN, "&6Hats", List.of("&eClick to browse.")));
            inventory.setItem(24, GuiService.item(Material.NAME_TAG, "&dChat Tags", List.of("&eClick to browse.")));
            inventory.setItem(31, GuiService.item(Material.PAPER, "&aChat Styles", List.of("&eClick to browse.")));
        } else {
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile != null) {
                final List<Offer> offers = offers(viewer, profile);
                final int from = page * ITEMS.length;
                final int to = Math.min(from + ITEMS.length, offers.size());
                inventory.setItem(4, GuiService.item(Material.GOLD_INGOT, "&6&lYOUR CREDITS",
                        List.of("&7Balance: &f" + profile.credits(), "&7Unlocks are permanent.")));
                for (int i = from; i < to; i++) {
                    final Offer offer = offers.get(i);
                    final long price = price();
                    final List<String> lore = new ArrayList<>();
                    if (!offer.detail().isBlank()) lore.add(ColorUtil.colorize("&7" + offer.detail()));
                    lore.add("");
                    lore.add(ColorUtil.colorize(offer.owned() ? "&a&l✔ Owned" : "&7&lPrice: &f&l" + price + " Credits"));
                    lore.add(ColorUtil.colorize(offer.owned() ? "&7Already unlocked." : "&e&lClick to unlock."));
                    inventory.setItem(ITEMS[i - from], GuiService.item(offer.icon(), offer.name(), lore));
                }
                if (page > 0) inventory.setItem(48, GuiService.item(Material.ARROW, "&ePrevious page", List.of()));
                if (to < offers.size()) inventory.setItem(50, GuiService.item(Material.ARROW, "&eNext page", List.of()));
            }
        }
        inventory.setItem(45, GuiService.item(Material.ARROW, "&eBack", List.of()));
        inventory.setItem(49, GuiService.item(Material.BARRIER, "&cClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    private List<Offer> offers(final Player viewer, final PlayerProfile profile) {
        final List<Offer> result = new ArrayList<>();
        switch (category) {
            case TOOL_SKIN, HAT -> plugin.skins().catalog().all().stream()
                    .filter(skin -> skin.type() == (category == Category.HAT ? SkinType.HAT : SkinType.TOOL))
                    .sorted(Comparator.comparing(Skin::id))
                    .forEach(skin -> result.add(new Offer(skin.id(), skin.display(), skin.material(),
                            skin.description(), profile.ownsSkin(skin.id()))));
            case TAG -> plugin.tags().all().stream()
                    .sorted(Comparator.comparing(TagDefinition::id))
                    .forEach(tag -> result.add(new Offer(tag.id(), tag.nameOrDisplay(),
                            Material.matchMaterial(tag.material()) == null ? Material.NAME_TAG : Material.matchMaterial(tag.material()),
                            tag.display(), plugin.tags().owns(viewer, profile, tag))));
            case CHAT_STYLE -> plugin.chatStyles().catalog().all().stream()
                    .sorted(Comparator.comparing(ChatStyle::id))
                    .forEach(style -> result.add(new Offer(style.id(), style.display(),
                            Material.matchMaterial(style.material()) == null ? Material.PAPER : Material.matchMaterial(style.material()),
                            style.gradient() ? style.fromHex() + " → " + style.toHex() : style.colour(),
                            plugin.chatStyles().owns(viewer, profile, style))));
            default -> { }
        }
        return result;
    }

    private long price() {
        final String key = switch (category) {
            case TOOL_SKIN -> "tool-skin";
            case HAT -> "hat";
            case TAG -> "tag";
            case CHAT_STYLE -> "chat-style";
            default -> "tool-skin";
        };
        return Math.max(0L, plugin.getConfig().getLong("store.cosmetic-prices." + key, 5000L));
    }

    @Override public boolean onClick(final Player viewer, final int slot) {
        if (slot == 49) { viewer.closeInventory(); return false; }
        if (slot == 45) {
            if (category == Category.MENU) plugin.gui().open(viewer, new StoreGui(plugin));
            else plugin.gui().open(viewer, new CosmeticStoreGui(plugin));
            return false;
        }
        if (category == Category.MENU) {
            final Category selected = switch (slot) {
                case 20 -> Category.TOOL_SKIN;
                case 22 -> Category.HAT;
                case 24 -> Category.TAG;
                case 31 -> Category.CHAT_STYLE;
                default -> null;
            };
            if (selected != null) plugin.gui().open(viewer, new CosmeticStoreGui(plugin, selected, 0));
            return false;
        }
        if (slot == 48 && page > 0) { plugin.gui().open(viewer, new CosmeticStoreGui(plugin, category, page - 1)); return false; }
        if (slot == 50) { plugin.gui().open(viewer, new CosmeticStoreGui(plugin, category, page + 1)); return false; }
        final int index = java.util.Arrays.binarySearch(ITEMS, slot);
        if (index < 0) return false;
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) return false;
        final List<Offer> offers = offers(viewer, profile);
        final int offerIndex = page * ITEMS.length + index;
        if (offerIndex >= offers.size()) return false;
        final Offer offer = offers.get(offerIndex);
        if (offer.owned()) {
            plugin.messages().sendPrefixed(viewer, "shop.unavailable", java.util.Map.of());
            return false;
        }
        final long cost = price();
        if (!plugin.economy().withdraw(profile, Currency.CREDITS, cost)) {
            plugin.messages().sendPrefixed(viewer, "shop.insufficient",
                    java.util.Map.of("price", String.valueOf(cost), "currency", "Credits"));
            return false;
        }
        final boolean granted = switch (category) {
            case TOOL_SKIN, HAT -> plugin.skins().grant(viewer.getUniqueId(), offer.id())
                    .map(com.coremc.core.cosmetic.SkinService.HookResult::success).orElse(false);
            case TAG -> plugin.tags().grant(viewer, offer.id());
            case CHAT_STYLE -> plugin.chatStyles().grant(viewer, offer.id());
            default -> false;
        };
        if (!granted) {
            plugin.economy().deposit(profile, Currency.CREDITS, cost);
            plugin.messages().sendPrefixed(viewer, "shop.unavailable", java.util.Map.of());
            return false;
        }
        plugin.messages().sendPrefixed(viewer, "store.purchased",
                java.util.Map.of("item", ColorUtil.colorize(offer.name()), "price", String.valueOf(cost)));
        plugin.gui().open(viewer, new CosmeticStoreGui(plugin, category, page));
        return false;
    }
}
