package com.coremc.core.island;

import java.util.Map;
import org.bukkit.entity.Player;

/** Messaging and main-thread callback effects required by island purchases. */
interface IslandPurchaseHost {
    void sendPurchaseMessage(Player player, String key, Map<String, String> placeholders);
    void schedulePurchaseCallback(Runnable callback, String operation);
}
