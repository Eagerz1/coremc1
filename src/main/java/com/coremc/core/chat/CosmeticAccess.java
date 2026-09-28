package com.coremc.core.chat;

import com.coremc.core.player.PlayerProfile;
import java.util.function.Predicate;

/**
 * Pure ownership + selection rules for tags and chat styles.
 *
 * A cosmetic is owned when ANY of these hold:
 *  1. it is flagged {@code default-owned} in config (starter cosmetics),
 *  2. the profile explicitly owns its stable id (crate/store/admin grant),
 *  3. the holder has the cosmetic's permission, or the wildcard.
 *
 * Rule 2 is what makes paid ownership durable: it is persisted by id on
 * the profile and therefore survives permission changes, rank expiry and
 * display-name/lore edits.
 *
 * Bukkit-free (the permission check is injected as a predicate), so the
 * grant / revoke / select / locked-selection tests run without a server.
 */
public final class CosmeticAccess {

    /** Outcome of a selection attempt. */
    public enum Result {
        /** Selection applied and the profile changed. */
        SELECTED,
        /** Selection cleared ("none"). */
        CLEARED,
        /** The cosmetic exists but the player does not own it. */
        LOCKED,
        /** No cosmetic with that id. */
        UNKNOWN,
        /** Already selected — nothing changed. */
        UNCHANGED
    }

    /** The id meaning "nothing selected". */
    public static final String NONE = "none";

    private CosmeticAccess() {
    }

    // ------------------------------------------------------------------ tags

    /** Whether {@code profile}/{@code permissions} own the tag. */
    public static boolean ownsTag(
            final PlayerProfile profile, final TagDefinition tag, final Predicate<String> permissions) {
        if (tag == null) {
            return false;
        }
        if (tag.defaultOwned()) {
            return true;
        }
        if (profile != null && profile.hasTag(tag.id())) {
            return true;
        }
        return hasPermission(permissions, tag.permission(), TagCatalog.PERMISSION_WILDCARD);
    }

    /** Grants a tag by stable id; false when already owned explicitly. */
    public static boolean grantTag(final PlayerProfile profile, final String tagId) {
        return profile != null && profile.addTag(tagId);
    }

    /**
     * Revokes explicit ownership of a tag and unequips it when it was
     * selected. Returns true when anything changed.
     */
    public static boolean revokeTag(
            final PlayerProfile profile,
            final TagDefinition tag,
            final Predicate<String> permissions) {
        if (profile == null || tag == null) {
            return false;
        }
        final boolean removed = profile.removeTag(tag.id());
        boolean unequipped = false;
        if (tag.id().equals(profile.equippedTag()) && !ownsTag(profile, tag, permissions)) {
            profile.equippedTag(NONE);
            unequipped = true;
        }
        return removed || unequipped;
    }

    /** Clears the selected tag; true when something was equipped. */
    public static boolean clearTag(final PlayerProfile profile) {
        if (profile == null || NONE.equals(profile.equippedTag())) {
            return false;
        }
        profile.equippedTag(NONE);
        return true;
    }

    /** Selects a tag, enforcing ownership. */
    public static Result selectTag(
            final PlayerProfile profile,
            final TagCatalog catalog,
            final String tagId,
            final Predicate<String> permissions) {
        if (profile == null || catalog == null || tagId == null) {
            return Result.UNKNOWN;
        }
        if (NONE.equalsIgnoreCase(tagId.trim())) {
            return clearTag(profile) ? Result.CLEARED : Result.UNCHANGED;
        }
        final TagDefinition tag = catalog.byId(tagId).orElse(null);
        if (tag == null) {
            return Result.UNKNOWN;
        }
        if (!ownsTag(profile, tag, permissions)) {
            return Result.LOCKED;
        }
        if (tag.id().equals(profile.equippedTag())) {
            return Result.UNCHANGED;
        }
        profile.equippedTag(tag.id());
        return Result.SELECTED;
    }

