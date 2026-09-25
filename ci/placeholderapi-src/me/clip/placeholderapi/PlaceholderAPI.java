/*
 * Minimal compile-time stub of the PlaceholderAPI facade.
 *
 * On a production server the real PlaceholderAPI plugin supplies this class
 * (with its full regex replacer, cache and cloud machinery). This trimmed
 * copy exists so CoreMC compiles offline, and — identical source — powers
 * the mock PlaceholderAPI plugin (ci/mock-papi-src) on the sandbox server:
 * a simple registry plus a %identifier_params% scanner good enough for
 * journey tests.
 */
package me.clip.placeholderapi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class PlaceholderAPI {

    private static final Map<String, PlaceholderExpansion> EXPANSIONS =
            new ConcurrentHashMap<>();

    private PlaceholderAPI() {
    }

    public static boolean registerExpansion(final PlaceholderExpansion expansion) {
        if (expansion == null || expansion.getIdentifier() == null) {
            return false;
        }
        EXPANSIONS.put(expansion.getIdentifier().toLowerCase(), expansion);
        return true;
    }

    public static boolean unregisterExpansion(final PlaceholderExpansion expansion) {
        return expansion != null
                && EXPANSIONS.remove(expansion.getIdentifier().toLowerCase()) != null;
    }

    public static boolean isRegistered(final PlaceholderExpansion expansion) {
        return expansion != null && EXPANSIONS.containsValue(expansion);
    }

    /** Resolves one {@code %identifier_params%} placeholder. */
    public static String setPlaceholders(final OfflinePlayer player, final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        int search = 0;
        while (true) {
            final int start = result.indexOf('%', search);
            if (start < 0 || start + 1 >= result.length()) {
                return result;
            }
            final int end = result.indexOf('%', start + 1);
            if (end < 0) {
                return result;
            }
            final String token = result.substring(start + 1, end);
            final int split = token.indexOf('_');
            if (split > 0) {
                final String identifier = token.substring(0, split).toLowerCase();
                final PlaceholderExpansion expansion = EXPANSIONS.get(identifier);
                if (expansion != null) {
                    final Player online = player != null && player.isOnline()
                            ? player.getPlayer() : null;
                    final String value = expansion.onRequest(
                            online != null ? online : player, token.substring(split + 1));
                    final String replacement = value == null ? "" : value;
                    result = result.substring(0, start) + replacement + result.substring(end + 1);
                    search = start + replacement.length();
                    continue;
                }
            }
            search = end + 1;
        }
    }

    public static String setPlaceholders(final Player player, final String text) {
        return setPlaceholders((OfflinePlayer) player, text);
    }
}
