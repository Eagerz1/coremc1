package com.coremc.core.chat;

/**
 * Pure assembly of the CoreMC public-chat line.
 *
 * The canonical layout is:
 *
 * <pre>{@code <RANK> <TAG> Player: Message}</pre>
 *
 * i.e. rank prefix first, then the selected cosmetic tag, then the player
 * name, then the message. Everything is driven by a configurable format
 * string (chat.yml {@code chat.format}) so servers can re-order or re-style
 * the head of the line without touching code.
 *
 * Two rules make omissions clean:
 *  - a missing rank or tag renders as an empty string,
 *  - the HEAD of the line (everything before {@code {message}}) has its
 *    whitespace collapsed, so no double spaces or leading spaces survive.
 *
 * The MESSAGE itself is never touched: player punctuation, Unicode and
 * repeated spaces are preserved byte for byte.
 *
 * Bukkit-free on purpose — this is the class the ordering tests drive.
 */
public final class ChatFormatter {

    /** The default format: rank, tag, player, then the message. */
    public static final String DEFAULT_FORMAT = "{rank} {tag} {player}&7:&r {message}";

    private static final String MESSAGE_TOKEN = "{message}";

    private ChatFormatter() {
    }

    /**
     * Renders the chat line.
     *
     * @param format  format string containing {@code {rank} {tag} {player} {message}}
     * @param rank    rank prefix (may be empty)
     * @param tag     selected tag display (may be empty)
     * @param player  player display name
     * @param message the already-styled message body (never modified)
     * @return the '&amp;'-coded line, ready for {@code ColorUtil.colorize}
     */
    public static String format(
            final String format,
            final String rank,
            final String tag,
            final String player,
            final String message) {
        final String template = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
        final String safeRank = rank == null ? "" : rank.trim();
        final String safeTag = tag == null ? "" : tag.trim();
        final String safePlayer = player == null ? "" : player;
        final String safeMessage = message == null ? "" : message;

        final int split = template.indexOf(MESSAGE_TOKEN);
        if (split < 0) {
            // No {message} token: treat the whole template as the head and
            // append the message, so chat can never silently disappear.
            return tidy(substitute(template, safeRank, safeTag, safePlayer)) + " " + safeMessage;
        }
        final String head = substitute(template.substring(0, split), safeRank, safeTag, safePlayer);
        final String tail = substitute(template.substring(split + MESSAGE_TOKEN.length()),
                safeRank, safeTag, safePlayer);
        return tidy(head) + safeMessage + tidy(tail);
    }

    private static String substitute(
            final String part, final String rank, final String tag, final String player) {
        return part.replace("{rank}", rank)
                .replace("{tag}", tag)
                .replace("{player}", player)
                .replace("{name}", player);
    }

    /**
     * Collapses runs of spaces into one and trims leading spaces, keeping at
     * most a single trailing space (the separator before the message).
     */
    private static String tidy(final String part) {
        if (part == null || part.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(part.length());
        boolean pendingSpace = false;
        for (int i = 0; i < part.length(); i++) {
            final char current = part.charAt(i);
            if (current == ' ') {
                pendingSpace = out.length() > 0; // never a leading space
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(current);
        }
        if (pendingSpace) {
            out.append(' ');
        }
        return out.toString();
    }
}
