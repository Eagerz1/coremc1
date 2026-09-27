package com.coremc.core.collections;

import com.coremc.core.config.MessageService;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Tells a player they have reached a Collection milestone: one short
 * prefixed line, a line per automatic unlock, a nudge when something
 * is waiting to be claimed, and a quiet level-up sound.
 *
 * <p>Kept out of {@link CollectionService} so the service stays pure
 * and unit-testable; this class is the only part that touches Bukkit.</p>
 */
public final class CollectionNotifier implements CollectionService.Notifier {

    private final MessageService messages;
    private final boolean announce;

    public CollectionNotifier(final MessageService messages, final boolean announce) {
        this.messages = messages;
        this.announce = announce;
    }

    @Override
    public void milestone(final java.util.UUID id, final MilestoneAward award) {
        if (!announce || messages == null) {
            return;
        }
        final Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        messages.sendPrefixed(player, "collections.milestone", Map.of(
                "collection", award.entry().display(),
                "tier", GuiText.roman(award.milestone().tier()),
                "amount", GuiText.number(award.milestone().amount())));
        for (final Reward reward : award.automatic()) {
            player.sendMessage(ColorUtil.colorize("  &a" + GuiText.TICK + " &f" + reward.label()));
        }
        if (award.hasClaimable()) {
            messages.sendPrefixed(player, "collections.milestone-claimable", Map.of(
                    "collection", award.entry().display(),
                    "tier", String.valueOf(award.milestone().tier())));
        }
        if (award.completesEntry()) {
            messages.sendPrefixed(player, "collections.completed", Map.of(
                    "collection", award.entry().display()));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.8f);
        }
    }
}
