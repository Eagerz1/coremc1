package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.Role;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The Tool Skins section of /skins: a CoreMC-style double chest with
 * collection and role filters, paging, animated live previews (the grid
 * entries ARE the custom models — their textures animate in the GUI), a
 * preview slot showing the skinned OmniTool, Apply, Reset and Close.
 *
 * The preview never modifies the real tool: Apply is the only action that
 * records a selection, and even then only the visual model layer of the
 * soulbound tool changes (identity PDC, damage, enchants, upgrades and
 * levels are preserved byte-for-byte).
 */
public final class SkinsGui implements Gui {

    private static final int SLOT_TAB_TOOLS = 0;
    private static final int SLOT_TAB_HATS = 1;
    private static final int SLOT_INFO = 4;

    private static final int SLOT_COLLECTION_ALL = 9;
    private static final int SLOT_COLLECTION_BASE = 10; // 10..14 = five collections

    private static final int SLOT_ROLE_ALL = 18;
    private static final int SLOT_ROLE_BASE = 19; // 19..24 = six roles

    /** 14 grid slots: rows 3 and 4 minus the edge columns. */
    private static final int[] GRID_SLOTS = {
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 46;
    private static final int SLOT_PREVIEW = 47;
    private static final int SLOT_APPLY = 49;
    private static final int SLOT_CLEAR = 51;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final SkinService skins;
    private final Role toolRole;

    /** Per-open view state (this GUI instance lives exactly one open). */
    private String collectionFilter; // null = all
    private int page;
    private String previewSkinId; // null = no preview selected

    public SkinsGui(final CoreMCPlugin plugin, final Role toolRole) {
        this.plugin = plugin;
        this.skins = plugin.skins();
        this.toolRole = java.util.Objects.requireNonNull(toolRole, "toolRole");
    }

    @Override
    public String title() {
        return "&b&lCOREMC &8» &7" + ColorUtil.colorize(toolRole.display()) + " Skins";
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
        // The submenu is scoped to one selected tool.
        inventory.setItem(SLOT_TAB_TOOLS, GuiService.item(
                Material.ARROW, "&e&lBack to Tools", List.of("&7Choose a different Omni-Tool.")));
        inventory.setItem(SLOT_TAB_HATS, GuiService.item(
                toolRole.toolMaterial(), toolRole.display(),
                List.of("&7Skins for this tool only.")));
        inventory.setItem(SLOT_INFO, GuiService.item(
                Material.BOOK, "&b&lSKINS",
                List.of(
                        "&7Owned: &f" + skins.ownedSummary(profile),
                        "&7Filters narrow the grid; the grid",
                        "&7entries are live previews.",
                        "&eLeft-click &7a skin to preview it.",
                        "&eRight-click &7an owned skin to apply it.")));
        // collection filters
        inventory.setItem(SLOT_COLLECTION_ALL, GuiService.item(
                Material.NAME_TAG,
                (collectionFilter == null ? "&a&l" : "&7") + "All Collections",
                List.of(collectionFilter == null ? "&aSelected" : "&eClick to show all.")));
        int slot = SLOT_COLLECTION_BASE;
        for (final SkinCollection collection : skins.catalog().collections()) {
            final boolean active = collection.id().equals(collectionFilter);
            final List<String> lore = new ArrayList<>();
            lore.add("&7" + collection.description());
            final long owned = collection.toolSkins().stream()
                    .filter(skin -> profile.ownsSkin(skin.id())).count();
            lore.add("&7Owned: &f" + owned + "&7/&f" + collection.toolSkins().size());
            lore.add(active ? "&aSelected" : "&eClick to filter.");
            final ItemStack item = GuiService.item(
                    skins.filterIcon(collection),
                    (active ? "&a&l" : "&7") + collection.display(), lore);
            if (active) {
                glint(item);
            }
            inventory.setItem(slot++, item);
        }
        inventory.setItem(SLOT_ROLE_ALL, GuiService.item(
                toolRole.toolMaterial(), "&b&l" + toolRole.display(),
                List.of("&7Showing skins for this tool only.")));
        // grid
        final List<Skin> visible = skins.toolSkins(collectionFilter, toolRole);
        final int pages = Math.max(1, (visible.size() + GRID_SLOTS.length - 1) / GRID_SLOTS.length);
        if (page >= pages) {
            page = pages - 1;
        }
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            final int index = page * GRID_SLOTS.length + i;
            if (index >= visible.size()) {
                break;
            }
            inventory.setItem(GRID_SLOTS[i], skinEntry(viewer, profile, visible.get(index)));
        }
        // navigation + actions
        inventory.setItem(SLOT_PREV, GuiService.item(Material.ARROW, "&ePrevious Page",
                List.of("&7Page &f" + (page + 1) + "&7/&f" + pages)));
        inventory.setItem(SLOT_NEXT, GuiService.item(Material.ARROW, "&eNext Page",
                List.of("&7Page &f" + (page + 1) + "&7/&f" + pages)));
        buildPreview(viewer, profile, inventory);
        buildApply(profile, inventory);
        buildClear(profile, inventory);
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&cClose", List.of()));
    }

