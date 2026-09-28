package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.Role;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.RawYaml;
import com.coremc.core.util.YamlFiles;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Animated skin registry, ownership and selection.
 *
 * Ownership and equipped selections live in the player profile by stable
 * skin id, so changing roles, upgrading the OmniTool, relogging, trading,
 * dropping or restarting can never remove ownership — the visual layer is
 * re-stamped onto the (unchanged) soulbound tool from the profile every
 * time it is granted or refreshed.
 *
 * Reusable grant/revoke hooks (crates, events, store, staff commands):
 * {@link #grant(UUID, String)} / {@link #revoke(UUID, String)} work for
 * online and offline players. Nothing is ever granted automatically: new
 * players start with zero skins.
 *
 * Season resets honour the configurable policy in skins.yml
 * ({@code season-reset-keep-sources}) and never wipe sources listed in
 * {@code always-keep-sources} (store/crate purchases by default).
 */
public final class SkinService {

    private final CoreMCPlugin plugin;
    private final NamespacedKey toolSkinKey;
    private volatile SkinCatalog catalog = new SkinCatalog(Set.of(), Set.of());

    public SkinService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.toolSkinKey = new NamespacedKey(plugin, "tool-skin");
    }

    /** The PDC marker identifying which skin a tool currently wears. */
    public NamespacedKey toolSkinKey() {
        return toolSkinKey;
    }

    /** (Re)loads skins.yml (merge-safe: admin values on disk win). Returns the skin count. */
    public int load() {
        YamlFiles.mergeNewDefaults(plugin, "skins.yml");
        final Map<String, Object> raw =
                RawYaml.loadMap(new File(plugin.getDataFolder(), "skins.yml"));
        final SkinCatalog parsed = SkinCatalog.parse(raw);
        for (final String problem : parsed.problems()) {
            plugin.getLogger().warning("[skins] " + problem);
        }
        this.catalog = parsed;
        return parsed.all().size();
    }

    public SkinCatalog catalog() {
        return catalog;
    }

    public Optional<Skin> skin(final String id) {
        return catalog.skin(id);
    }

    public Collection<Skin> hats() {
        return catalog.hats();
    }

    // ------------------------------------------------------------------
    // item visuals
    // ------------------------------------------------------------------

    /**
     * Stamps a skin's presentation onto an existing item meta: ONLY the
     * model layer and the cosmetic marker change. Damage, enchants,
     * levels, upgrades, lore and every pre-existing PDC key are untouched.
     */
    public void stampToolSkin(final ItemMeta meta, final Skin skin) {
        meta.setCustomModelData(skin.modelId());
        meta.getPersistentDataContainer().set(toolSkinKey, PersistentDataType.STRING, skin.id());
    }

    /** Removes the cosmetic layer, restoring the base (role) model id. */
    public void clearToolSkin(final ItemMeta meta, final int baseModelId) {
        meta.setCustomModelData(baseModelId <= 0 ? null : baseModelId);
        meta.getPersistentDataContainer().remove(toolSkinKey);
    }

    /** The skin id currently worn by an item (empty = default look). */
    public Optional<String> toolSkinIdOf(final ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta()
                .getPersistentDataContainer().get(toolSkinKey, PersistentDataType.STRING));
    }

    /**
     * A display-only copy of the player's OmniTool wearing {@code skin} —
     * the GUI preview. The real tool is never modified until Apply. When
     * the previewed skin belongs to a role other than the held tool's, a
     * synthetic tool of that role is previewed so the silhouette matches.
     */
    public ItemStack previewToolStack(final Player player, final Skin skin) {
        final PlayerProfile profile = profileOf(player);
        final ItemStack tool = plugin.omniTool().toolInMainHand(player);
        final Role heldRole = plugin.omniTool().boundRole(tool);
        final ItemStack copy;
        if (tool != null && heldRole == skin.role()) {
            copy = tool.clone();
        } else if (profile != null) {
            copy = plugin.omniTool().create(skin.role(), profile);
        } else {
            copy = new ItemStack(Material.NETHERITE_PICKAXE);
            final ItemMeta base = copy.getItemMeta();
            if (base != null) {
                base.setDisplayName(ColorUtil.colorize("&b&lOmni-Tool"));
                copy.setItemMeta(base);
            }
        }
        final ItemMeta meta = copy.getItemMeta();
        stampToolSkin(meta, skin);
        final List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ColorUtil.colorize("&8Skin preview: &7" + skin.display()));
        meta.setLore(lore);
        meta.setEnchantmentGlintOverride(true);
        copy.setItemMeta(meta);
        return copy;
    }

    /** The display item for a hat (used in GUIs and the worn overlay). */
    public ItemStack hatStack(final Skin skin) {
        final ItemStack item = new ItemStack(skin.material());
        final ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ColorUtil.colorize(skin.display()));
        meta.setLore(List.of(ColorUtil.colorize("&8" + skin.description())));
        meta.setCustomModelData(skin.modelId());
        meta.getPersistentDataContainer().set(toolSkinKey, PersistentDataType.STRING, skin.id());
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------
    // selection (equipped look)
    // ------------------------------------------------------------------

    /** Result of an equip attempt, for exact messaging. */
    public enum EquipResult {
        OK,
        NOT_OWNED,
        WRONG_ROLE,
        NO_TOOL
    }

    /**
     * Equips a tool skin for its role: records the selection in the
     * profile, re-stamps every held OmniTool of that role (visual layer
     * only) and persists. Ownership and role compatibility are enforced.
     */
    public EquipResult equipToolSkin(final Player player, final PlayerProfile profile, final Skin skin) {
        if (skin.type() != SkinType.TOOL) {
            return EquipResult.WRONG_ROLE;
        }
        if (!profile.ownsSkin(skin.id())) {
            return EquipResult.NOT_OWNED;
        }
        profile.equipToolSkin(skin.role().key(), skin.id());
        plugin.playerData().persistImportant(profile);
        plugin.omniTool().refreshHeldTools(player, profile);
        return EquipResult.OK;
    }

    /** Clears the equipped tool skin for a role and re-stamps held tools. */
    public void clearToolSkin(final Player player, final PlayerProfile profile, final Role role) {
        profile.equipToolSkin(role.key(), null);
        plugin.playerData().persistImportant(profile);
        plugin.omniTool().refreshHeldTools(player, profile);
    }

    /** Equips a hat (overlay only; the real helmet stays untouched). */
    public boolean equipHat(final Player player, final PlayerProfile profile, final Skin skin) {
        if (skin.type() != SkinType.HAT || !profile.ownsSkin(skin.id())) {
            return false;
        }
        profile.equipHat(skin.id());
        plugin.playerData().persistImportant(profile);
        plugin.hatOverlay().equip(player, skin);
        return true;
    }

    /** Removes the worn hat overlay. */
    public void clearHat(final Player player, final PlayerProfile profile) {
        profile.equipHat(null);
        plugin.playerData().persistImportant(profile);
        plugin.hatOverlay().unequip(player);
    }

    // ------------------------------------------------------------------
    // grant / revoke hooks (crates, events, store, staff)
    // ------------------------------------------------------------------

    /** Outcome of a grant/revoke hook call. */
    public record HookResult(boolean success, boolean already, boolean online, String username) {
    }

    /**
     * Grants ownership of {@code skinId} to {@code uuid}. Works online and
     * offline; online players get the visual layer restamped immediately.
     * Returns empty when the skin id is unknown.
     */
    public Optional<HookResult> grant(final UUID uuid, final String skinId) {
        final Skin skin = skin(skinId).orElse(null);
        if (skin == null) {
            return Optional.empty();
        }
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            final PlayerProfile profile = profileOf(online);
            if (profile == null) {
                return Optional.empty();
            }
            final boolean added = profile.grantSkin(skin.id());
            plugin.playerData().persistImportant(profile);
            plugin.omniTool().refreshHeldTools(online, profile); // in case the skin is already equipped
            return Optional.of(new HookResult(true, !added, true, online.getName()));
        }
        return Optional.of(mutateOffline(uuid, profile -> profile.grantSkin(skin.id())));
    }

    /** Revokes ownership (and unequips it everywhere). Online + offline. */
    public Optional<HookResult> revoke(final UUID uuid, final String skinId) {
        final Skin skin = skin(skinId).orElse(null);
        if (skin == null) {
            return Optional.empty();
        }
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            final PlayerProfile profile = profileOf(online);
            if (profile == null) {
                return Optional.empty();
            }
            final boolean removed = profile.revokeSkin(skin.id());
            plugin.playerData().persistImportant(profile);
            plugin.omniTool().refreshHeldTools(online, profile);
            if (skin.id().equals(profile.equippedHat())) {
                profile.equipHat(null);
                plugin.hatOverlay().unequip(online);
            }
            return Optional.of(new HookResult(true, !removed, true, online.getName()));
        }
        return Optional.of(mutateOffline(uuid, profile -> profile.revokeSkin(skin.id())));
    }

    /** Applies the configurable season-reset policy; returns the removed skin ids. */
    public List<String> applySeasonReset(final UUID uuid) {
        final SkinCatalog cat = catalog;
        final List<String> removed = new ArrayList<>();
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            final PlayerProfile profile = profileOf(online);
            if (profile != null) {
                removed.addAll(applySeasonReset(cat, profile));
                plugin.playerData().persistImportant(profile);
                plugin.omniTool().refreshHeldTools(online, profile);
                plugin.hatOverlay().syncWithProfile(online, profile);
            }
            return removed;
        }
        mutateOffline(uuid, profile -> removed.addAll(applySeasonReset(cat, profile)));
        return removed;
    }

    /** Policy core: remove owned skins whose source does not survive. */
    private List<String> applySeasonReset(final SkinCatalog cat, final PlayerProfile profile) {
        final List<String> removed = new ArrayList<>();
        for (final String id : new LinkedHashSet<>(profile.ownedSkins())) {
            final Skin skin = cat.skin(id).orElse(null);
            final String source = skin == null ? "unknown" : skin.source();
            if (skin == null || !cat.survivesSeasonReset(source)) {
                profile.revokeSkin(id); // also unequips it
                removed.add(id);
            }
        }
        return removed;
    }

    private HookResult mutateOffline(final UUID uuid, final java.util.function.Consumer<PlayerProfile> mutation) {
        final PlayerDataService data = plugin.playerData();
        final PlayerProfile profile = data.cachedOrLoad(uuid).orElse(null);
        if (profile == null) {
            return new HookResult(false, false, false, "unknown");
        }
        mutation.accept(profile);
        data.persistImportant(profile);
        return new HookResult(true, false, false, profile.username());
    }

    private PlayerProfile profileOf(final Player player) {
        return plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
    }

    // ------------------------------------------------------------------
    // helpers for GUIs
    // ------------------------------------------------------------------

    /** Owned-count line for info panes. */
    public String ownedSummary(final PlayerProfile profile) {
        int owned = 0;
        for (final Skin skin : catalog.all()) {
            if (profile.ownsSkin(skin.id())) {
                owned++;
            }
        }
        return owned + "/" + catalog.all().size();
    }

    /** All tool skins in catalog order, optionally filtered. */
    public List<Skin> toolSkins(final String collectionId, final Role role) {
        final List<Skin> out = new ArrayList<>();
        for (final Skin skin : catalog.all()) {
            if (skin.type() != SkinType.TOOL) {
                continue;
            }
            if (collectionId != null && !skin.collectionId().equals(collectionId)) {
                continue;
            }
            if (role != null && skin.role() != role) {
                continue;
            }
            out.add(skin);
        }
        return out;
    }

    /** Human-readable unlock source for lore. */
    public static String sourceLabel(final Skin skin) {
        final String source = skin.source() == null ? "event" : skin.source().toLowerCase(Locale.ROOT);
        if (source.startsWith("crate:")) {
            return "&eCrate reward&8 (&f" + source.substring(6) + "&8)";
        }
        return switch (source) {
            case "store" -> "&aStore purchase";
            case "event" -> "&dEvent reward";
            case "seasonal" -> "&bSeasonal reward";
            case "default" -> "&fFree";
            default -> "&f" + source;
        };
    }

    /** Convenience: material for a collection filter icon (null-safe). */
    public Material filterIcon(final SkinCollection collection) {
        return collection.filterIcon() == null ? Material.NAME_TAG : collection.filterIcon();
    }
}
