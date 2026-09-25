/*
 * Minimal compile-time stub of PlaceholderAPI's PlaceholderHook.
 *
 * Signature-accurate against PlaceholderAPI 2.11.x (2026): CoreMC compiles
 * against these signatures and binds at runtime to the real classes from
 * the PlaceholderAPI plugin (or the mock plugin in ci/mock-papi-src on the
 * offline sandbox server). Bodies here are never executed in production;
 * the ones below are real so the same source doubles as the mock runtime.
 */
package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public abstract class PlaceholderHook {

    public String onRequest(final OfflinePlayer player, final String params) {
        return onPlaceholderRequest(player != null && player.isOnline()
                ? player.getPlayer() : null, params);
    }

    public String onPlaceholderRequest(final Player player, final String params) {
        return null;
    }
}