    private ItemStack skinEntry(final Player viewer, final PlayerProfile profile, final Skin skin) {
        final boolean owned = profile.ownsSkin(skin.id());
        final boolean equipped = skin.id().equals(
                profile.equippedToolSkin(skin.role().key()).orElse(null));
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + skin.description());
        lore.add("&7Role: " + skin.role().display());
        lore.add("&7Unlock: " + SkinService.sourceLabel(skin));
        lore.add("");
        if (equipped) {
            lore.add("&a✔ &lEQUIPPED");
        } else if (owned) {
            lore.add("&a✔ Owned");
        } else {
            lore.add("&c✖ Locked");
        }
        lore.add("&eLeft-click &7to preview.");
        if (owned && !equipped) {
            lore.add("&eRight-click &7to apply.");
        }
        final ItemStack item = new ItemStack(skin.material());
        final var meta = item.getItemMeta();
        meta.setDisplayName(ColorUtil.colorize((owned ? "&a✔ " : "&c✖ ") + skin.display()));
        meta.setLore(lore.stream().map(ColorUtil::colorize).toList());
        // the grid entry itself is the animated custom model (live preview)
        meta.setCustomModelData(skin.modelId());
        if (equipped || previewSkinId != null && previewSkinId.equals(skin.id())) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void buildPreview(final Player viewer, final PlayerProfile profile, final Inventory inventory) {
        final Skin preview = previewSkin();
        if (preview == null) {
            inventory.setItem(SLOT_PREVIEW, GuiService.item(
                    Material.ITEM_FRAME, "&fPreview",
                    List.of("&7Select a skin in the grid", "&7to see it on your tool.")));
            return;
        }
        final ItemStack stack = skins.previewToolStack(viewer, preview);
        inventory.setItem(SLOT_PREVIEW, stack);
    }

    private void buildApply(final PlayerProfile profile, final Inventory inventory) {
        final Skin preview = previewSkin();
        final List<String> lore = new ArrayList<>();
        if (preview == null) {
            lore.add("&7Select a skin first.");
        } else {
            final boolean owned = profile.ownsSkin(preview.id());
            lore.add("&7Skin: " + preview.display());
            if (!owned) {
                lore.add("&cYou do not own this skin.");
            } else {
                lore.add("&7Applies to your &f" + preview.role().display() + " &7Omni-Tool.");
                lore.add("&7The tool's stats, enchants and");
                lore.add("&7upgrades are never changed.");
                lore.add("&eClick to apply.");
            }
        }
        inventory.setItem(SLOT_APPLY, GuiService.item(Material.LIME_DYE, "&a&lAPPLY", lore));
    }

    private void buildClear(final PlayerProfile profile, final Inventory inventory) {
        final Skin preview = previewSkin();
        final List<String> lore = new ArrayList<>();
        lore.add("&7Returns the tool to its default look.");
        if (preview != null) {
            lore.add("&7Resets: &f" + preview.role().display());
        } else if (toolRole != null) {
            lore.add("&7Resets: &f" + toolRole.display());
        } else {
            lore.add("&7Resets: &fall roles");
        }
        lore.add("&eClick to reset.");
        inventory.setItem(SLOT_CLEAR, GuiService.item(Material.GRAY_DYE, "&7&lRESET TO DEFAULT", lore));
    }

