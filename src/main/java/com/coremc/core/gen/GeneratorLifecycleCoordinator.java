package com.coremc.core.gen;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/** Owns generator purchases, harvest rules, and recovery item minting. */
public final class GeneratorLifecycleCoordinator {

    private final CoreMCPlugin plugin;
    private final GeneratorService catalogue;
    /** per-player-per-block last harvest millis; bounded and swept on growth. */
    private final Map<String, Long> lastHarvest = new HashMap<>();

    public GeneratorLifecycleCoordinator(final CoreMCPlugin plugin, final GeneratorService catalogue) {
        this.plugin = plugin;
        this.catalogue = catalogue;
    }

    /** Purchases a catalogue generator, refunding if neither inventory can accept it. */
    public boolean buy(final Player player, final PlayerProfile profile, final GeneratorDefinition def) {
        final String price = String.format(java.util.Locale.ROOT, "%,d", def.priceCredits());
        final boolean free = def.priceCredits() <= 0L;
        synchronized (profile) {
            if (!free && !plugin.economy().withdraw(profile, Currency.CREDITS, def.priceCredits())) {
                plugin.messages().sendPrefixed(player, "gen.insufficient", Map.of("price", price));
                return false;
            }
            final ItemStack item = catalogue.mint(def);
            final var delivery = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
            if (delivery == com.coremc.core.util.ItemDelivery.Result.FAILED) {
                if (!free) {
                    plugin.economy().deposit(profile, Currency.CREDITS, def.priceCredits());
                }
                plugin.messages().sendPrefixed(player, "purchase.no-space", Map.of());
                return false;
            }
            if (delivery == com.coremc.core.util.ItemDelivery.Result.DELIVERED_TO_ENDER_CHEST) {
                plugin.messages().sendPrefixed(player, "gen.bought-enderchest", Map.of());
            }
            plugin.messages().sendPrefixed(
                    player, "gen.bought", Map.of("name", ColorUtil.colorize(def.display()), "price", price));
            return true;
        }
    }

    /** Handles right-clicking a placed generator. */
    public void onInteract(final PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getClickedBlock() == null) {
            return;
        }
        final Optional<PlaceableService.Placement> placement =
                plugin.placeables().at(event.getClickedBlock().getLocation());
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
        final Optional<GeneratorDefinition> definition = catalogue.definition(placement.get().id());
        if (definition.isEmpty()) {
            plugin.messages().sendPrefixed(player, "gen.broken", Map.of());
            return;
        }
        harvest(player, event.getClickedBlock().getLocation(), definition.get());
    }

    /** Creates the item returned when a registered generator is broken. */
    public Optional<ItemStack> mintForBreak(final String id) {
        return catalogue.mint(id);
    }

    private void harvest(final Player player, final Location loc, final GeneratorDefinition def) {
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
                        Map.of("product", com.coremc.core.island.IslandUpgradeEffects.prettyName(rare)));
            });
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
        plugin.messages().sendPrefixed(player, "gen.harvest",
                Map.of("amount", String.valueOf(amount), "product", def.productName()));
    }
}
