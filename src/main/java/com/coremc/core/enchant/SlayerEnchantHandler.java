package com.coremc.core.enchant;

import static com.coremc.core.enchant.EnchantEngine.doubleValue;
import static com.coremc.core.enchant.EnchantEngine.stringList;
import static com.coremc.core.enchant.EnchantEngine.stringValue;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Slayer-activity enchant pipeline (slayer role + universal). No
 * OmniTool requirement — slayers fight with real weapons, and the
 * role gates the magic.
 *
 * Strikes run damage enchants (+ combo damage, capped); kills run
 * combos → temp boosts → bonus drops → currency → souls → reward
 * tables → magnet → recovery. PvP never procs anything.
 *
 * Anti-abuse: spawner-born mobs are PDC-tagged at spawn and are
 * ineligible for every kill ECONOMY effect (bonus drops, currency,
 * souls, tables, combos) — spawner farms cannot print souls or
 * tokens. Damage scaling, magnets and recovery still apply.
 */
public final class SlayerEnchantHandler implements Listener {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;
    private final NamespacedKey spawnerBornKey;

    public SlayerEnchantHandler(final CoreMCPlugin plugin, final EnchantEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.spawnerBornKey = new NamespacedKey(plugin, "spawner-born");
    }

    /** Tags every spawner-spawned entity (vanilla or CoreMC spawner) for the economy filter. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.getEntity().getPersistentDataContainer()
                    .set(spawnerBornKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (engine.guarded() || !(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof Player) {
            return;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String role = profile.roleId();
        if (!"slayer".equals(role) && !"universal".equals(role)) {
            return;
        }
        final List<EnchantService.EnchantLevel> active =
                engine.activeFor(profile, role, EnchantEffect.Trigger.KILL);
        if (active.isEmpty()) {
            return;
        }
        double mult = 1.0;
        for (final EnchantService.EnchantLevel owned : active) {
            if (owned.enchant().effect() != EnchantEffect.DAMAGE) {
                continue;
            }
            mult *= damageRoll(player, victim, owned);
        }
        mult *= engine.comboMultiplier(profile, role, "DAMAGE");
        if (mult != 1.0) {
            event.setDamage(event.getDamage() * EnchantEngine.capDamageMult(mult));
        }
        fireReaper(player, profile, role, event, victim, active);
        engine.fireOverdrive(player, profile, active);
    }

    /** One DAMAGE enchant's multiplier for this strike (1.0 when it doesn't apply). */
    private double damageRoll(final Player player, final LivingEntity victim,
            final EnchantService.EnchantLevel owned) {
        final Enchant enchant = owned.enchant();
        final String mode = stringValue(enchant.values(), "mode", "CRIT");
        switch (mode) {
            case "CRIT" -> {
                if (!engine.roll(enchant.chanceAt(owned.level()))
                        || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                    return 1.0;
                }
                engine.markCooldown(player.getUniqueId(), enchant.id());
                victim.getWorld().spawnParticle(Particle.CRIT,
                        victim.getLocation().add(0.0, 1.0, 0.0), 12, 0.4, 0.5, 0.4, 0.1);
                return enchant.valueAt(owned.level());
            }
            case "EXECUTE" -> {
                final double max = victim.getMaxHealth();
                final double threshold = doubleValue(enchant.values(), "threshold", 0.3);
                if (max > 0.0 && victim.getHealth() / max < threshold) {
                    return enchant.valueAt(owned.level());
                }
                return 1.0;
            }
            case "MOBS" -> {
                if (stringList(enchant.values(), "mobs").contains(victim.getType().name())) {
                    return enchant.valueAt(owned.level());
                }
                return 1.0;
            }
            case "BOSS" -> {
                if (stringList(enchant.values(), "bosses").contains(victim.getType().name())) {
                    return enchant.valueAt(owned.level());
                }
                return 1.0;
            }
            default -> {
                return 1.0;
            }
        }
    }

    /** Reaper: a rare capped execute burst plus soul rewards. */
    private void fireReaper(final Player player, final PlayerProfile profile, final String role,
            final EntityDamageByEntityEvent event, final LivingEntity victim,
            final List<EnchantService.EnchantLevel> active) {
        if (isSpawnerBorn(victim)) {
            return;
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"EXECUTE_BURST".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final double max = Math.max(1.0, victim.getMaxHealth());
            final double bonus = Math.min(enchant.valueAt(owned.level()), Math.min(
                    max * doubleValue(enchant.values(), "boss-cap-fraction", 0.25),
                    doubleValue(enchant.values(), "cap", 100.0)));
            event.setDamage(event.getDamage() + Math.max(0.0, bonus));
            engine.grantRewards(player, profile, enchant, role, RoleCategory.SLAYING);
            engine.ultimateFx(player, enchant);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final EntityDeathEvent event) {
        if (engine.guarded()) {
            return;
        }
        final LivingEntity victim = event.getEntity();
        final Player killer = victim.getKiller();
        if (killer == null || victim instanceof Player) {
            return;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(killer.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String role = profile.roleId();
        if (!"slayer".equals(role) && !"universal".equals(role)) {
            return;
        }
        final List<EnchantService.EnchantLevel> active =
                engine.activeFor(profile, role, EnchantEffect.Trigger.KILL);
        if (active.isEmpty()) {
            return;
        }
        if (!isSpawnerBorn(victim)) {
            engine.recordCombos(killer, profile, active);
            engine.triggerTempBoosts(killer, profile, role, active);
            engine.collectKillBonus(killer, profile, role, active, event.getDrops());
            engine.grantCurrencies(killer, profile, role, active);
            collectSouls(killer, profile, role, active);
            engine.grantTables(killer, profile, role, active, RoleCategory.SLAYING);
        }
        magnetDrops(killer, event, active);
        engine.applyRecovery(killer, active);
        engine.fireOverdrive(killer, profile, active);
    }

    private void collectSouls(final Player killer, final PlayerProfile profile, final String role,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.SOUL) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(killer.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(killer.getUniqueId(), enchant.id());
            engine.grantSouls(killer, profile,
                    Math.max(1L, (long) Math.floor(enchant.valueAt(owned.level()))), role);
        }
    }

    /** Kill magnets collect death drops (convenience — allowed even for spawner mobs). */
    private void magnetDrops(final Player killer, final EntityDeathEvent event,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.MAGNET) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(killer.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(killer.getUniqueId(), enchant.id());
            final List<ItemStack> drops = new ArrayList<>(event.getDrops());
            event.getDrops().clear();
            engine.collectOrDrop(killer, drops,
                    stringValue(enchant.values(), "fallback", "DROP"),
                    event.getEntity().getLocation());
            return; // first proc wins
        }
    }

    private boolean isSpawnerBorn(final LivingEntity victim) {
        return victim.getPersistentDataContainer().has(spawnerBornKey, PersistentDataType.BYTE);
    }
}
