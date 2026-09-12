package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gen.GeneratorDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * World rules for core placeables:
 *  - placed PDC-tagged blocks join the placement registry (MONITOR, only if
 *    nothing cancelled the placement),
 *  - breaking a registered block suppresses vanilla drops and yields exactly
 *    one fresh core item (dupe-safe: an item placed twice is registered
 *    twice, consumed per-placement),
 *  - explosions disarm placed blocks (registry cleared; item destroyed),
 *  - right-clicking a placed generator harvests its product with a
 *    per-block cooldown so farms tick without redstone exploits.
 *
 * Buff pipeline: spawner delays multiply with the island spawner-boost
 * at place time, harvest amounts scale with the generator-boost, and
 * the rare-product chance rolls with island-luck — each exactly once.
 */
public final class PlaceableListener implements Listener {

    private final CoreMCPlugin plugin;
    private final PlaceableService placeables;

    /** per-player-per-block last harvest millis; bounded, swept on growth. */
    private final Map<String, Long> lastHarvest = new HashMap<>();

    public PlaceableListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.placeables = plugin.placeables();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Optional<Map.Entry<PlaceableService.Type, String>> id =
                placeables.idOf(event.getItemInHand());
        if (id.isEmpty()) {
            return;
        }
        final Block block = event.getBlockPlaced();
        placeables.register(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                id.get().getKey(), id.get().getValue(), event.getPlayer().getUniqueId());
        if (id.get().getKey() == PlaceableService.Type.SPAWNER) {
            plugin.spawners().tierFor(id.get().getValue()).ifPresent(ref -> {
                if (block.getState() instanceof org.bukkit.block.CreatureSpawner) {
                    // "Better tiers" are real world-level behaviour: more mobs
                    // per cycle and a faster cycle, taken from the tier config.
                    // Island spawner-boost shrinks the cycle further.
                    final int boost = plugin.upgradeEffects().spawnerBoostTierAt(
                            block.getWorld().getName(), block.getX(), block.getZ());
                    final int pct = Math.max(0, plugin.getConfig().getInt(
                            "island.upgrades.spawner-boost.delay-reduction-percent-per-level", 10));
                    final int tuned = com.coremc.core.island.IslandUpgradeEffects
                            .reducedDelayTicks(ref.tier().spawnDelayTicks(), boost, pct);
                    // BUFF stage: the island spawner-boost multiplies the tuned delay.
                    final int delay = Math.max(20, (int) Math.round(
                            tuned * plugin.islandBuffs().spawnerDelayMult(event.getPlayer())));
                    // Writes entity, SpawnPotentials, count, delays and a positive
                    // first delay — a tile missing SpawnPotentials stalls forever
                    // once Paper refuses a spawn while the mob cap is reached.
                    plugin.spawners().configureWorldSpawner(block, ref, delay);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Optional<PlaceableService.Placement> placement = placeables.at(event.getBlock().getLocation());
        if (placement.isEmpty()) {
            return;
        }
        final UUID placer = placement.get().owner();
        if (placer != null && !placer.equals(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("coremc.island.bypass")) {
            event.setCancelled(true);
            plugin.messages().sendPrefixed(event.getPlayer(), "gen.not-yours", Map.of());
            return;
        }
        event.setDropItems(false);
        placeables.unregister(event.getBlock().getLocation());
        final ItemStack returned = switch (placement.get().type()) {
            case GENERATOR -> plugin.generators().mint(placement.get().id()).orElse(null);
            case SPAWNER -> plugin.spawners().mint(placement.get().id()).orElse(null);
        };
        if (returned != null) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), returned);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(final org.bukkit.event.block.BlockPistonExtendEvent event) {
        for (final Block block : event.getBlocks()) {
            if (placeables.at(block.getLocation()).isPresent()) {
                event.setCancelled(true); // a pushed block would desync from the placement registry
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(final org.bukkit.event.block.BlockPistonRetractEvent event) {
        for (final Block block : event.getBlocks()) {
            if (placeables.at(block.getLocation()).isPresent()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        clearAffected(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        clearAffected(event.blockList());
    }

    private void clearAffected(final List<Block> blocks) {
        for (final Block block : blocks) {
            placeables.unregister(block.getLocation());
        }
    }

    // ------------------------------------------------------------------ generator harvest

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(final PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getClickedBlock() == null) {
            return;
        }
        final Optional<PlaceableService.Placement> placement =
                placeables.at(event.getClickedBlock().getLocation());
        if (placement.isEmpty() || placement.get().type() != PlaceableService.Type.GENERATOR) {
            return;
        }
        event.setCancelled(true);
        final Player player = event.getPlayer();
        final UUID placer = placement.get().owner();
        if (placer != null && !placer.equals(player.getUniqueId())
                && !player.hasPermission("coremc.island.bypass")) {
            plugin.messages().sendPrefixed(player, "gen.not-yours", Map.of());
            return;
        }
        final Optional<GeneratorDefinition> definition =
                plugin.generators().definition(placement.get().id());
        if (definition.isEmpty()) {
            plugin.messages().sendPrefixed(player, "gen.broken", Map.of());
            return;
        }
        final GeneratorDefinition def = definition.get();
        final Location loc = event.getClickedBlock().getLocation();
        // Island generator-boost shrinks the harvest cooldown.
        final int boost = plugin.upgradeEffects().generatorBoostTierAt(
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        final int pct = Math.max(0, plugin.getConfig().getInt(
                "island.upgrades.generator-boost.cooldown-reduction-percent-per-level", 8));
        final long effectiveCooldown = com.coremc.core.island.IslandUpgradeEffects
                .reducedCooldownSeconds(def.cooldownSeconds(), boost, pct);
        final String rateKey = player.getUniqueId() + ":" + PlaceableService.keyOf(
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        final long now = System.currentTimeMillis();
        final Long last = lastHarvest.get(rateKey);
        if (last != null && now - last < effectiveCooldown * 1000L) {
            final long waits = (effectiveCooldown * 1000L - (now - last)) / 1000L + 1;
            plugin.messages().sendPrefixed(player, "gen.cooldown", Map.of("seconds", String.valueOf(waits)));
            return;
        }
        lastHarvest.put(rateKey, now);
        if (lastHarvest.size() > 256) {
            final long threshold = now - (effectiveCooldown * 1000L + 60_000L);
            lastHarvest.entrySet().removeIf(e -> e.getValue() < threshold);
        }
        final com.coremc.core.island.IslandUpgradeEffects boosts = plugin.upgradeEffects();
        final int everyN = Math.max(0, plugin.getConfig()
                .getInt("island.upgrades.generator-boost.bonus-product-every-n-tiers", 2));
        int amount = 1 + com.coremc.core.island.IslandUpgradeEffects.bonusBaseProducts(boost, everyN);
        if (boost > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                < boosts.generatorBonusYieldChance(boost)) {
            amount++;
        }
        // BUFF stage: the island generator-boost scales the harvest (once).
        amount = com.coremc.core.island.IslandBuffService.scaleCount(
                amount, plugin.islandBuffs().generatorMult(player));
        final boolean delivered =
                com.coremc.core.util.ItemDelivery.deliver(player, new ItemStack(def.product(), amount));
        if (!delivered) {
            lastHarvest.remove(rateKey); // don't burn the cooldown for a product the player never got
            plugin.messages().sendPrefixed(player, "purchase.no-space", Map.of());
            return;
        }
        // Island progression + the rare-product roll (post-delivery: the base yield is safe).
        plugin.islands().islandAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ())
                .filter(island -> island.roleOf(player.getUniqueId()) != null)
                .ifPresent(island -> plugin.islandProgress().recordGeneratorHarvest(island, player));
        final double rareChance = Math.min(0.95,
                boosts.generatorRareChance(boost) * plugin.islandBuffs().luckMult(player));
        if (boost > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < rareChance) {
            boosts.rollGeneratorRare().ifPresent(rare -> {
                final boolean rareDelivered = com.coremc.core.util.ItemDelivery.deliver(
                        player, new ItemStack(rare));
                if (!rareDelivered) {
                    loc.getWorld().dropItemNaturally(loc, new ItemStack(rare));
                }
                plugin.messages().sendPrefixed(player, "gen.harvest-rare",
                        Map.of("product",
                                com.coremc.core.island.IslandUpgradeEffects.prettyName(rare)));
            });
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
        plugin.messages().sendPrefixed(player, "gen.harvest",
                Map.of("amount", String.valueOf(amount), "product", def.productName()));
    }
}
