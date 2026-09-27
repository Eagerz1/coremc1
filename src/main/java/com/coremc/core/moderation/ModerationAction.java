package com.coremc.core.moderation;

/** Action chosen by a direct moderation command or tier progression. */
public enum ModerationAction {
    WARN,
    KICK,
    MUTE,
    BAN,
    NONE;

    public static ModerationAction parse(final String raw, final ModerationAction fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ModerationAction.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (final IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
