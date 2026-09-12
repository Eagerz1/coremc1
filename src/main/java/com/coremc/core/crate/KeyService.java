package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Owns crate keys: physical, PDC-identified items from the
 * {@code keys:} section of {@code crates.yml}.
 *
 * Identity: a {@code coremc:crate-key} PDC string holding the key id —
 * renamed vanilla items can never impersonate a key. Keys are granted
 * by enchant/vote/playtime rewards and consumed by the crate lineup
 * (same file, later phase); this service is deliberately
 * crate-agnostic so rewards can mint keys before crates exist.
 */
public final class KeyService {

    private final CoreMCPlugin plugin;
    private final NamespacedKey markerKey;
    private final Map<String, CrateKey> keys = new LinkedHashMap<>();

    public KeyService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "crate-key");
    }

    /** (Re)loads {@code crates.yml} keys; returns the key count. */
    public int load() {
        com.coremc.core.util.YamlFiles.mergeNewDefaults(plugin, "crates.yml");
        final File file = new File(plugin.getDataFolder(), "crates.yml");
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        final ConfigurationSection root = yaml.getConfigurationSection("keys");
        final Map<String, CrateKey> parsed = new LinkedHashMap<>();
        if (root == null) {
            plugin.getLogger().warning("[crates] missing 'keys:' section — no keys available.");
        } else {
            for (final String id : root.getKeys(false)) {
                final ConfigurationSection def = root.getConfigurationSection(id);
                if (def == null) {
                    plugin.getLogger().warning("[crates] key '" + id + "' must be a map — skipped.");
                    continue;
                }
                parsed.put(
                        id.toLowerCase(Locale.ROOT),
                        new CrateKey(
                                id.toLowerCase(Locale.ROOT),
                                def.getString("display", "&e" + id + " key"),
                                def.getString("icon", "TRIPWIRE_HOOK").trim().toUpperCase(Locale.ROOT)));
            }
        }
        keys.clear();
        keys.putAll(parsed);
        return parsed.size();
    }

    public Optional<CrateKey> key(final String keyId) {
        return Optional.ofNullable(keys.get(keyId.toLowerCase(Locale.ROOT)));
    }

    public Collection<CrateKey> all() {
        return java.util.Collections.unmodifiableCollection(keys.values());
    }

    /** Mints {@code amount} key items, or null for an unknown key id. */
    public ItemStack mint(final String keyId, final int amount) {
        final CrateKey key = keys.get(keyId.toLowerCase(Locale.ROOT));
        if (key == null || amount <= 0) {
            return null;
        }
        Material icon = Material.matchMaterial(key.icon());
        if (icon == null || icon.isAir()) {
            icon = Material.TRIPWIRE_HOOK;
        }
        final ItemStack item = new ItemStack(icon, Math.min(amount, 64));
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(key.display()));
            meta.setLore(List.of(
                    ColorUtil.colorize("&7Crate key — use at &f/crates&7."),
                    ColorUtil.colorize("&8Single use.")));
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, key.id());
            item.setItemMeta(meta);
        }
        if (amount > 64) {
            // Stack overflow is handled by giveKeys (repeated mints), never here.
            item.setAmount(64);
        }
        return item;
    }

    /** The key id of an item, or empty for non-key items. */
    public Optional<String> keyIdOf(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        final String id = item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        return id == null ? Optional.empty() : Optional.of(id);
    }

    /** Counts key items of {@code keyId} in the player's inventory. */
    public int countKeys(final Player player, final String keyId) {
        int total = 0;
        for (final ItemStack item : player.getInventory().getContents()) {
            if (item != null && keyIdOf(item).map(keyId::equalsIgnoreCase).orElse(false)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} keys; true iff all {@code amount} were removed. */
    public boolean takeKeys(final Player player, final String keyId, final int amount) {
        int remaining = amount;
        final ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            final ItemStack item = contents[slot];
            // Only PDC-tagged stacks with the matching id are keys; a plain
            // (marker-less) item must never be treated as one. NB: the old
            // `... .orElse(true) == false` expression parsed as
            // `... || (x == false)`, so marker-less items matched and the
            // real keys were never consumed.
            if (item == null) {
                continue;
            }
            final Optional<String> onStack = keyIdOf(item);
            if (onStack.isEmpty() || !onStack.get().equalsIgnoreCase(keyId)) {
                continue;
            }
            final int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            player.getInventory().setItem(slot, item.getAmount() <= 0 ? null : item);
        }
        return remaining <= 0;
    }

    /** Gives {@code amount} keys; overflow drops at the player's feet (never voided). */
    public void giveKeys(final Player player, final String keyId, final int amount) {
        int remaining = Math.max(0, amount);
        final List<ItemStack> mints = new ArrayList<>();
        while (remaining > 0) {
            final ItemStack mint = mint(keyId, Math.min(remaining, 64));
            if (mint == null) {
                plugin.getLogger().warning("Tried to give unknown crate key '" + keyId + "'.");
                return;
            }
            mints.add(mint);
            remaining -= mint.getAmount();
        }
        for (final ItemStack stack : mints) {
            player.getInventory().addItem(stack).values()
                    .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
    }
}
