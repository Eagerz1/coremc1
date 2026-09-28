package com.coremc.core.cosmetic;

import com.coremc.core.role.Role;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;

/**
 * Parsed contents of {@code skins.yml}: the 33 animated skins, their
 * collections and the season-reset policy.
 *
 * Parsing is a pure map transform so unit tests can exercise it without a
 * server (the same pattern as the themes/enchant catalogues). Tolerant by
 * design: a malformed entry is skipped, never fatal.
 */
public final class SkinCatalog {

    private final Map<String, SkinCollection> collections = new LinkedHashMap<>();
    private final Map<String, Skin> skins = new LinkedHashMap<>();
    private final List<Skin> hats = new ArrayList<>();
    /** Sources that survive a season reset; empty set = keep everything. */
    private final Set<String> seasonKeepSources;
    private final Set<String> alwaysKeepSources;
    private final List<String> problems = new ArrayList<>();

    private SkinCatalog(
            final Set<String> seasonKeepSources, final Set<String> alwaysKeepSources) {
        this.seasonKeepSources = seasonKeepSources;
        this.alwaysKeepSources = alwaysKeepSources;
    }

    /** An empty catalogue (used before the first successful load). */
    public static SkinCatalog empty() {
        return new SkinCatalog(Set.of(), Set.of());
    }

    /**
     * Parses the raw {@code skins.yml} map.
     *
     * <p>Expected shape (see the bundled file):
     * {@code skins: { season-reset-keep-sources: all|[...], always-keep-sources: [...],
     * collections: { <id>: { display, description, filter-icon, source, tool-skins: {
     * <skinId>: { display, role, model-id, material, ... } } } },
     * hat-skins: { <id>: { display, model-id, material, ... } } } }</p>
     */
    public static SkinCatalog parse(final Map<String, Object> root) {
        final Map<String, Object> skinsRoot = section(root.get("skins"));
        final Set<String> keep = parseKeepSources(skinsRoot.get("season-reset-keep-sources"));
        final Set<String> alwaysKeep = parseKeepSources(skinsRoot.get("always-keep-sources"));
        final SkinCatalog catalog = new SkinCatalog(keep, alwaysKeep);
        if (skinsRoot.isEmpty()) {
            catalog.problems.add("no 'skins:' section found");
            return catalog;
        }
        for (final Map.Entry<String, Object> entry : section(skinsRoot.get("collections")).entrySet()) {
            catalog.parseCollection(entry.getKey(), section(entry.getValue()));
        }
        for (final Map.Entry<String, Object> entry : section(skinsRoot.get("hat-skins")).entrySet()) {
            catalog.parseHat(entry.getKey(), section(entry.getValue()));
        }
        return catalog;
    }

    private void parseCollection(final String id, final Map<String, Object> def) {
        final String display = str(def.get("display"), "&f" + id);
        final String description = str(def.get("description"), "");
        final Material icon = material(def.get("filter-icon"), Material.NAME_TAG, id);
        final String source = str(def.get("source"), "event");
        final List<Skin> toolSkins = new ArrayList<>();
        for (final Map.Entry<String, Object> skinEntry : section(def.get("tool-skins")).entrySet()) {
            final String skinId = skinEntry.getKey().toLowerCase(Locale.ROOT);
            final Map<String, Object> skinDef = section(skinEntry.getValue());
            final Role role = Role.byKey(str(skinDef.get("role"), "")).orElse(null);
            final Integer modelId = intOf(skinDef.get("model-id"));
            final Material material = material(skinDef.get("material"), Material.NETHERITE_PICKAXE, skinId);
            if (role == null || modelId == null) {
                problems.add("collection '" + id + "' skin '" + skinId + "': bad role or model-id — skipped");
                continue;
            }
            try {
                final Skin skin = new Skin(skinId, SkinType.TOOL, id,
                        str(skinDef.get("display"), display + " " + role.display()),
                        description, role, modelId, material, source);
                toolSkins.add(skin);
                register(skin);
            } catch (final IllegalArgumentException exception) {
                problems.add("collection '" + id + "' skin '" + skinId + "': " + exception.getMessage());
            }
        }
        if (toolSkins.isEmpty()) {
            problems.add("collection '" + id + "' has no tool skins");
        }
        collections.put(id, new SkinCollection(id, display, description, icon, source, toolSkins));
    }

