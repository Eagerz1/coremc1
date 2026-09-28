package com.coremc.core.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, Bukkit-free catalogue of chat message styles parsed from the
 * raw {@code chat.yml} map: solid colours under {@code chat.colours:} and
 * gradients under {@code chat.gradients:}.
 *
 * Ids are stable and shared with the player profile; display names,
 * materials and slots are pure presentation and may change freely.
 */
public final class ChatStyleCatalog {

    /** Permission prefix used when a style does not declare its own. */
    public static final String PERMISSION_PREFIX = "coremc.chatcolour.";

    /** Wildcard permission granting every style. */
    public static final String PERMISSION_WILDCARD = "coremc.chatcolour.*";

    /** Permission for the bold toggle (config may override). */
    public static final String DEFAULT_BOLD_PERMISSION = "coremc.chatcolour.bold";

    private final Map<String, ChatStyle> styles;
    private final List<ChatStyle> solids;
    private final List<ChatStyle> gradients;

    private ChatStyleCatalog(final Map<String, ChatStyle> styles) {
        this.styles = Collections.unmodifiableMap(new LinkedHashMap<>(styles));
        final List<ChatStyle> solidList = new ArrayList<>();
        final List<ChatStyle> gradientList = new ArrayList<>();
        for (final ChatStyle style : this.styles.values()) {
            if (style.gradient()) {
                gradientList.add(style);
            } else {
                solidList.add(style);
            }
        }
        this.solids = List.copyOf(solidList);
        this.gradients = List.copyOf(gradientList);
    }

    public static ChatStyleCatalog empty() {
        return new ChatStyleCatalog(new LinkedHashMap<>());
    }

    /**
     * Parses {@code chat.colours:} and {@code chat.gradients:} from the raw
     * chat.yml map. Invalid entries are skipped with a warning.
     */
    public static ChatStyleCatalog parse(final Map<String, Object> root, final List<String> problems) {
        final Map<String, ChatStyle> parsed = new LinkedHashMap<>();
        final Map<String, Object> chat = section(root, "chat");
        readSolids(section(chat, "colours"), parsed, problems);
        readGradients(section(chat, "gradients"), parsed, problems);
        return new ChatStyleCatalog(parsed);
    }

    private static void readSolids(
            final Map<String, Object> raw, final Map<String, ChatStyle> out, final List<String> problems) {
        for (final Map.Entry<String, Object> entry : raw.entrySet()) {
            final String id = entry.getKey().toLowerCase(Locale.ROOT).trim();
            if (!(entry.getValue() instanceof Map<?, ?> body) || id.isEmpty()) {
                warn(problems, "chat colour '" + entry.getKey() + "' must be a map — skipped.");
                continue;
            }
            final String colour = string(body.get("colour"), string(body.get("color"), ""));
            if (ChatRender.colourPrefix(colour).isEmpty()) {
                warn(problems, "chat colour '" + id + "' has no usable colour — skipped.");
                continue;
            }
            if (out.containsKey(id)) {
                warn(problems, "duplicate chat style id '" + id + "' — skipped.");
                continue;
            }
            out.put(id, new ChatStyle(
                    id,
                    ChatStyle.Kind.SOLID,
                    string(body.get("display"), colour + id),
                    colour,
                    "",
                    "",
                    string(body.get("material"), "WHITE_WOOL"),
                    (int) number(body.get("slot"), -1L),
                    string(body.get("permission"), PERMISSION_PREFIX + id),
                    Boolean.parseBoolean(string(body.get("default-owned"), "false"))));
        }
    }

    private static void readGradients(
            final Map<String, Object> raw, final Map<String, ChatStyle> out, final List<String> problems) {
        for (final Map.Entry<String, Object> entry : raw.entrySet()) {
            final String id = entry.getKey().toLowerCase(Locale.ROOT).trim();
            if (!(entry.getValue() instanceof Map<?, ?> body) || id.isEmpty()) {
                warn(problems, "chat gradient '" + entry.getKey() + "' must be a map — skipped.");
                continue;
            }
            final String from = ChatRender.normaliseHex(string(body.get("from"), ""));
            final String to = ChatRender.normaliseHex(string(body.get("to"), ""));
            if (from == null || to == null) {
                warn(problems, "chat gradient '" + id + "' needs valid from/to hex colours — skipped.");
                continue;
            }
            if (out.containsKey(id)) {
                warn(problems, "duplicate chat style id '" + id + "' — skipped.");
                continue;
            }
            out.put(id, new ChatStyle(
                    id,
                    ChatStyle.Kind.GRADIENT,
                    string(body.get("display"), "&f" + id),
                    "",
                    from,
                    to,
                    string(body.get("material"), "MAGENTA_STAINED_GLASS"),
                    (int) number(body.get("slot"), -1L),
                    string(body.get("permission"), PERMISSION_PREFIX + id),
                    Boolean.parseBoolean(string(body.get("default-owned"), "false"))));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(final Map<String, Object> root, final String key) {
        if (root == null) {
            return Map.of();
        }
        final Object value = root.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
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

    public Optional<ChatStyle> byId(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(styles.get(id.toLowerCase(Locale.ROOT).trim()));
    }

    public List<ChatStyle> solids() {
        return solids;
    }

    public List<ChatStyle> gradients() {
        return gradients;
    }

    public List<ChatStyle> all() {
        return List.copyOf(styles.values());
    }

    public List<String> ids() {
        return List.copyOf(styles.keySet());
    }

    public int size() {
        return styles.size();
    }
}
