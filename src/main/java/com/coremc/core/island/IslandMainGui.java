package com.coremc.core.island;

import com.coremc.core.gen.GensGui;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.spawner.SpawnersGui;
import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The main island menu opened by bare {@code /is} — 54-slot double chest.
 *
 * Spaced layout: primary island actions on row two, management on row three,
 * and a separated danger zone. Actions route through existing commands/panels.
 */
public final class IslandMainGui implements Gui {

    private static final int SLOT_HOME = 10;
    private static final int SLOT_MEMBERS = 12;
    private static final int SLOT_INFO = 14;
    private static final int SLOT_UPGRADES = 16;
    private static final int SLOT_SETTINGS = 19;
    private static final int SLOT_PERMISSIONS = 21;
    private static final int SLOT_INVITE = 23;
    private static final int SLOT_DELETE = 31;
    private static final int SLOT_GENS = 28;
    private static final int SLOT_SPAWNERS = 30;
    private static final int SLOT_BORDER = 32;
    private static final int SLOT_VISIT = 34;
    private static final int SLOT_LEAVE = 38;
    private static final int SLOT_BUFFS = 40;
    private static final int SLOT_CLOSE = 49;

    /** All actionable slots — exported so audits and tests never duplicate the layout. */
    public static final java.util.Set<Integer> ACTION_SLOTS =
            java.util.Set.of(10, 12, 14, 16, 19, 21, 23, 31, 28, 30, 32, 34, 38, 40, 49);

    private final CoreMCPlugin plugin;

