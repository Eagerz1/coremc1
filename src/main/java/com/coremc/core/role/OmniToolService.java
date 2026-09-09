package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Owns the OmniTool: a soulbound, PDC-identified netherite pickaxe the
 * player receives when selecting a role.
 *
 * Identity: a {@link NamespacedKey} marker + role binding + issued tool
 * level in the item's {@link org.bukkit.persistence.PersistentDataContainer}
 * — ordinary items cannot impersonate it (players cannot forge NBT).
 *
 * Anti-duplication contract:
 *  - granting always removes every existing OmniTool from the player's
 *    inventory first (role switching replaces, never stacks),
 *  - dropping is cancelled, storing in external containers is cancelled,
 *  - death never loses it: OmniTools are stripped from drops and
 *    re-inserted on respawn (soulbound), even without keepInventory.
 */
public final class OmniToolService {

    private final CoreMCPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey roleKey;

    /** uuid -> tools held in trust across death until respawn. */
    private final Map<UUID, List<ItemStack>> respawnTrust = new ConcurrentHashMap<>();

    /** Purchased-upgrade catalogue (config-driven; {@code omnitool.upgrades}). */
    private volatile OmniUpgradeCatalog upgrades = new OmniUpgradeCatalog(Map.of());

    public OmniToolService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "omnitool");
        this.roleKey = new NamespacedKey(plugin, "omnitool-role");
    }

    /** (Re)loads {@code omnitool.upgrades} from config. Returns loaded count. */
    public int load() {
        final org.bukkit.configuration.ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("omnitool.upgrades");
        final Map<String, OmniUpgradeCatalog.Upgrade> parsed = new java.util.LinkedHashMap<>();
        if (section != null) {
            for (final String id : section.getKeys(false)) {
                final org.bukkit.configuration.ConfigurationSection def =
                        section.getConfigurationSection(id);
                if (def == null) {
                    continue;
                }
                final int maxLevel = Math.max(1, def.getInt("max-level", 1));
                final List<Long> costs = new ArrayList<>();
                for (final long cost : def.getLongList("costs")) {
                    costs.add(Math.max(0L, cost));
                }
                while (costs.size() < maxLevel) {
                    costs.add(0L); // tolerant defaults: missing tiers become free
                }
                try {
                    parsed.put(id, new OmniUpgradeCatalog.Upgrade(
                            id, def.getString("display", "&f" + id), maxLevel, costs.subList(0, maxLevel)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("OmniTool upgrade '" + id + "' skipped: " + e.getMessage());
                }
            }
        }
        this.upgrades = new OmniUpgradeCatalog(parsed);
        return parsed.size();
    }

    public OmniUpgradeCatalog upgrades() {
        return upgrades;
    }

    public boolean isOmniTool(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** The role an OmniTool is bound to (null for imposter items). */
    public Role boundRole(final ItemStack item) {
        if (!isOmniTool(item)) {
            return null;
        }
        final String key =
                item.getItemMeta().getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        return key == null ? null : Role.byKey(key).orElse(null);
    }

    /** Builds a fresh OmniTool bound to {@code role}, stamped with the tool level
     * and every OmniTool upgrade the owner has purchased. */
    public ItemStack create(final Role role, final PlayerProfile profile) {
        final ItemStack item = new ItemStack(Material.NETHERITE_PICKAXE);
        final ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ColorUtil.colorize("&b&lOmni-Tool"));
        final List<String> lore = new ArrayList<>();
        lore.add(ColorUtil.colorize("&7Role: " + role.display()));
        lore.add(ColorUtil.colorize("&7OmniTool level: &b" + profile.omniToolLevel()));
        lore.add(ColorUtil.colorize("&8Shift right-click to open the Omni Panel."));
        lore.add(ColorUtil.colorize("&8Soulbound — cannot be dropped or lost on death."));
        appendUpgradeLore(lore, profile);
        meta.setLore(lore);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role.key());
        applyUpgradeEnchants(meta, profile);
        item.setItemMeta(meta);
        return item;
    }

    /** Tool lore line per owned upgrade (level/shield visibility = investment proof). */
    private void appendUpgradeLore(final List<String> lore, final PlayerProfile profile) {
        for (final Map.Entry<String, OmniUpgradeCatalog.Upgrade> entry : upgrades.all().entrySet()) {
            final int level = profile.omniUpgrade(entry.getKey());
            if (level > 0) {
                lore.add(ColorUtil.colorize("&7" + ColorUtil.colorize(entry.getValue().display())
                        + " &8→ &b" + level + "&7/&b" + entry.getValue().maxLevel()));
            }
        }
        // Custom-enchant summary (owned track levels — detail lives in the enchant GUI).
        final Map<String, Integer> enchants = profile.enchantLevels();
        if (!enchants.isEmpty()) {
            int levels = 0;
            for (final int owned : enchants.values()) {
                levels += owned;
            }
            lore.add(ColorUtil.colorize(
                    "&7Custom enchants: &d" + enchants.size() + " &8(&d" + levels + " levels&8)"));
        }
    }

    /** Stamps the enchantments implied by purchased upgrades (Efficiency/Fortune);
     * the smelter is behavioural (see {@code OmniToolListener#onBlockBreak}). */
    private void applyUpgradeEnchants(final ItemMeta meta, final PlayerProfile profile) {
        stamp(meta, enchantByKey("efficiency"), profile.omniUpgrade(OmniUpgradeCatalog.EFFICIENCY));
        stamp(meta, enchantByKey("fortune"), profile.omniUpgrade(OmniUpgradeCatalog.FORTUNE));
    }

    /**
     * Resolves an enchantment by namespaced key ({@code in} since Bukkit 1.14).
     * Key names are permanently stable, unlike the legacy enum constants
     * (renamed in 1.20.5), so this compiles and runs on both API eras.
     */
    private static org.bukkit.enchantments.Enchantment enchantByKey(final String key) {
        return org.bukkit.enchantments.Enchantment.getByKey(new NamespacedKey("minecraft", key));
    }

    private void stamp(
            final ItemMeta meta, final org.bukkit.enchantments.Enchantment enchantment, final int level) {
        if (enchantment == null) {
            return; // enchant unavailable on this server version — upgrades stay profile-only
        }
        if (level <= 0) {
            meta.removeEnchant(enchantment);
        } else {
            meta.addEnchant(enchantment, level, true);
        }
    }

    /**
     * Re-stamps every OmniTool the player currently carries after a purchase —
     * the item stays exactly where it was (no re-grant, no position churn).
     */
    public void refreshHeldTools(final Player player, final PlayerProfile profile) {
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack item = inventory.getItem(slot);
            if (!isOmniTool(item)) {
                continue;
            }
            final ItemMeta meta = item.getItemMeta();
            applyUpgradeEnchants(meta, profile);
            // Rebuild lore deterministically from the template (upgrade lines
            // must never be duplicated across refresh cycles).
            final Role role = boundRole(item);
            if (role != null) {
                final List<String> fresh = new ArrayList<>();
                fresh.add(ColorUtil.colorize("&7Role: " + role.display()));
                fresh.add(ColorUtil.colorize("&7OmniTool level: &b" + profile.omniToolLevel()));
                fresh.add(ColorUtil.colorize("&8Shift right-click to open the Omni Panel."));
                fresh.add(ColorUtil.colorize("&8Soulbound — cannot be dropped or lost on death."));
                appendUpgradeLore(fresh, profile);
                meta.setLore(fresh);
            }
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    /**
     * Replaces every OmniTool in the player's inventory with a fresh one
     * bound to the current role (no stacking/duplication possible). The
     * tool level survives because it lives in the profile.
     */
    public void grantFresh(final Player player, final Role role, final PlayerProfile profile) {
        final int removed = removeAllOmniTools(player);
        if (removed > 0) {
            plugin.getLogger().fine("Removed " + removed + " old OmniTool(s) from " + player.getName());
        }
        final ItemStack tool = create(role, profile);
        final Map<Integer, ItemStack> overflow = player.getInventory().addItem(tool);
        if (!overflow.isEmpty()) {
            // Inventory full: never drop it on the ground (exploitable) —
            // place into the ender chest as the last safe personal storage,
            // else delete and rely on /role re-granting later.
            final Map<Integer, ItemStack> enderOverflow = player.getEnderChest().addItem(tool);
            if (!enderOverflow.isEmpty()) {
                plugin.getLogger().warning(player.getName()
                        + " had no space for the OmniTool (inventory + ender chest full); /role will re-grant.");
                player.sendMessage(ColorUtil.colorize(
                        "&b&lCOREMC &8» &fYour inventory was full — run &b/role &fonce you have space again."));
            } else {
                player.sendMessage(ColorUtil.colorize("&b&lCOREMC &8» &fInventory full — Omni-Tool placed in your ender chest."));
            }
        }
    }

    /** Removes every OmniTool in the inventory, on the cursor and in the ender chest; returns count. */
    public int removeAllOmniTools(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        int removed = 0;
        final ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isOmniTool(contents[slot])) {
                removed += contents[slot].getAmount();
                inventory.setItem(slot, null);
            }
        }
        if (isOmniTool(player.getItemOnCursor())) {
            removed += player.getItemOnCursor().getAmount();
            player.setItemOnCursor(null);
        }
        // The ender chest is grantFresh's overflow vault: tools can only get
        // there from our own grants, so a fresh grant reclaims them too
        // (role switches replace, never stack — everywhere).
        final ItemStack[] ender = player.getEnderChest().getContents();
        for (int slot = 0; slot < ender.length; slot++) {
            if (isOmniTool(ender[slot])) {
                removed += ender[slot].getAmount();
                player.getEnderChest().setItem(slot, null);
            }
        }
        return removed;
    }

    /** True if the player currently carries at least one OmniTool. */
    public boolean ownsOmniTool(final Player player) {
        for (final ItemStack item : player.getInventory().getContents()) {
            if (isOmniTool(item)) {
                return true;
            }
        }
        return false;
    }

    /** The OmniTool in the player's main hand, if any. */
    public ItemStack toolInMainHand(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        return isOmniTool(item) ? item : null;
    }

    // ------------------------------------------------------------------ death handling (soulbound)

    /** Called from PlayerDeathEvent: pulls OmniTools out of the drop list. */
    public void onDeath(final Player player, final List<ItemStack> drops) {
        final List<ItemStack> held = new ArrayList<>();
        drops.removeIf(item -> {
            if (isOmniTool(item)) {
                held.add(item);
                return true;
            }
            return false;
        });
        if (!held.isEmpty()) {
            respawnTrust.put(player.getUniqueId(), held);
        }
    }

    /** Called from PlayerRespawnEvent: returns held tools. */
    public void onRespawn(final Player player) {
        restoreTrust(player);
    }

    /**
     * Returns trusted tools immediately (respawn OR quit-at-death-screen).
     * Idempotent via map removal. Post-death the inventory is empty, so
     * addItem cannot overflow; if a future edge ever makes it do, the
     * remainder drops at the player instead of being lost.
     */
    public void restoreTrust(final Player player) {
        final List<ItemStack> held = respawnTrust.remove(player.getUniqueId());
        if (held == null) {
            return;
        }
        for (final ItemStack tool : held) {
            final Map<Integer, ItemStack> overflow = player.getInventory().addItem(tool);
            overflow.values().forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
    }

    /** Plugin disable hook (defensive; trust map is short-lived in-memory only). */
    public void clearTransient() {
        respawnTrust.clear();
    }

    // ------------------------------------------------------------------ purchased upgrades

    /**
     * Buys the next level of {@code upgradeId} for {@code player}: fully
     * transactional — unlock check → price check → withdraw → persist
     * (permanent paid progression) → re-stamp held tools → confirm.
     * Returns true when the level was granted, false when the player was
     * denied (unknown id, maxed level, or too few Credits).
     */
    public boolean purchaseUpgrade(final Player player, final PlayerProfile profile, final String upgradeId) {
        final java.util.Optional<OmniUpgradeCatalog.Upgrade> found = upgrades.upgrade(upgradeId);
        if (found.isEmpty()) {
            plugin.messages().sendPrefixed(player, "omnitool.upgrade-unknown", Map.of());
            return false;
        }
        final OmniUpgradeCatalog.Upgrade def = found.get();
        final int current = profile.omniUpgrade(upgradeId);
        if (def.maxed(current)) {
            plugin.messages().sendPrefixed(player, "omnitool.upgrade-maxed", Map.of());
            return false;
        }
        final long price = def.costForNextLevel(current);
        final String priceText = String.format(java.util.Locale.ROOT, "%,d", price);
        if (price > 0L && !plugin.economy().withdraw(profile, com.coremc.core.economy.Currency.CREDITS, price)) {
            plugin.messages().sendPrefixed(player, "omnitool.insufficient", Map.of("price", priceText));
            return false;
        }
        profile.setOmniUpgrade(upgradeId, current + 1);
        plugin.playerData().persistImportant(profile); // permanent paid progression — write through
        refreshHeldTools(player, profile);
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
        plugin.messages().sendPrefixed(player, "omnitool.upgrade-bought", Map.of(
                "upgrade", ColorUtil.colorize(def.display()),
                "level", (current + 1) + "/" + def.maxLevel(),
                "price", priceText));
        return true;
    }
}
