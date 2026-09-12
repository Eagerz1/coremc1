package com.coremc.core.role.xp;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.role.RoleCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;

/** Slayer XP: killing hostile mobs; tougher mobs pay more. */
public final class SlayerXpListener extends RoleXpListener {

    public SlayerXpListener(final CoreMCPlugin plugin) {
        super(plugin, RoleCategory.SLAYING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        final long xp = xpFor(event.getEntityType());
        if (xp > 0) {
            award(killer, xp);
        }
    }

    static long xpFor(final EntityType type) {
        return switch (type) {
            case ZOMBIE, SKELETON, SPIDER, HUSK, STRAY, ZOMBIE_VILLAGER, DROWNED -> 4;
            case CREEPER, ENDERMAN, WITCH, SLIME, MAGMA_CUBE, SILVERFISH, CAVE_SPIDER, PHANTOM, PILLAGER,
                    VINDICATOR, BOGGED, BREEZE -> 7;
            case BLAZE, GHAST, WITHER_SKELETON, PIGLIN_BRUTE, HOGLIN, ZOGLIN, SHULKER, RAVAGER, EVOKER -> 15;
            case WARDEN -> 100;
            case ENDER_DRAGON, WITHER -> 250;
            default -> 0; // passive animals are Farmer territory one day; Slayer = hostiles only
        };
    }
}