    /**
     * Resolves the tag to RENDER for a profile: the selected tag, but only
     * while it still exists and is still owned. Anything else renders as no
     * tag at all (a revoked or deleted tag never leaks into chat).
     */
    public static TagDefinition renderableTag(
            final PlayerProfile profile, final TagCatalog catalog, final Predicate<String> permissions) {
        if (profile == null || catalog == null) {
            return null;
        }
        final String selected = profile.equippedTag();
        if (selected == null || NONE.equals(selected)) {
            return null;
        }
        final TagDefinition tag = catalog.byId(selected).orElse(null);
        if (tag == null || !ownsTag(profile, tag, permissions)) {
            return null;
        }
        return tag;
    }

    // ---------------------------------------------------------- chat styles

    /** Whether {@code profile}/{@code permissions} own the chat style. */
    public static boolean ownsStyle(
            final PlayerProfile profile, final ChatStyle style, final Predicate<String> permissions) {
        if (style == null) {
            return false;
        }
        if (style.defaultOwned()) {
            return true;
        }
        if (profile != null && profile.hasChatStyle(style.id())) {
            return true;
        }
        return hasPermission(permissions, style.permission(), ChatStyleCatalog.PERMISSION_WILDCARD);
    }

    /** Grants a chat style by stable id; false when already owned explicitly. */
    public static boolean grantStyle(final PlayerProfile profile, final String styleId) {
        return profile != null && profile.addChatStyle(styleId);
    }

    /** Revokes a chat style and resets the selection when it was in use. */
    public static boolean revokeStyle(
            final PlayerProfile profile, final ChatStyle style, final Predicate<String> permissions) {
        if (profile == null || style == null) {
            return false;
        }
        final boolean removed = profile.removeChatStyle(style.id());
        boolean reset = false;
        if (style.id().equals(profile.chatColor()) && !ownsStyle(profile, style, permissions)) {
            profile.chatColor(NONE);
            reset = true;
        }
        return removed || reset;
    }

    /** Resets style + bold to defaults; true when something changed. */
    public static boolean resetStyle(final PlayerProfile profile) {
        if (profile == null) {
            return false;
        }
        final boolean changed = !NONE.equals(profile.chatColor()) || profile.chatBold();
        profile.chatColor(NONE);
        profile.chatBold(false);
        return changed;
    }

    /** Selects a chat style, enforcing ownership. */
    public static Result selectStyle(
            final PlayerProfile profile,
            final ChatStyleCatalog catalog,
            final String styleId,
            final Predicate<String> permissions) {
        if (profile == null || catalog == null || styleId == null) {
            return Result.UNKNOWN;
        }
        if (NONE.equalsIgnoreCase(styleId.trim())) {
            return resetStyle(profile) ? Result.CLEARED : Result.UNCHANGED;
        }
        final ChatStyle style = catalog.byId(styleId).orElse(null);
        if (style == null) {
            return Result.UNKNOWN;
        }
        if (!ownsStyle(profile, style, permissions)) {
            return Result.LOCKED;
        }
        if (style.id().equals(profile.chatColor())) {
            return Result.UNCHANGED;
        }
        profile.chatColor(style.id());
        return Result.SELECTED;
    }

    /** The style to RENDER with: selected, existing and still owned, else null. */
    public static ChatStyle renderableStyle(
            final PlayerProfile profile, final ChatStyleCatalog catalog, final Predicate<String> permissions) {
        if (profile == null || catalog == null) {
            return null;
        }
        final String selected = profile.chatColor();
        if (selected == null || NONE.equals(selected)) {
            return null;
        }
        final ChatStyle style = catalog.byId(selected).orElse(null);
        if (style == null || !ownsStyle(profile, style, permissions)) {
            return null;
        }
        return style;
    }

    /** Whether bold may be used (permission-gated, configurable). */
    public static boolean boldAllowed(
            final String boldPermission, final boolean boldFree, final Predicate<String> permissions) {
        if (boldFree) {
            return true;
        }
        return hasPermission(permissions, boldPermission, ChatStyleCatalog.PERMISSION_WILDCARD);
    }

    private static boolean hasPermission(
            final Predicate<String> permissions, final String permission, final String wildcard) {
        if (permissions == null) {
            return false;
        }
        if (permission != null && !permission.isBlank() && permissions.test(permission)) {
            return true;
        }
        return wildcard != null && permissions.test(wildcard);
    }
}