    private void parseHat(final String id, final Map<String, Object> def) {
        final String skinId = id.toLowerCase(Locale.ROOT);
        final Integer modelId = intOf(def.get("model-id"));
        final Material material = material(def.get("material"), Material.CARVED_PUMPKIN, skinId);
        if (modelId == null) {
            problems.add("hat '" + skinId + "': bad model-id — skipped");
            return;
        }
        try {
            final Skin skin = new Skin(skinId, SkinType.HAT, str(def.get("collection"), "hats"),
                    str(def.get("display"), "&f" + skinId),
                    str(def.get("description"), ""), null, modelId, material,
                    str(def.get("source"), "event"));
            hats.add(skin);
            register(skin);
        } catch (final IllegalArgumentException exception) {
            problems.add("hat '" + skinId + "': " + exception.getMessage());
        }
    }

    private void register(final Skin skin) {
        if (skins.containsKey(skin.id())) {
            problems.add("duplicate skin id '" + skin.id() + "' — first definition wins");
            return;
        }
        skins.put(skin.id(), skin);
    }

    public Optional<Skin> skin(final String id) {
        return Optional.ofNullable(id == null ? null : skins.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<Skin> all() {
        return List.copyOf(skins.values());
    }

    public Collection<SkinCollection> collections() {
        return List.copyOf(collections.values());
    }

    public Optional<SkinCollection> collection(final String id) {
        return Optional.ofNullable(collections.get(id));
    }

    public List<Skin> hats() {
        return List.copyOf(hats);
    }

    /** Tool skins for one role across all collections, catalog order. */
    public List<Skin> toolSkinsForRole(final Role role) {
        final List<Skin> out = new ArrayList<>();
        for (final Skin skin : skins.values()) {
            if (skin.fitsRole(role)) {
                out.add(skin);
            }
        }
        return out;
    }

    /**
     * Whether a source id survives the season reset. A keep entry protects
     * both its exact id and its family: {@code crate} keeps {@code crate:ember}.
     * An empty policy set ("all") keeps everything.
     */
    public boolean survivesSeasonReset(final String source) {
        final String src = source == null ? "unknown" : source.toLowerCase(Locale.ROOT);
        if (matchesKeepEntry(alwaysKeepSources, src)) {
            return true;
        }
        return seasonKeepSources.isEmpty() || matchesKeepEntry(seasonKeepSources, src);
    }

    private static boolean matchesKeepEntry(final Set<String> keep, final String source) {
        for (final String entry : keep) {
            if (entry.equals(source) || source.startsWith(entry + ":")) {
                return true;
            }
        }
        return false;
    }

    /** Parse problems (empty when the catalogue is fully valid). */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    private static Set<String> parseKeepSources(final Object raw) {
        if (raw instanceof Iterable<?> list) {
            final Set<String> out = new java.util.LinkedHashSet<>();
            for (final Object entry : list) {
                out.add(String.valueOf(entry).toLowerCase(Locale.ROOT));
            }
            return Set.copyOf(out);
        }
        final String text = String.valueOf(raw == null ? "all" : raw).toLowerCase(Locale.ROOT);
        if (text.equals("all") || text.equals("*") || text.isEmpty()) {
            return Set.of(); // empty = keep everything
        }
        return Set.of(text);
    }

    private static Map<String, Object> section(final Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static String str(final Object value, final String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static Integer intOf(final Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (final NumberFormatException exception) {
            return null;
        }
    }

    private static Material material(final Object value, final Material fallback, final String id) {
        if (value == null) {
            return fallback;
        }
        final Material parsed = Material.matchMaterial(String.valueOf(value));
        // pure comparisons only: registry-backed Material methods must stay
        // out of the bare-JVM unit-tested parse path (Paper 1.21)
        if (parsed == null
                || parsed == Material.AIR || parsed == Material.CAVE_AIR || parsed == Material.VOID_AIR) {
            return fallback;
        }
        return parsed;
    }
}
