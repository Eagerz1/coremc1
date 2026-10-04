package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The Hats section of /skins. Three animated wearable hats with live
 * previews, Apply and Remove.
 *
 * Hats are worn as a client-visible overlay (an ItemDisplay riding the
 * player's head): the real helmet slot and every armour attribute stay
 * untouched — see {@link HatOverlayService} for why that is the only
 * supported cosmetic path in vanilla/Paper.
 */
public final class SkinsHatsGui implements Gui {

    private static final int SLOT_TAB_TOOLS = 0;
    private static final int SLOT_TAB_HATS = 1;
    private static final int SLOT_INFO = 4;
    private static final int[] HAT_SLOTS = {20, 22, 24};
    private static final int SLOT_PREVIEW = 47;
    private static final int SLOT_APPLY = 49;
    private static final int SLOT_REMOVE = 51;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final SkinService skins;
    private String previewSkinId;

    public SkinsHatsGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.skins = plugin.skins();
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &7Hats";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData()
                .profileOf(viewer.getUniqueId()).orElse(null);
        for (int slot = 0; slot < size(); slot++) {
            inventory.setItem(slot, GuiService.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        if (profile == null) {
            inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&cClose", List.of()));
            return;
        }
        inventory.setItem(SLOT_TAB_TOOLS, GuiService.item(
                Material.NETHERITE_PICKAXE, "&b&lTOOL SKINS",
                List.of("&7Animated skins for your Omni-Tool.", "&eClick to open.")));
        inventory.setItem(SLOT_TAB_HATS, GuiService.item(
                Material.CARVED_PUMPKIN, "&d&lHATS &8(you are here)",
                List.of("&7Worn as an overlay — your helmet",
                        "&7and armour stay completely intact.")));
        final boolean wearing = !"none".equals(profile.equippedHat());
        inventory.setItem(SLOT_INFO, GuiService.item(
                Material.BOOK, "&d&lHATS",
                List.of(
                        "&7Owned: &f" + skins.ownedSummary(profile),
                        wearing ? "&7Wearing: &f" + profile.equippedHat() : "&7Wearing: &fnone",
                        "&7Hats render above your helmet;",
                        "&7all armour attributes are preserved.")));
        final List<Skin> hats = new ArrayList<>(skins.hats());
        for (int i = 0; i < HAT_SLOTS.length && i < hats.size(); i++) {
            inventory.setItem(HAT_SLOTS[i], hatEntry(profile, hats.get(i)));
        }
        final Skin preview = previewSkin();
        if (preview == null) {
            inventory.setItem(SLOT_PREVIEW, GuiService.item(
                    Material.ITEM_FRAME, "&fPreview",
                    List.of("&7Select a hat to preview it.")));
        } else {
            final ItemStack stack = skins.hatStack(preview);
            final var meta = stack.getItemMeta();
            final List<String> lore = meta.hasLore()
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ColorUtil.colorize("&8Preview"));
            meta.setLore(lore);
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
            inventory.setItem(SLOT_PREVIEW, stack);
        }
        final List<String> applyLore = new ArrayList<>();
        if (preview == null) {
            applyLore.add("&7Select a hat first.");
        } else if (!profile.ownsSkin(preview.id())) {
            applyLore.add("&7Hat: " + preview.display());
            applyLore.add("&cYou do not own this hat.");
        } else {
            applyLore.add("&7Hat: " + preview.display());
            applyLore.add("&eClick to wear it.");
        }
        inventory.setItem(SLOT_APPLY, GuiService.item(Material.LIME_DYE, "&a&lWEAR", applyLore));
        inventory.setItem(SLOT_REMOVE, GuiService.item(Material.GRAY_DYE, "&7&lREMOVE HAT",
                List.of("&7Takes the hat off.", "&eClick to remove.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&cClose", List.of()));
    }

    private ItemStack hatEntry(final PlayerProfile profile, final Skin skin) {
        final boolean owned = profile.ownsSkin(skin.id());
        final boolean equipped = skin.id().equals(profile.equippedHat());
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + skin.description());
        lore.add("&7Unlock: " + SkinService.sourceLabel(skin));
        lore.add("");
        if (equipped) {
            lore.add("&a✔ &lWORN");
        } else if (owned) {
            lore.add("&a✔ Owned");
        } else {
            lore.add("&c✖ Locked");
        }
        lore.add("&eClick &7to preview &8(&eeClick again to wear&8)");
        final ItemStack item = new ItemStack(skin.material());
        final var meta = item.getItemMeta();
        meta.setDisplayName(ColorUtil.colorize((owned ? "&a✔ " : "&c✖ ") + skin.display()));
        meta.setLore(lore.stream().map(ColorUtil::colorize).toList());
        meta.setCustomModelData(skin.modelId());
        if (equipped || skin.id().equals(previewSkinId)) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Skin previewSkin() {
        return previewSkinId == null ? null : skins.skin(previewSkinId).orElse(null);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final PlayerProfile profile = plugin.playerData()
                .profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_TAB_TOOLS) {
            plugin.gui().open(viewer, new SkinsGui(plugin));
            return false;
        }
        for (int i = 0; i < HAT_SLOTS.length; i++) {
            if (slot != HAT_SLOTS[i]) {
                continue;
            }
            final List<Skin> hats = new ArrayList<>(skins.hats());
            if (i < hats.size()) {
                final Skin skin = hats.get(i);
                if (skin.id().equals(previewSkinId) && profile.ownsSkin(skin.id())) {
                    // clicking the same hat twice wears it
                    wear(viewer, profile, skin);
                }
                previewSkinId = skin.id();
            }
            return true;
        }
        if (slot == SLOT_APPLY) {
            final Skin skin = previewSkin();
            if (skin != null) {
                wear(viewer, profile, skin);
            }
            return true;
        }
        if (slot == SLOT_REMOVE) {
            skins.clearHat(viewer, profile);
            plugin.messages().sendPrefixed(viewer, "skins.hat-removed", Map.of());
            return true;
        }
        return false;
    }

    private void wear(final Player viewer, final PlayerProfile profile, final Skin skin) {
        if (!profile.ownsSkin(skin.id())) {
            plugin.messages().sendPrefixed(viewer, "skins.not-owned", Map.of());
            return;
        }
        if (skins.equipHat(viewer, profile, skin)) {
            plugin.messages().sendPrefixed(viewer, "skins.hat-applied", Map.of(
                    "hat", ColorUtil.colorize(skin.display())));
        }
    }
}
