package com.coremc.core.market;

import java.util.Map;

/**
 * The market's outbound announcement port (opening/closing warnings,
 * auction calls). The runtime implementation broadcasts a
 * messages.yml entry to chat; tests plug in a recorder so warning
 * timing and deduplication are unit-testable without a server.
 */
public interface MarketAnnouncer {

    void announce(String messageKey, Map<String, String> placeholders);

    /** A silent sink. */
    static MarketAnnouncer none() {
        return (key, placeholders) -> {
        };
    }
}
