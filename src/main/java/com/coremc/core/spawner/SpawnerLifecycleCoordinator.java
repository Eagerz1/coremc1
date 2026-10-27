package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Owns spawner purchases, tier item identity, placement setup, and break recovery. */
public final class SpawnerLifecycleCoordinator {

    private final CoreMCPlugin plugin;
    private final SpawnerService catalogue;

    public SpawnerLifecycleCoordinator(final CoreMCPlugin plugin, final SpawnerService catalogue) {
        this.plugin = plugin;
        this.catalogue = catalogue;
    }

    /** Mints a typed deployable spawner by tier ID or legacy plain mob alias. */
    public Optional<ItemStack> mint(final String id) {
        return catalogue.tierFor(id).map(this::mint);
    }

    /** Mints a tier item with the existing PDC identity and player-facing lore. */
    public ItemStack mint(final SpawnerService.TierRef ref) {
        final ItemStack stack = new ItemStack(Material.SPAWNER);
        final var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ref.tier().colouredDisplay());
            final List<String> lore = new java.util.ArrayList<>();
            lore.add(ColorUtil.colorize(ref.tier().ancient()
                    ? "&7Ancient variant &8— " + ref.tier().throughputLine()
                    : "&7Tier &b" + SpawnerTier.roman(ref.tier().index())
                            + "&7 — " + ref.tier().throughputLine()));
            if (!ref.tier().specialLine().isEmpty()) {
                lore.add(ColorUtil.colorize(ref.tier().specialLine()));
            }
            lore.add(ColorUtil.colorize("&7Place me to set me down."));
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return plugin.placeables().identify(stack, PlaceableService.Type.SPAWNER, ref.tier().tierId());
    }

    /** Configures a newly placed spawner block using tier and island settings. */
    public void configurePlacedSpawner(final Block block, final String tierId, final Player placer) {
        catalogue.tierFor(tierId).ifPresent(ref -> {
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
                        tuned * plugin.islandBuffs().spawnerDelayMult(placer)));
                // Writes entity, SpawnPotentials, count, delays and a positive
                // first delay — a tile missing SpawnPotentials stalls forever
                // once Paper refuses a spawn while the mob cap is reached.
                catalogue.configureWorldSpawner(block, ref, delay);
            }
        });
    }

    /** Purchases a spawner after unlock, refunding if delivery fails. */
    public boolean buy(final Player player, final PlayerProfile profile, final SpawnerService.TierRef ref) {
        if (!catalogue.isUnlocked(profile, ref)) {
            plugin.messages().sendPrefixed(player, "spawner.locked", Map.of(
                    "kills", String.valueOf(catalogue.killsOf(profile, ref.mob())),
                    "needed", String.valueOf(ref.tier().requiredKills())));
            return false;
        }
        final String price = String.format(Locale.ROOT, "%,d", ref.tier().priceSkyTokens());
        final boolean free = ref.tier().priceSkyTokens() <= 0L;
        synchronized (profile) {
            if (!free && !plugin.economy().withdraw(profile, Currency.SKY_TOKENS, ref.tier().priceSkyTokens())) {
                plugin.messages().sendPrefixed(player, "spawner.insufficient", Map.of("price", price));
                return false;
            }
            final ItemStack item = mint(ref);
            final var delivery = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
            if (delivery == com.coremc.core.util.ItemDelivery.Result.FAILED) {
                if (!free) {
                    plugin.economy().deposit(profile, Currency.SKY_TOKENS, ref.tier().priceSkyTokens());
                }
                plugin.messages().sendPrefixed(player, "purchase.no-space", Map.of());
                return false;
            }
            if (delivery == com.coremc.core.util.ItemDelivery.Result.DELIVERED_TO_ENDER_CHEST) {
                plugin.messages().sendPrefixed(player, "gen.bought-enderchest", Map.of());
            }
            plugin.messages().sendPrefixed(
                    player, "spawner.bought", Map.of("name", ref.tier().colouredDisplay(), "price", price));
            return true;
        }
    }
}
