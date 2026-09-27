package com.coremc.core.chat;

/**
 * One selectable chat message style: a solid colour or a two-stop gradient.
 *
 * Only the stable {@code id} (plus the separate bold boolean) is persisted
 * on the player profile — rendered output is NEVER stored, so restyling a
 * gradient in config instantly restyles every player using it.
 *
 * @param id           stable lowercase id (white, sunset, ...)
 * @param kind         SOLID or GRADIENT
 * @param display      GUI item name ('&amp;'-coded)
 * @param colour       SOLID: legacy code ({@code &c}) or {@code #rrggbb}
 * @param fromHex      GRADIENT: first stop ({@code #rrggbb})
 * @param toHex        GRADIENT: last stop ({@code #rrggbb})
 * @param material     GUI icon material name
 * @param slot         explicit GUI slot, or -1 to auto-place
 * @param permission   permission that also grants the style ("" = none)
 * @param defaultOwned whether every player owns this style by default
 */
public record ChatStyle(
        String id,
        Kind kind,
        String display,
        String colour,
        String fromHex,
        String toHex,
        String material,
        int slot,
        String permission,
        boolean defaultOwned) {

    /** Style shapes CoreMC can render with legacy formatting only. */
    public enum Kind {
        SOLID,
        GRADIENT
    }

    public boolean gradient() {
        return kind == Kind.GRADIENT;
    }

    /**
     * Renders {@code message} in this style.
     *
     * @param bold        whether the player's bold toggle is on
     * @param maxSegments gradient colour-stop cap from config
     */
    public String render(final String message, final boolean bold, final int maxSegments) {
        if (kind == Kind.GRADIENT) {
            return ChatRender.gradient(fromHex, toHex, message, bold, maxSegments);
        }
        return ChatRender.solid(colour, message, bold);
    }

    /** Short preview swatch used in GUI lore. */
    public String preview(final String sample, final boolean bold, final int maxSegments) {
        return render(sample, bold, maxSegments);
    }
}
