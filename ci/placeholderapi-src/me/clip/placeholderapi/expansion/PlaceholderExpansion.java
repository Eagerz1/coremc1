/*
 * Minimal compile-time stub of PlaceholderAPI's expansion base class.
 *
 * Signature-accurate against PlaceholderAPI 2.11.x (2026) for every method
 * CoreMC touches (register/unregister/persist/canRegister/getIdentifier/
 * getAuthor/getVersion). Heavy real-PAPI machinery (configuration sections,
 * the LocalExpansionManager, logging) is intentionally absent — bodies bind
 * to the real classes from the PlaceholderAPI plugin at runtime, or to the
 * working copies in the offline sandbox's mock plugin.
 */
package me.clip.placeholderapi.expansion;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderHook;

public abstract class PlaceholderExpansion extends PlaceholderHook {

    /** The placeholder identifier of this expansion (no % or _). */
    public abstract String getIdentifier();

    /** The author of this expansion. */
    public abstract String getAuthor();

    /** The current version of this expansion. */
    public abstract String getVersion();

    /** The display name (defaults to the identifier). */
    public String getName() {
        return getIdentifier();
    }

    /** Another plugin this expansion needs (empty = none). */
    public String getRequiredPlugin() {
        return "";
    }

    /** True to survive PlaceholderAPI reloads (plugin-provided expansions). */
    public boolean persist() {
        return false;
    }

    /** False to skip registration (e.g. missing dependency). */
    public boolean canRegister() {
        return true;
    }

    /** Registers this expansion with PlaceholderAPI. */
    public boolean register() {
        return PlaceholderAPI.registerExpansion(this);
    }

    /** True when currently registered. */
    public boolean isRegistered() {
        return PlaceholderAPI.isRegistered(this);
    }

    /** Unregisters this expansion. */
    public boolean unregister() {
        return PlaceholderAPI.unregisterExpansion(this);
    }
}
