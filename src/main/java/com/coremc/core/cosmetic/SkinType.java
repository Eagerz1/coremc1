package com.coremc.core.cosmetic;

/**
 * The two animated-skin shapes CoreMC ships.
 *
 * TOOL skins re-skin the soulbound OmniTool of exactly one role (visual
 * model layer only). HAT skins are worn as a client-visible overlay and
 * never touch the real helmet slot.
 */
public enum SkinType {
    TOOL,
    HAT
}
