package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.RawYaml;
import com.coremc.core.util.YamlFiles;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * Cosmetic chat tags: catalogue, ownership, selection and the reward hook
 * other systems (crates, store, events) call to unlock a tag.
 *
 * Data model — everything is keyed by the STABLE tag id:
 *  - ownership: {@code owned-tags} on the player profile (+ config
 *    {@code default-owned} starters, + permissions),
 *  - selection: {@code equipped-tag} on the player profile.
 *
 * Display names, lore, materials and GUI slots are presentation only and
 * can change at any time without touching player data.
 *
 * Parsing is delegated to the Bukkit-free {@link TagCatalog}; this class
 * only adds file loading, Bukkit permission checks and persistence.
 */
public final class TagService {

    private static final String FILE_NAME = "tags.yml";

    private final CoreMCPlugin plugin;

    private volatile TagCatalog catalog = TagCatalog.empty();
    private volatile GuiLayout guiLayout = GuiLayout.defaults();

    /** GUI presentation settings for {@code /tags} (all config-driven). */
    public record GuiLayout(
            String title,
            int size,
            List<Integer> slots,
            int clearSlot,
            String clearMaterial,
            int closeSlot,
            String closeMaterial,
            int previousSlot,
            int nextSlot,
            String selectedMaterial,
            String lockedMaterial,
            boolean sounds) {

        public static GuiLayout defaults() {
            return new GuiLayout(
                    "&b&lCOREMC &8» &fTags",
                    54,
                    List.of(10, 11, 12, 13, 14, 15, 16,
                            19, 20, 21, 22, 23, 24, 25,
                            28, 29, 30, 31, 32, 33, 34),
                    49,
                    "BARRIER",
                    53,
                    "BARRIER",
                    45,
                    46,
                    "NAME_TAG",
                    "GRAY_DYE",
                    true);
        }
    }

    public TagService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads tags.yml (merging new bundled defaults); returns the tag count. */
    public int load() {
        YamlFiles.mergeNewDefaults(plugin, FILE_NAME);
        Map<String, Object> root;
        try {
            root = RawYaml.loadMap(new File(plugin.getDataFolder(), FILE_NAME));
        } catch (final RuntimeException exception) {
            plugin.getLogger().warning("[tags] " + FILE_NAME + " could not be read ("
                    + exception.getMessage() + ") — falling back to bundled defaults.");
            root = RawYaml.loadResource(plugin, FILE_NAME);
        }
        final List<String> problems = new ArrayList<>();
        final TagCatalog parsed = TagCatalog.parse(root, problems);
        for (final String problem : problems) {
            plugin.getLogger().warning("[tags] " + problem);
        }
        this.catalog = parsed;
        this.guiLayout = readLayout(root);
        return parsed.size();
    }

    private GuiLayout readLayout(final Map<String, Object> root) {
        final GuiLayout defaults = GuiLayout.defaults();
        final Object raw = root == null ? null : root.get("gui");
        if (!(raw instanceof Map<?, ?> gui)) {
            return defaults;
        }
        final List<Integer> slots = new ArrayList<>();
        if (gui.get("slots") instanceof Iterable<?> iterable) {
            for (final Object slot : iterable) {
                if (slot instanceof Number number) {
                    slots.add(number.intValue());
                }
            }
        }
        int size = (int) num(gui.get("size"), defaults.size());
        if (size % 9 != 0 || size < 9 || size > 54) {
            plugin.getLogger().warning("[tags] gui.size must be a multiple of 9 up to 54 — using 54.");
            size = 54;
        }
        return new GuiLayout(
                str(gui.get("title"), defaults.title()),
                size,
                slots.isEmpty() ? defaults.slots() : List.copyOf(slots),
                (int) num(gui.get("clear-slot"), defaults.clearSlot()),
                str(gui.get("clear-material"), defaults.clearMaterial()),
                (int) num(gui.get("close-slot"), defaults.closeSlot()),
                str(gui.get("close-material"), defaults.closeMaterial()),
                (int) num(gui.get("previous-slot"), defaults.previousSlot()),
                (int) num(gui.get("next-slot"), defaults.nextSlot()),
                str(gui.get("selected-material"), defaults.selectedMaterial()),
                str(gui.get("locked-material"), defaults.lockedMaterial()),
                !"false".equalsIgnoreCase(str(gui.get("sounds"), "true")));
    }

