package com.coremc.core.role;

/**
 * Gameplay categories that award role XP. Kept separate from Role so
 * Universal can aggregate all categories without special casing.
 */
public enum RoleCategory {
    MINING,
    LOGGING,
    FISHING,
    FARMING,
    SLAYING
}
