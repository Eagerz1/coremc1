package com.coremc.core.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, Bukkit-free catalogue of {@link TagDefinition}s parsed from
 * the raw {@code tags.yml} map.
 *
 * Parsing lives here (not in the service) so the 20 shipped tags, their
 * ids, colours and defaults are covered by unit tests without a server.
 * Unknown/broken entries are skipped rather than aborting the load: a
 * typo in one tag can never empty the whole catalogue.
 */
public final class TagCatalog {

    /** Permission prefix used when a tag does not declare its own. */
    public static final String PERMISSION_PREFIX = "coremc.tag.";

    /** Wildcard permission granting every tag. */
    public static final String PERMISSION_WILDCARD = "coremc.tag.*";

    private final Map<String, TagDefinition> tags;

    private TagCatalog(final Map<String, TagDefinition> tags) {
        // LinkedHashMap (not Map.copyOf): config order drives GUI order.
        this.tags = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    /** An empty catalogue (used before the first load and on fatal parse errors). */
    public static TagCatalog empty() {
        return new TagCatalog(new LinkedHashMap<>());
    }

    /**
     * Parses the {@code tags:} section of a raw YAML map.
     *
     * @param root      the whole tags.yml map
     * @param problems  parse warnings are appended here (may be null)
     */
    public static TagCatalog parse(final Map<String, Object> root, final List<String> problems) {
        final Map<String, TagDefinition> parsed = new LinkedHashMap<>();
        if (root == null) {
            return new TagCatalog(parsed);
        }
        final Object section = root.get("tags");
        if (!(section instanceof Map<?, ?> raw)) {
            warn(problems, "tags.yml has no 'tags:' section — no tags loaded.");
            return new TagCatalog(parsed);
        }
        for (final Map.Entry<?, ?> entry : raw.entrySet()) {
            final String id = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT).trim();
            if (id.isEmpty() || !(entry.getValue() instanceof Map<?, ?> body)) {
                warn(problems, "tag '" + entry.getKey() + "' must be a map — skipped.");
                continue;
            }
            if (parsed.containsKey(id)) {
                warn(problems, "duplicate tag id '" + id + "' — skipped.");
                continue;
            }
            final String display = string(body.get("display"), "");
            if (display.isBlank()) {
                warn(problems, "tag '" + id + "' has no display — skipped.");
                continue;
            }
            final String permission = string(body.get("permission"), PERMISSION_PREFIX + id);
            parsed.put(id, new TagDefinition(
                    id,
                    display,
                    string(body.get("name"), ""),
                    string(body.get("material"), "NAME_TAG"),
                    (int) number(body.get("slot"), -1L),
                    permission,
                    Boolean.parseBoolean(string(body.get("default-owned"), "false")),
                    stringList(body.get("lore"))));
        }
        return new TagCatalog(parsed);
    }

    private static void warn(final List<String> problems, final String message) {
        if (problems != null) {
            problems.add(message);
        }
    }

    private static String string(final Object value, final String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long number(final Object value, final long fallback) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(final Object value) {
        final List<String> lines = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (final Object line : iterable) {
                lines.add(String.valueOf(line));
            }
        }
        return lines;
    }

    /** Tag by stable id (case-insensitive); empty when unknown. */
    public Optional<TagDefinition> byId(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tags.get(id.toLowerCase(Locale.ROOT).trim()));
    }

    /** Every tag, in config order. */
    public Collection<TagDefinition> all() {
        return tags.values();
    }

    /** Ids of every tag, in config order. */
    public List<String> ids() {
        return List.copyOf(tags.keySet());
    }

    public int size() {
        return tags.size();
    }

    public boolean isEmpty() {
        return tags.isEmpty();
    }
}
