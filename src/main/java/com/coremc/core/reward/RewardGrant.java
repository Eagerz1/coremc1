package com.coremc.core.reward;

import java.util.Locale;

/**
 * One concrete, rolled reward — a {@link RewardDef} with its amount
 * decided. Serializes to a compact descriptor line
 * ({@code type|id|amount|display}) so pending deliveries survive
 * restarts without ever persisting raw ItemStacks: at delivery time the
 * grant is rebuilt from config-owned factories, which keeps pending
 * rewards idempotent and immune to item-format drift.
 */
public record RewardGrant(RewardType type, String id, long amount, String display, String extra) {

    private static final String SEPARATOR = "|";

    /** Joins/splits COMMAND grant command lists inside the extra field. */
    public static final String COMMAND_JOINER = ";;";

    public RewardGrant {
        id = id == null ? "" : id;
        display = display == null ? "" : display;
        extra = extra == null ? "" : extra;
        if (amount < 0) {
            throw new IllegalArgumentException("grant amount cannot be negative: " + amount);
        }
    }

    /** Convenience for grants that need no extra payload. */
    public RewardGrant(final RewardType type, final String id, final long amount,
                       final String display) {
        this(type, id, amount, display, "");
    }

    /** Builds the grant a rolled reward def produces. */
    public static RewardGrant of(final RewardDef def, final long amount) {
        String extra = "";
        if (def.type() == RewardType.ITEM && def.material() != null) {
            extra = def.material().name();
        } else if (def.type() == RewardType.COMMAND) {
            extra = String.join(COMMAND_JOINER, def.commands());
        }
        return new RewardGrant(def.type(), def.id(), amount, def.display(), extra);
    }

    /** The command list of a COMMAND grant (empty otherwise). */
    public java.util.List<String> commands() {
        if (type != RewardType.COMMAND || extra.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(extra.split(COMMAND_JOINER));
    }

    /** Serializes to {@code type|id|amount|display|extra} (fields escaped). */
    public String serialize() {
        return type.name().toLowerCase(Locale.ROOT) + SEPARATOR + escape(id)
                + SEPARATOR + amount + SEPARATOR + escape(display) + SEPARATOR + escape(extra);
    }

    /** Parses a serialized grant; throws on corrupt lines (callers skip loudly). */
    public static RewardGrant parse(final String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("empty grant line");
        }
        final String[] parts = split(line);
        if (parts.length < 3) {
            throw new IllegalArgumentException("corrupt grant line: " + line);
        }
        final RewardType type = RewardType.parse(parts[0]);
        if (type == null) {
            throw new IllegalArgumentException("unknown grant type in line: " + line);
        }
        final long amount;
        try {
            amount = Long.parseLong(parts[2]);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("bad grant amount in line: " + line);
        }
        final String display = parts.length > 3 ? unescape(parts[3]) : "";
        final String extra = parts.length > 4 ? unescape(parts[4]) : "";
        return new RewardGrant(type, unescape(parts[1]), amount, display, extra);
    }

    // ------------------------------------------------------------------
    // escaping: '|' and '\' inside fields
    // ------------------------------------------------------------------

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("|", "\\p");
    }

    private static String unescape(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length()) {
                final char next = text.charAt(index + 1);
                out.append(next == 'p' ? '|' : next);
                index++;
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static String[] split(final String line) {
        final java.util.List<String> parts = new java.util.ArrayList<>(4);
        final StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            final char character = line.charAt(index);
            if (escaped) {
                current.append('\\').append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }
}
