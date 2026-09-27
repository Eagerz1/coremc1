package com.coremc.core.progression;

import com.coremc.core.island.IslandService;
import com.coremc.core.quest.QuestProgressService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

/** Island XP for active fishing. Only real caught-fish events count. */
public final class ProgressionFishingListener implements Listener {

    private final IslandProgressionService progression;
    private final IslandService islands;
    private final QuestProgressService quests;

    public ProgressionFishingListener(final IslandProgressionService progression) {
        this(progression, null, null);
    }

    public ProgressionFishingListener(final IslandProgressionService progression, final IslandService islands,
                                      final QuestProgressService quests) {
        this.progression = progression;
        this.islands = islands;
        this.quests = quests;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        progression.recordActivity(event.getPlayer(), event.getPlayer().getLocation(), "fishing", 1L);
        if (quests != null && islands != null) {
            quests.publish(event.getPlayer(), islands.islandOf(event.getPlayer().getUniqueId()), "fish-caught", 1L);
        }
    }
}
