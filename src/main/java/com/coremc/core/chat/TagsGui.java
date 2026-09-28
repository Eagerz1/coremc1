package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * {@code /tags} — the cosmetic tag picker.
 *
 * Layout, materials and slots all come from {@code tags.yml gui:}; the
 * page only renders state:
 *   owned + selected  → selected material, "SELECTED" lore,
 *   owned             → the tag's own material, "click to select",
 *   locked            → locked material, unlock/permission lore.
 *
 * Pagination appears only when the catalogue does not fit the configured
 * slots (the 20 shipped tags fit one page).
 */
public final class TagsGui implements Gui {

    private final CoreMCPlugin plugin;
    private final int page;

    public TagsGui(final CoreMCPlugin plugin) {
        this(plugin, 0);
    }

    public TagsGui(final CoreMCPlugin plugin, final int page) {
        this.plugin = plugin;
        this.page = Math.max(0, page);
    }

    private TagService.GuiLayout layout() {
        return plugin.tags().layout();
    }

    @Override
    public String title() {
        final int pages = pageCount();
        final String base = layout().title();
        return ColorUtil.colorize(pages > 1 ? base + " &8(" + (page + 1) + "/" + pages + ")" : base);
    }

    @Override
    public int size() {
        return layout().size();
    }

    private int pageCount() {
        final int perPage = Math.max(1, layout().slots().size());
        final int total = plugin.tags().all().size();
        return Math.max(1, (int) Math.ceil(total / (double) perPage));
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final TagService.GuiLayout layout = layout();
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<TagDefinition> tags = new ArrayList<>(plugin.tags().all());
        final List<Integer> slots = layout.slots();
        final int perPage = Math.max(1, slots.size());
        final int start = page * perPage;

        for (int i = 0; i < perPage; i++) {
            final int index = start + i;
            if (index >= tags.size()) {
                break;
            }
            final TagDefinition tag = tags.get(index);
            final boolean owned = plugin.tags().owns(viewer, profile, tag);
            final boolean selected = profile != null && tag.id().equals(profile.equippedTag());
            inventory.setItem(slots.get(i), icon(tag, owned, selected, layout));
        }

        final String equipped = profile == null ? CosmeticAccess.NONE : profile.equippedTag();
        final List<String> clearLore = new ArrayList<>();
        clearLore.add("&7Removes your tag from chat.");
        clearLore.add("&7Current: &f"
                + (CosmeticAccess.NONE.equals(equipped)
                        ? "&8none"
                        : plugin.tags().byId(equipped).map(TagDefinition::display).orElse("&8none")));
        clearLore.add("");
        clearLore.add(CosmeticAccess.NONE.equals(equipped) ? "&8Nothing selected." : "&eClick to clear.");
        inventory.setItem(layout.clearSlot(), GuiService.item(
                material(layout.clearMaterial(), Material.BARRIER), "&c&lCLEAR TAG", clearLore));

        if (pageCount() > 1) {
            if (page > 0) {
                inventory.setItem(layout.previousSlot(), GuiService.item(
                        Material.ARROW, "&e&lPREVIOUS PAGE", List.of("&7Page &f" + page)));
            }
            if (page + 1 < pageCount()) {
                inventory.setItem(layout.nextSlot(), GuiService.item(
                        Material.ARROW, "&e&lNEXT PAGE", List.of("&7Page &f" + (page + 2))));
            }
        }

        inventory.setItem(layout.closeSlot(), GuiService.item(
                material(layout.closeMaterial(), Material.BARRIER), "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    private org.bukkit.inventory.ItemStack icon(
            final TagDefinition tag,
            final boolean owned,
            final boolean selected,
            final TagService.GuiLayout layout) {
        final List<String> lore = new ArrayList<>();
        lore.add("&7Chat preview:");
        lore.add("&8» " + tag.display() + " &fPlayer&7: &fhello");
        lore.addAll(tag.lore());
        lore.add("");
        if (selected) {
            lore.add("&a✔ Selected");
            lore.add("&7Click &fCLEAR TAG&7 to remove it.");
        } else if (owned) {
            lore.add("&a✔ Unlocked");
            lore.add("&eClick to select.");
        } else {
            lore.add("&c✖ Locked");
            lore.add("&7Unlock via crates, the store or events.");
            if (tag.permission() != null && !tag.permission().isBlank()) {
                lore.add("&8" + tag.permission());
            }
        }
        final Material material = selected
                ? material(layout.selectedMaterial(), Material.NAME_TAG)
                : owned ? material(tag.material(), Material.NAME_TAG)
                        : material(layout.lockedMaterial(), Material.GRAY_DYE);
        final String name = (selected ? "&a" : owned ? "&f" : "&8") + stripToName(tag) + (selected ? " &8(selected)" : "");
        return GuiService.item(material, name, lore);
    }

    private static String stripToName(final TagDefinition tag) {
        return tag.nameOrDisplay();
    }

    private static Material material(final String name, final Material fallback) {
        final Material matched = name == null ? null : Material.matchMaterial(name);
        return matched == null || matched.isAir() ? fallback : matched;
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final TagService.GuiLayout layout = layout();
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(viewer, "island.starting", Map.of());
            return false;
        }
        if (slot == layout.closeSlot()) {
            viewer.closeInventory();
            return false;
        }
        if (slot == layout.clearSlot()) {
            if (plugin.tags().clear(profile)) {
                click(viewer, 1.0f);
                plugin.messages().sendPrefixed(viewer, "tags.cleared", Map.of());
            }
            return true;
        }
        if (pageCount() > 1 && slot == layout.previousSlot() && page > 0) {
            plugin.gui().open(viewer, new TagsGui(plugin, page - 1));
            return false;
        }
        if (pageCount() > 1 && slot == layout.nextSlot() && page + 1 < pageCount()) {
            plugin.gui().open(viewer, new TagsGui(plugin, page + 1));
            return false;
        }
        final List<Integer> slots = layout.slots();
        final int index = slots.indexOf(slot);
        if (index < 0) {
            return false;
        }
        final List<TagDefinition> tags = new ArrayList<>(plugin.tags().all());
        final int tagIndex = page * Math.max(1, slots.size()) + index;
        if (tagIndex >= tags.size()) {
            return false;
        }
        final TagDefinition tag = tags.get(tagIndex);
        final CosmeticAccess.Result result = plugin.tags().select(viewer, profile, tag.id());
        switch (result) {
            case SELECTED -> {
                click(viewer, 1.2f);
                plugin.messages().sendPrefixed(viewer, "tags.selected",
                        Map.of("tag", ColorUtil.colorize(tag.display())));
            }
            case LOCKED -> {
                click(viewer, 0.6f);
                plugin.messages().sendPrefixed(viewer, "tags.locked",
                        Map.of("tag", ColorUtil.colorize(tag.display())));
            }
            default -> click(viewer, 1.0f);
        }
        return true;
    }

    private void click(final Player viewer, final float pitch) {
        if (layout().sounds()) {
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, pitch);
        }
    }
}