    public IslandMainGui(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String title() {
        return "&b&lＣＯＲＥＭＣ &8» &f&lɪsʟᴀɴᴅ ᴍᴇɴᴜ";
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final var island = plugin.islands().islandOf(viewer.getUniqueId());
        final boolean hasIsland = island.isPresent();
        final boolean owner = hasIsland && island.get().owner().equals(viewer.getUniqueId());
        final Map<String, String> placeholders = plugin.islands().placeholdersOf(viewer.getUniqueId());

        if (hasIsland) {
            inventory.setItem(SLOT_HOME, GuiService.item(Material.ENDER_PEARL,
                    "&a&lɪsʟᴀɴᴅ ʜᴏᴍᴇ",
                    List.of("&8ᴛʀᴀᴠᴇʟ", "&7Return to your island spawn.", "", "&a➜ &fClick to teleport.")));
        } else {
            inventory.setItem(SLOT_HOME, GuiService.item(Material.GRASS_BLOCK,
                    "&a&lᴄʀᴇᴀᴛᴇ ɪsʟᴀɴᴅ",
                    List.of("&8ʙᴇɢɪɴ ʏᴏᴜʀ ᴊᴏᴜʀɴᴇʏ", "&7Choose a theme and claim", "&7your place in the sky.", "", "&a➜ &fClick to choose a theme.")));
        }
        inventory.setItem(SLOT_MEMBERS, GuiService.item(Material.PLAYER_HEAD,
                "&d&lᴍᴇᴍʙᴇʀs", hasIsland
                        ? List.of("&8ᴛᴇᴀᴍ ᴍᴀɴᴀɢᴇᴍᴇɴᴛ", "&7See who belongs to your island.", "", "&d➜ &fClick to view members.")
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_INFO, GuiService.item(Material.BOOK,
                "&b&lɪsʟᴀɴᴅ sᴛᴀᴛs",
                List.of("&8ʏᴏᴜʀ ᴘʀᴏɢʀᴇss", "&7Level: &f" + placeholders.get("island_level"),
                        "&7Border: &f" + placeholders.get("island_border"),
                        "&7Members: &f" + placeholders.get("island_members"),
                        "&7Owner: &f" + placeholders.get("island_owner"), "", "&b➜ &fClick for full details.")));
        inventory.setItem(SLOT_UPGRADES, GuiService.item(Material.CRAFTING_TABLE,
                "&6&lᴜᴘɢʀᴀᴅᴇs",
                List.of("&8ɪᴍᴘʀᴏᴠᴇ ʏᴏᴜʀ ɪsʟᴀɴᴅ", "&7Mining, fishing, farming, slaying,", "&7logging and island upgrades.", "", "&6➜ &fClick to browse upgrades.")));
        inventory.setItem(SLOT_SETTINGS, GuiService.item(
                hasIsland && !owner ? Material.GRAY_DYE : Material.REPEATER,
                "&e&lɪsʟᴀɴᴅ sᴇᴛᴛɪɴɢs", hasIsland
                        ? (owner ? List.of("&8ᴏᴡɴᴇʀ ᴄᴏɴᴛʀᴏʟs", "&7Manage visitors and mob spawning.", "", "&e➜ &fClick to configure.")
                                : List.of("&8ᴠɪᴇᴡ ᴏɴʟʏ", "&7View island settings.", "&cOnly the owner can change them."))
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_PERMISSIONS, GuiService.item(
                hasIsland && !owner ? Material.GRAY_DYE : Material.OAK_FENCE_GATE,
                "&9&lᴘᴇʀᴍɪssɪᴏɴs", hasIsland
                        ? (owner ? List.of("&8ᴍᴇᴍʙᴇʀ ᴀᴄᴄᴇss", "&7Choose what members may do.", "", "&9➜ &fClick to manage permissions.")
                                : List.of("&8ᴠɪᴇᴡ ᴏɴʟʏ", "&7See what your role can do.", "&cOnly the owner can change permissions."))
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_INVITE, GuiService.item(Material.ENDER_EYE,
                "&d&lɪɴᴠɪᴛᴇ ᴘʟᴀʏᴇʀs", List.of("&8ɢʀᴏᴡ ʏᴏᴜʀ ᴛᴇᴀᴍ", "&7Bring a friend to your island.", "", "&d➜ &fUse &d/is invite <player>")));
        inventory.setItem(SLOT_DELETE, GuiService.item(Material.BARRIER,
                "&c&lᴅᴇʟᴇᴛᴇ ɪsʟᴀɴᴅ", List.of("&4ᴅᴀɴɢᴇʀ ᴢᴏɴᴇ", "&7Permanently remove your island.", "&cA confirmation is required.", "", "&c➜ &fClick to continue.")));

        inventory.setItem(SLOT_GENS, GuiService.item(Material.OBSERVER,
                "&6&lɢᴇɴᴇʀᴀᴛᴏʀs", List.of("&8ᴘᴀssɪᴠᴇ ʀᴇsᴏᴜʀᴄᴇs", "&7Buy generators for cobble,", "&7obsidian, ice and quartz.", "", "&6➜ &fClick to browse generators.")));
        inventory.setItem(SLOT_SPAWNERS, GuiService.item(Material.SPAWNER,
                "&6&l sᴘᴀᴡɴᴇʀs", List.of("&8ᴜɴʟᴏᴄᴋ ᴀɴᴅ ᴘʀᴏɢʀᴇss", "&7Unlock tiers with kills, then", "&7purchase spawners here.", "", "&6➜ &fClick to open spawners.")));
        if (hasIsland) {
            final var islandValue = island.get();
            final int borderTier = islandValue.upgrades().getOrDefault("border", 0);
            final int borderMax = plugin.coreConfig().upgradeMaxTier("border");
            final var borderCost = plugin.coreConfig().upgradeCost("border", borderTier);
            final List<String> borderLore = new ArrayList<>(List.of("&8ᴇxᴘᴀɴᴅ ʏᴏᴜʀ ᴛᴇʀʀɪᴛᴏʀʏ",
                    "&7Current size: &f" + plugin.islands().effectiveBorder(islandValue) + "x" + plugin.islands().effectiveBorder(islandValue)));
            if (!owner) {
                borderLore.add("&cOnly the owner can upgrade.");
            } else if (borderTier >= borderMax || borderCost.isEmpty()) {
                borderLore.add("&a&lᴍᴀxᴇᴅ ᴏᴜᴛ");
            } else {
                borderLore.add("&7Next upgrade: &b" + borderCost.getAsLong() + " Sky Tokens");
                borderLore.add("");
                borderLore.add("&6➜ &fClick to expand.");
            }
            inventory.setItem(SLOT_BORDER, GuiService.item(Material.OAK_FENCE, "&6&lʙᴏʀᴅᴇʀ ᴜᴘɢʀᴀᴅᴇ", borderLore));
        } else {
            inventory.setItem(SLOT_BORDER, GuiService.item(Material.OAK_FENCE,
                    "&6&lʙᴏʀᴅᴇʀ ᴜᴘɢʀᴀᴅᴇ", List.of("&8Create an island first.")));
        }
        inventory.setItem(SLOT_VISIT, GuiService.item(Material.ENDER_EYE,
                "&b&lᴠɪsɪᴛ ɪsʟᴀɴᴅs", List.of("&8ᴇxᴘʟᴏʀᴇ ᴛʜᴇ sᴋʏ", "&7Visit an island that allows guests.", "", "&b➜ &fUse &b/is visit <player>")));
        inventory.setItem(SLOT_LEAVE, GuiService.item(Material.IRON_DOOR,
                "&c&lʟᴇᴀᴠᴇ ɪsʟᴀɴᴅ", !hasIsland
                        ? List.of("&8You have no island.")
                        : (owner ? List.of("&8ᴏᴡɴᴇʀ ʀᴇsᴛʀɪᴄᴛɪᴏɴ", "&7Owners cannot leave their island.", "&cDelete it instead.")
                                : List.of("&8ᴛᴇᴀᴍ ᴀᴄᴛɪᴏɴ", "&7Leave this island.", "", "&c➜ &fClick to leave."))));
        inventory.setItem(SLOT_BUFFS, GuiService.item(Material.POTION,
                "&b&lɪsʟᴀɴᴅ ʙᴜғғs", hasIsland
                        ? List.of("&8ᴘᴀssɪᴠᴇ ʙᴏɴᴜsᴇs", "&7View island-wide drops, currency,", "&7XP, generators and spawner boosts.", "", "&b➜ &fClick to view buffs.")
                        : List.of("&8Create an island first.")));
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lᴄʟᴏsᴇ ᴍᴇɴᴜ", List.of("&8Return to the game.")));

        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        final boolean hasIsland = plugin.islands().islandOf(viewer.getUniqueId()).isPresent();
        boolean refresh = false;
        switch (slot) {
            case SLOT_HOME -> {
                if (hasIsland) {
                    viewer.closeInventory();
                    viewer.performCommand("is home");
                } else {
                    plugin.gui().open(viewer, new ThemeSelectGui(plugin));
                }
            }
            case SLOT_MEMBERS -> {
                if (hasIsland) plugin.gui().open(viewer, new IslandMembersGui(plugin));
            }
            case SLOT_INFO -> {
                if (hasIsland) {
                    viewer.closeInventory();
                    viewer.performCommand("is info");
                }
            }
            case SLOT_UPGRADES -> {
                if (hasIsland) plugin.gui().open(viewer, new IslandUpgradesGui(plugin));
            }
            case SLOT_SETTINGS -> {
                if (hasIsland) plugin.gui().open(viewer, new IslandSettingsGui(plugin));
            }
            case SLOT_PERMISSIONS -> {
                if (hasIsland) plugin.gui().open(viewer, new IslandPermissionsGui(plugin));
            }
            case SLOT_INVITE -> plugin.messages().sendPrefixed(viewer, "island.invite-hint", Map.of());
            case SLOT_DELETE -> {
                viewer.closeInventory();
                viewer.performCommand("is delete");
            }
            case SLOT_GENS -> plugin.gui().open(viewer, new GensGui(plugin));
            case SLOT_SPAWNERS -> plugin.gui().open(viewer, new SpawnersGui(plugin));
            case SLOT_BORDER -> {
                final var owned = plugin.islands().ownedIsland(viewer.getUniqueId());
                if (owned.isEmpty()) {
                    if (hasIsland) plugin.messages().sendPrefixed(viewer, "island.not-owner", Map.of());
                } else {
                    refresh = plugin.islands().purchaseUpgrade(viewer, owned.get(), "border");
                }
            }
            case SLOT_VISIT -> plugin.messages().sendPrefixed(viewer, "island.visit-usage", Map.of());
            case SLOT_LEAVE -> {
                if (hasIsland) {
                    viewer.closeInventory();
                    viewer.performCommand("is leave");
                }
            }
            case SLOT_BUFFS -> {
                if (hasIsland) plugin.gui().open(viewer, new IslandBuffsGui(plugin));
            }
            case SLOT_CLOSE -> viewer.closeInventory();
            default -> { }
        }
        return refresh;
    }
}
