package com.coremc.core.moderation;

/** Persisted moderation record categories. */
public enum PunishmentType {
    WARN,
    KICK,
    MUTE,
    BAN,
    FREEZE,
    UNFREEZE,
    ROTATE,
    TIER_CORRECTION;

    public static PunishmentType fromAction(final ModerationAction action) {
        return switch (action) {
            case WARN -> WARN;
            case KICK -> KICK;
            case MUTE -> MUTE;
            case BAN -> BAN;
            case NONE -> WARN;
        };
    }
}
