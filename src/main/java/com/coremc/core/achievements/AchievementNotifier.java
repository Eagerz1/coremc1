package com.coremc.core.achievements;

import com.coremc.core.config.MessageService;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Announces earned Achievements: a title on screen, a chat line, the
 * reward list, and a server-wide broadcast for Epic and above (so the
 * rare ones feel rare) — all switchable in achievements.yml.
 */
public final class AchievementNotifier implements AchievementService.Notifier {

    private final MessageService messages;
    private final boolean announce;
    private final boolean broadcastRare;

    public AchievementNotifier(final MessageService messages, final boolean announce,
                               final boolean broadcastRare) {
        this.messages = messages;
        this.announce = announce;
        this.broadcastRare = broadcastRare;
    }

    @Override
    public void earned(final UUID id, final Achievement achievement, final boolean hasClaimable) {
        if (!announce || messages == null) {
            return;
        }
        final Player player = Bukkit.getPlayer(id);
        if (player != null) {
            messages.sendPrefixed(player, "achievements.earned", Map.of(
                    "achievement", achievement.display(),
                    "difficulty", achievement.difficulty().display(),
                    "points", String.valueOf(achievement.points())));
            for (final Reward reward : achievement.rewards()) {
                player.sendMessage(ColorUtil.colorize("  "
                        + (reward.manual() ? "&e" + GuiText.caps("to claim") + ": &f"
                                : "&a" + GuiText.TICK + " &f") + reward.label()));
            }
            if (hasClaimable) {
                messages.sendPrefixed(player, "achievements.earned-claimable",
                        Map.of("achievement", achievement.display()));
            }
            player.sendTitle(ColorUtil.colorize(achievement.difficulty().color() + "&l"
                            + GuiText.caps(achievement.display())),
                    ColorUtil.colorize("&7" + GuiText.caps("Achievement unlocked") + " &8• &e+"
                            + achievement.points() + " " + GuiText.caps("points")), 10, 50, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
        if (broadcastRare && rare(achievement) && player != null) {
            Bukkit.getServer().broadcastMessage(messages.prefix() + messages.get(
                    "achievements.broadcast", Map.of(
                            "player", player.getName(),
                            "achievement", achievement.display(),
                            "difficulty", achievement.difficulty().display())));
        }
    }

    private static boolean rare(final Achievement achievement) {
        return achievement.difficulty() == AchievementDifficulty.EPIC
                || achievement.difficulty() == AchievementDifficulty.LEGENDARY
                || achievement.difficulty() == AchievementDifficulty.PRESTIGE;
    }
}
