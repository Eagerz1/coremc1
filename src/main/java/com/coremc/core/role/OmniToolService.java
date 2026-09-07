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

    public OmniToolService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "omnitool");
        this.roleKey = new NamespacedKey(plugin, "omnitool-role");
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

    /** Builds a fresh OmniTool bound to {@code role}, stamped with the tool level. */
    public ItemStack create(final Role role, final int toolLevel) {
        final ItemStack item = new ItemStack(Material.NETHERITE_PICKAXE);
        final ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ColorUtil.colorize("&b&lOmni-Tool"));
        final List<String> lore = new ArrayList<>();
        lore.add(ColorUtil.colorize("&7Role: " + role.display()));
        lore.add(ColorUtil.colorize("&7OmniTool level: &b" + toolLevel));
        lore.add(ColorUtil.colorize("&8Shift right-click to open the Omni Panel."));
        lore.add(ColorUtil.colorize("&8Soulbound — cannot be dropped or lost on death."));
        meta.setLore(lore);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role.key());
        item.setItemMeta(meta);
        return item;
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
        final ItemStack tool = create(role, profile.omniToolLevel());
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

    /** Removes every OmniTool anywhere in the player's inventory; returns count. */
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
}
