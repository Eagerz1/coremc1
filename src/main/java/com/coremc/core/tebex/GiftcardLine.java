package com.coremc.core.tebex;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The shared gift card chat line: the card number is clickable
 * (copies to clipboard) with the balance beside it. Used by {@code /gc}
 * and the island top season rewards so both look identical.
 */
public final class GiftcardLine {

    private GiftcardLine() {
    }

    /**
     * The clickable card number component (gold, underlined,
     * click-to-copy, hover hint).
     */
    public static TextComponent number(final String code) {
        return Component.text(code, NamedTextColor.GOLD, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Click to copy your giftcard number", NamedTextColor.YELLOW)));
    }

    /**
     * The full branded card line: {@code <prefix>Giftcard: CODE
     * Balance: £x}. {@code legacyPrefix} is the colour-coded plugin
     * prefix, {@code formattedBalance} is already rendered
     * (e.g. {@code "£100.00"}).
     */
    public static Component cardLine(final String legacyPrefix, final String code,
                                     final String formattedBalance) {
        return Component.empty()
                .append(LegacyComponentSerializer.legacySection().deserialize(legacyPrefix))
                .append(Component.text("Giftcard: ", NamedTextColor.GRAY))
                .append(number(code))
                .append(Component.text("  Balance: ", NamedTextColor.GRAY))
                .append(Component.text(formattedBalance, NamedTextColor.GREEN));
    }
}