    private Skin previewSkin() {
        return previewSkinId == null ? null : skins.skin(previewSkinId).orElse(null);
    }

    private static void glint(final ItemStack item) {
        final var meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
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
            plugin.gui().open(viewer, new SkinsToolSelectGui(plugin));
            return false;
        }
        if (slot == SLOT_COLLECTION_ALL) {
            collectionFilter = null;
            page = 0;
            return true;
        }
        if (slot >= SLOT_COLLECTION_BASE && slot < SLOT_COLLECTION_BASE + 5) {
            final List<SkinCollection> collections =
                    new ArrayList<>(skins.catalog().collections());
            final int index = slot - SLOT_COLLECTION_BASE;
            if (index < collections.size()) {
                collectionFilter = collections.get(index).id();
                page = 0;
                return true;
            }
        }
        if (slot == SLOT_PREV) {
            if (page > 0) {
                page--;
            }
            return true;
        }
        if (slot == SLOT_NEXT) {
            final List<Skin> visible = skins.toolSkins(collectionFilter, toolRole);
            final int pages = Math.max(1, (visible.size() + GRID_SLOTS.length - 1) / GRID_SLOTS.length);
            if (page < pages - 1) {
                page++;
            }
            return true;
        }
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            if (slot != GRID_SLOTS[i]) {
                continue;
            }
            final List<Skin> visible = skins.toolSkins(collectionFilter, toolRole);
            final int index = page * GRID_SLOTS.length + i;
            if (index < visible.size()) {
                previewSkinId = visible.get(index).id();
            }
            return true;
        }
        if (slot == SLOT_APPLY) {
            applyPreview(viewer, profile);
            return true;
        }
        if (slot == SLOT_CLEAR) {
            reset(viewer, profile);
            return true;
        }
        return false;
    }

    @Override
    public boolean onRightClick(final Player viewer, final int slot) {
        // right-click an owned grid skin = quick apply
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            if (slot != GRID_SLOTS[i]) {
                continue;
            }
            final PlayerProfile profile = plugin.playerData()
                    .profileOf(viewer.getUniqueId()).orElse(null);
            if (profile == null) {
                return false;
            }
            final List<Skin> visible = skins.toolSkins(collectionFilter, toolRole);
            final int index = page * GRID_SLOTS.length + i;
            if (index >= visible.size()) {
                return false;
            }
            final Skin skin = visible.get(index);
            previewSkinId = skin.id();
            applyPreview(viewer, profile);
            return true;
        }
        return onClick(viewer, slot);
    }

    private void applyPreview(final Player viewer, final PlayerProfile profile) {
        final Skin skin = previewSkin();
        if (skin == null) {
            return;
        }
        if (!profile.ownsSkin(skin.id())) {
            plugin.messages().sendPrefixed(viewer, "skins.not-owned", java.util.Map.of());
            return;
        }
        switch (skins.equipToolSkin(viewer, profile, skin)) {
            case OK -> plugin.messages().sendPrefixed(viewer, "skins.applied", java.util.Map.of(
                    "skin", ColorUtil.colorize(skin.display())));
            case WRONG_ROLE -> plugin.messages().sendPrefixed(viewer, "skins.wrong-role", java.util.Map.of(
                    "role", skin.role().display()));
            default -> plugin.messages().sendPrefixed(viewer, "skins.not-owned", java.util.Map.of());
        }
    }

    private void reset(final Player viewer, final PlayerProfile profile) {
        final Skin preview = previewSkin();
        if (preview != null) {
            skins.clearToolSkin(viewer, profile, preview.role());
        } else {
            skins.clearToolSkin(viewer, profile, toolRole);
        }
        plugin.messages().sendPrefixed(viewer, "skins.cleared", java.util.Map.of());
    }
}
