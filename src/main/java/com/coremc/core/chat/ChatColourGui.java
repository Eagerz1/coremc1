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
import org.bukkit.inventory.ItemStack;

/**
 * {@code /chatcolour} (alias {@code /chatcolor}) — message colour picker.
 *
 * Rows: solid colours, then gradients, then the bold toggle, a live
 * preview of the current selection and a reset button. Everything is
 * config-driven; locked entries show their unlock lore instead of
 * being hidden, so players can see what is available.
 */
public final class ChatColourGui implements Gui {

    private final CoreMCPlugin plugin;

    public ChatColourGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    private ChatStyleService.GuiLayout layout() {
        return plugin.chatStyles().layout();
    }

    @Override
    public String title() {
        return ColorUtil.colorize(layout().title());
    }

    @Override
    public int size() {
        return layout().size();
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final ChatStyleService styles = plugin.chatStyles();
        final ChatStyleService.GuiLayout layout = layout();
        final ChatStyleService.Settings settings = styles.settings();
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final boolean bold = profile != null && profile.chatBold();
        final String selected = profile == null ? CosmeticAccess.NONE : profile.chatColor();

        place(inventory, layout.colourSlots(), styles.catalog().solids(), viewer, profile, selected, bold, settings);
        place(inventory, layout.gradientSlots(), styles.catalog().gradients(), viewer, profile, selected, bold,
                settings);

        // Bold toggle.
        final boolean boldAllowed = styles.boldAllowed(viewer);
        final List<String> boldLore = new ArrayList<>();
        boldLore.add("&7Makes your messages &lbold&7.");
        boldLore.add("&7State: " + (bold ? "&aON" : "&cOFF"));
        boldLore.add("");
        boldLore.add(boldAllowed ? "&eClick to toggle." : "&c✖ Locked &8(" + settings.boldPermission() + ")");
        inventory.setItem(layout.boldSlot(), GuiService.item(
                boldAllowed ? Material.ANVIL : material(layout.lockedMaterial(), Material.GRAY_DYE),
                "&f&lBOLD", boldLore));

        // Live preview of the current selection.
        final String sample = settings.previewSample();
        final String preview = styles.render(viewer, profile, sample);
        final List<String> previewLore = new ArrayList<>();
        previewLore.add("&7Current style: &f"
                + (CosmeticAccess.NONE.equals(selected) ? "&8default" : selected));
        previewLore.add("&7Bold: " + (bold ? "&aON" : "&cOFF"));
        previewLore.add("");
        previewLore.add("&8» " + preview);
        inventory.setItem(layout.previewSlot(), GuiService.item(
                Material.PAPER, "&b&lPREVIEW", previewLore));

        // Reset.
        inventory.setItem(layout.resetSlot(), GuiService.item(
                Material.WATER_BUCKET, "&e&lRESET",
                List.of("&7Back to the default chat colour", "&7and bold off.", "", "&eClick to reset.")));

        inventory.setItem(layout.closeSlot(), GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    private void place(
            final Inventory inventory,
            final List<Integer> slots,
            final List<ChatStyle> styles,
            final Player viewer,
            final PlayerProfile profile,
            final String selected,
            final boolean bold,
            final ChatStyleService.Settings settings) {
        for (int i = 0; i < slots.size() && i < styles.size(); i++) {
            final ChatStyle style = styles.get(i);
            final boolean owned = plugin.chatStyles().owns(viewer, profile, style);
            final boolean active = style.id().equals(selected);
            final List<String> lore = new ArrayList<>();
            lore.add("&7Preview:");
            lore.add("&8» " + style.preview(settings.previewSample(), bold, settings.maxGradientSegments()));
            if (style.gradient()) {
                lore.add("&7Gradient: &f#" + style.fromHex() + " &7→ &f#" + style.toHex());
            }
            lore.add("");
            if (active) {
                lore.add("&a✔ Selected");
            } else if (owned) {
                lore.add("&a✔ Unlocked");
                lore.add("&eClick to select.");
            } else {
                lore.add("&c✖ Locked");
                lore.add("&7Unlock via crates, the store or events.");
                lore.add("&8" + style.permission());
            }
            final Material material = owned
                    ? material(style.material(), Material.WHITE_WOOL)
                    : material(plugin.chatStyles().layout().lockedMaterial(), Material.GRAY_DYE);
            final ItemStack item = GuiService.item(
                    material, (active ? "&a✔ " : owned ? "" : "&8") + style.display(), lore);
            inventory.setItem(slots.get(i), item);
        }
    }

    private static Material material(final String name, final Material fallback) {
        final Material matched = name == null ? null : Material.matchMaterial(name);
        return matched == null || matched.isAir() ? fallback : matched;
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final ChatStyleService styles = plugin.chatStyles();
        final ChatStyleService.GuiLayout layout = layout();
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(viewer, "island.starting", Map.of());
            return false;
        }
        if (slot == layout.closeSlot()) {
            viewer.closeInventory();
            return false;
        }
        if (slot == layout.resetSlot()) {
            styles.reset(profile);
            click(viewer, 1.0f);
            plugin.messages().sendPrefixed(viewer, "chatcolour.reset", Map.of());
            return true;
        }
        if (slot == layout.boldSlot()) {
            if (!styles.boldAllowed(viewer)) {
                click(viewer, 0.6f);
                plugin.messages().sendPrefixed(viewer, "chatcolour.bold-locked", Map.of());
                return false;
            }
            final boolean state = styles.toggleBold(viewer, profile);
            click(viewer, state ? 1.3f : 0.9f);
            plugin.messages().sendPrefixed(viewer, "chatcolour.bold", Map.of("state", state ? "&aON" : "&cOFF"));
            return true;
        }
        final ChatStyle clicked = styleAt(slot, layout, styles);
        if (clicked == null) {
            return false;
        }
        final CosmeticAccess.Result result = styles.select(viewer, profile, clicked.id());
        switch (result) {
            case SELECTED -> {
                click(viewer, 1.2f);
                plugin.messages().sendPrefixed(viewer, "chatcolour.selected",
                        Map.of("style", ColorUtil.colorize(clicked.display())));
            }
            case LOCKED -> {
                click(viewer, 0.6f);
                plugin.messages().sendPrefixed(viewer, "chatcolour.locked",
                        Map.of("style", ColorUtil.colorize(clicked.display())));
            }
            default -> click(viewer, 1.0f);
        }
        return true;
    }

    private ChatStyle styleAt(
            final int slot, final ChatStyleService.GuiLayout layout, final ChatStyleService styles) {
        final int solidIndex = layout.colourSlots().indexOf(slot);
        if (solidIndex >= 0 && solidIndex < styles.catalog().solids().size()) {
            return styles.catalog().solids().get(solidIndex);
        }
        final int gradientIndex = layout.gradientSlots().indexOf(slot);
        if (gradientIndex >= 0 && gradientIndex < styles.catalog().gradients().size()) {
            return styles.catalog().gradients().get(gradientIndex);
        }
        return null;
    }

    private void click(final Player viewer, final float pitch) {
        if (layout().sounds()) {
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, pitch);
        }
    }
}