    private static String str(final Object value, final String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long num(final Object value, final long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    /** The live catalogue (immutable snapshot). */
    public TagCatalog catalog() {
        return catalog;
    }

    /** GUI layout from config. */
    public GuiLayout layout() {
        return guiLayout;
    }

    public Collection<TagDefinition> all() {
        return catalog.all();
    }

    public Optional<TagDefinition> byId(final String id) {
        return catalog.byId(id);
    }

    /** Permission predicate for an online player (thread-safe on Paper). */
    public static Predicate<String> permissionsOf(final Player player) {
        return player == null ? perm -> false : player::hasPermission;
    }

    /** Whether the player owns the tag (default / granted / permission). */
    public boolean owns(final Player player, final PlayerProfile profile, final TagDefinition tag) {
        return CosmeticAccess.ownsTag(profile, tag, permissionsOf(player));
    }

    /** The tag that should render in chat for this profile (null = none). */
    public TagDefinition renderable(final Player player, final PlayerProfile profile) {
        return CosmeticAccess.renderableTag(profile, catalog, permissionsOf(player));
    }

    /** Selects a tag for an online player and persists immediately. */
    public CosmeticAccess.Result select(
            final Player player, final PlayerProfile profile, final String tagId) {
        final CosmeticAccess.Result result =
                CosmeticAccess.selectTag(profile, catalog, tagId, permissionsOf(player));
        if (result == CosmeticAccess.Result.SELECTED || result == CosmeticAccess.Result.CLEARED) {
            plugin.playerData().persistImportant(profile);
        }
        return result;
    }

    /** Clears the selection and persists. */
    public boolean clear(final PlayerProfile profile) {
        final boolean changed = CosmeticAccess.clearTag(profile);
        if (changed) {
            plugin.playerData().persistImportant(profile);
        }
        return changed;
    }

    // ------------------------------------------------------- reward hooks

    /**
     * REWARD HOOK — unlocks a tag for an ONLINE player (crates, store,
     * events, quests). Paid ownership is written through to disk straight
     * away so a crash can never lose a purchase.
     *
     * @return true when the tag was newly unlocked
     */
    public boolean grant(final Player player, final String tagId) {
        final TagDefinition tag = catalog.byId(tagId).orElse(null);
        if (tag == null) {
            plugin.getLogger().warning("[tags] refusing to grant unknown tag '" + tagId + "'.");
            return false;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        final boolean granted = CosmeticAccess.grantTag(profile, tag.id());
        if (granted) {
            plugin.playerData().persistImportant(profile);
        }
        return granted;
    }

    /**
     * REWARD HOOK — unlocks a tag for a player that may be OFFLINE. Runs on
     * the profile I/O thread (never the main thread) and write-through
     * persists; the callback receives the outcome back on the main thread.
     */
    public void grantAsync(final UUID uuid, final String tagId, final java.util.function.Consumer<Boolean> callback) {
        mutateAsync(uuid, profile -> CosmeticAccess.grantTag(profile, tagId.toLowerCase(Locale.ROOT)), callback);
    }

    /** Staff hook — revokes a tag from a possibly-offline player. */
    public void revokeAsync(
            final UUID uuid, final String tagId, final java.util.function.Consumer<Boolean> callback) {
        final TagDefinition tag = catalog.byId(tagId).orElse(null);
        if (tag == null) {
            callback.accept(false);
            return;
        }
        mutateAsync(uuid, profile -> CosmeticAccess.revokeTag(profile, tag, perm -> false), callback);
    }

    /** Staff hook — clears the selected tag of a possibly-offline player. */
    public void clearAsync(final UUID uuid, final java.util.function.Consumer<Boolean> callback) {
        mutateAsync(uuid, CosmeticAccess::clearTag, callback);
    }

    /** Loads a possibly-offline profile, mutates it, persists and reports back. */
    void mutateAsync(
            final UUID uuid,
            final java.util.function.Function<PlayerProfile, Boolean> mutation,
            final java.util.function.Consumer<Boolean> callback) {
        final PlayerProfile cached = plugin.playerData().profileOf(uuid).orElse(null);
        if (cached != null) {
            final boolean changed = Boolean.TRUE.equals(mutation.apply(cached));
            if (changed) {
                plugin.playerData().persistImportant(cached);
            }
            callback.accept(changed);
            return;
        }
        plugin.playerData().ioExecute(() -> {
            final PlayerProfile profile = plugin.playerData().cachedOrLoad(uuid).orElse(null);
            final boolean changed = profile != null && Boolean.TRUE.equals(mutation.apply(profile));
            if (changed) {
                plugin.playerData().persistImportant(profile);
            }
            plugin.tasks().runLater(() -> callback.accept(changed), 1L);
        });
    }
}
