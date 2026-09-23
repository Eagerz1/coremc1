package com.coremc.core.spawner;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.util.ColorUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spawner progression runtime: custom item factory and matching,
 * spawner placement/upgrade/break, island luck, and kill rewards
 * (essence, unique drops, relics) with Mythic auto-kill credit.
 *
 * All state is main-thread only; every mutation is written through
 * to the store immediately.
 */
public final class SpawnerService {

    private static final long AUTO_KILL_DELAY_TICKS = 60L; // 3s
    private static final int AUTO_KILL_SPAWN_RANGE = 6;

    private final JavaPlugin plugin;
    private final SpawnerConfig config;
    private final SpawnerDataStore store;
    private final EconomyService economy;
    private final MessageService messages;
    private final IslandService islands;
    private final Logger logger;
    private final Random random = new Random();

    private final NamespacedKey itemKey;
    private final NamespacedKey spawnerKey;
    private final NamespacedKey amountKey;

    private final Map<UUID, Integer> luck = new LinkedHashMap<>();
    private final Map<String, SpawnerEntry> spawners = new LinkedHashMap<>();
    /** Mobs spawned by Mythic auto-kill spawners, awaiting their scheduled death. */
    private final Map<UUID, SpawnerEntry> autoKillMobs = new LinkedHashMap<>();
    /** Island spawner-rate multipliers from the Spawner Overdrive buff (island id -> multiplier). */
    private final Map<UUID, Double> boosts = new LinkedHashMap<>();
    /** Mob stacking: spawner spawns merge into counted entities. */
    private final MobStacks mobStacks;
    /** Floating stack labels above placed spawners (attached after construction). */
    private SpawnerHolograms holograms;

    public SpawnerService(final JavaPlugin plugin, final SpawnerConfig config, final SpawnerDataStore store,
                          final EconomyService economy, final MessageService messages,
                          final IslandService islands) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.economy = economy;
        this.messages = messages;
        this.islands = islands;
        this.logger = plugin.getLogger();
        this.itemKey = new NamespacedKey(plugin, "coremc_item");
        this.spawnerKey = new NamespacedKey(plugin, "coremc_spawner");
        this.amountKey = new NamespacedKey(plugin, "coremc_spawner_amount");
        this.mobStacks = new MobStacks(plugin, config, this);
    }

    /** Loads persisted state (call once, after the island service). */
    public void load() {
        try {
            luck.putAll(store.loadLuck());
            for (final SpawnerEntry entry : store.loadSpawners()) {
                spawners.put(entry.key(), entry);
            }
        } catch (final IOException exception) {
            logger.severe("Could not load spawners.yml: " + exception.getMessage());
        }
    }

    private void persist() {
        try {
            store.save(luck, List.copyOf(spawners.values()));
        } catch (final IOException exception) {
            logger.severe("Could not save spawners.yml: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Custom items
    // ------------------------------------------------------------------

    /** Essence item for a group ("Organic Essence"). */
    public ItemStack essenceItem(final SpawnerGroup group, final int amount) {
        return customItem(group.essenceMaterial(), "&b" + group.essenceName(), amount,
                "essence:" + group.id(), "&7Dropped by " + group.name() + " mobs");
    }

    /** Rare relic item for a group. */
    public ItemStack relicItem(final SpawnerGroup group, final int amount) {
        return customItem(group.relicMaterial(), "&d" + group.relicName(), amount,
                "relic:" + group.id(), "&7Rare drop from " + group.name() + " mobs");
    }

    /** The mob's unique drop item ("Pig Tusk"). */
    public ItemStack dropItem(final SpawnerMob mob, final int amount) {
        return customItem(mob.dropMaterial(), "&e" + mob.dropName(), amount,
                "drop:" + mob.id(), "&7Unique " + mob.name() + " drop");
    }

    private ItemStack customItem(final Material material, final String coloredName, final int amount,
                                 final String pdcId, final String lore) {
        final ItemStack stack = new ItemStack(material, Math.max(1, amount));
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(coloredName));
            meta.setLore(List.of(ColorUtil.colorize(lore)));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, pdcId);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** The placeable spawner item for a mob at a variant (single spawner). */
    public ItemStack spawnerItem(final SpawnerMob mob, final SpawnerVariant variant) {
        return spawnerItem(mob, variant, 1);
    }

    /**
     * The placeable spawner item for a mob at a variant, possibly a
     * stacked one — the name carries the count ("32x Pig Spawner
     * [Normal]") and one item unit counts as {@code amount} spawners.
     */
    public ItemStack spawnerItem(final SpawnerMob mob, final SpawnerVariant variant,
                                 final int amount) {
        final int count = Math.max(1, amount);
        final ItemStack stack = new ItemStack(Material.SPAWNER);
        final ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BlockStateMeta stateMeta) {
            if (stateMeta.getBlockState() instanceof CreatureSpawner spawner) {
                spawner.setSpawnedType(mob.entity());
                stateMeta.setBlockState(spawner);
            }
        }
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(
                    SpawnerStacks.stackName(mob.name(), variant, count)));
            final SpawnerVariantSettings settings = config.variantSettings(variant);
            final List<String> lore = new ArrayList<>(List.of(
                    ColorUtil.colorize("&7Spawns &f" + mob.name() + "s &7at &fx"
                            + trimRate(settings.rate()) + (count > 1
                            ? " &7per spawner (&fx" + trimRate(settings.rate() * count) + " &7stacked)"
                            : "")),
                    ColorUtil.colorize(settings.autoKill()
                            ? "&dAuto-kills its spawns &7(drops go to the owner)"
                            : "&7Place on your island to start spawning"),
                    ColorUtil.colorize(count > 1
                            ? "&7Sneak-break to pick up all &f" + count + " &7spawners"
                            : "&7Sneak-click an identical spawner to stack it")));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(spawnerKey, PersistentDataType.STRING,
                    mob.id() + ":" + variant.name());
            if (count > 1) {
                meta.getPersistentDataContainer().set(amountKey, PersistentDataType.INTEGER, count);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** How many spawners one unit of a spawner item represents. */
    public int spawnerItemAmount(final ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || stack.getItemMeta() == null) {
            return 1;
        }
        final Integer amount = stack.getItemMeta().getPersistentDataContainer()
                .get(amountKey, PersistentDataType.INTEGER);
        return amount == null || amount < 1 ? 1 : amount;
    }

    /** Reads the spawner identity from an item, or null. */
    public String[] spawnerItemIdentity(final ItemStack stack) {
        if (stack == null || stack.getType() != Material.SPAWNER || !stack.hasItemMeta()) {
            return null;
        }
        final ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        final String raw = meta.getPersistentDataContainer().get(spawnerKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split(":");
        return parts.length == 2 ? parts : null;
    }

    /** True when the stack is the given custom item (PDC id, or material+name fallback). */
    public boolean isCustomItem(final ItemStack stack, final String pdcId, final Material material,
                                final String plainName) {
        if (stack == null || stack.getType() != material) {
            return false;
        }
        if (stack.hasItemMeta() && stack.getItemMeta() != null) {
            final String id = stack.getItemMeta().getPersistentDataContainer()
                    .get(itemKey, PersistentDataType.STRING);
            if (id != null) {
                return id.equals(pdcId);
            }
        }
        // Fallback: matching base material with the exact display name, so
        // admins can hand out items with /give if they want to.
        return stack.hasItemMeta()
                && stack.getItemMeta().hasDisplayName()
                && ColorUtil.colorize(plainName).equals(stack.getItemMeta().getDisplayName());
    }

    // ------------------------------------------------------------------
    // Buying / giving
    // ------------------------------------------------------------------

    /** /spawner buy <mob> — coins + unlock requirements -> Normal spawner item. */
    public void buy(final Player player, final String mobId) {
        final SpawnerGroup group = config.groupOf(mobId);
        if (group == null) {
            messages.sendPrefixed(player, "spawner.unknown-mob", Map.of("id", mobId));
            return;
        }
        final SpawnerMob mob = group.mob(mobId);
        final double cost = mob.spawnerCost();
        if (!economy.has(player.getUniqueId(), cost)) {
            messages.sendPrefixed(player, "spawner.cannot-afford", Map.of(
                    "cost", com.coremc.core.shop.Money.format(cost, "$"),
                    "balance", com.coremc.core.shop.Money.format(economy.balance(player.getUniqueId()), "$")));
            return;
        }

        // Unlock requirements: essence + earlier mobs' drops.
        final List<String> missing = new ArrayList<>();
        if (countOf(player, essenceItemMatcher(group)) < mob.unlockEssence()) {
            missing.add((mob.unlockEssence() - countOf(player, essenceItemMatcher(group)))
                    + " " + group.essenceName());
        }
        for (final Map.Entry<String, Integer> requirement : mob.unlockDrops().entrySet()) {
            final SpawnerMob source = group.mob(requirement.getKey());
            if (source == null) {
                continue;
            }
            final int held = countOf(player, dropMatcher(source));
            if (held < requirement.getValue()) {
                missing.add((requirement.getValue() - held) + " " + source.dropName());
            }
        }
        if (!missing.isEmpty()) {
            messages.sendPrefixed(player, "spawner.missing-items",
                    Map.of("missing", String.join(", ", missing)));
            return;
        }

        economy.withdraw(player.getUniqueId(), cost);
        removeUpTo(player, essenceItemMatcher(group), mob.unlockEssence());
        for (final Map.Entry<String, Integer> requirement : mob.unlockDrops().entrySet()) {
            final SpawnerMob source = group.mob(requirement.getKey());
            if (source != null) {
                removeUpTo(player, dropMatcher(source), requirement.getValue());
            }
        }
        giveItem(player, spawnerItem(mob, SpawnerVariant.NORMAL));
        messages.sendPrefixed(player, "spawner.bought", Map.of(
                "mob", mob.name(),
                "cost", com.coremc.core.shop.Money.format(cost, "$")));
    }

    /** Admin: hand a spawner item to a player. */
    public void give(final Player player, final String mobId, final SpawnerVariant variant) {
        final SpawnerGroup group = config.groupOf(mobId);
        if (group == null || variant == null) {
            messages.sendPrefixed(player, "spawner.unknown-mob", Map.of("id", mobId));
            return;
        }
        giveItem(player, spawnerItem(group.mob(mobId), variant));
        messages.sendPrefixed(player, "spawner.spawner-given", Map.of(
                "mob", group.mob(mobId).name(), "variant", variant.display()));
    }

    /** Admin: hand essence/drops/relics to a player. */
    public void giveSystemItem(final Player player, final String kind, final String id, final int amount) {
        if ("essence".equalsIgnoreCase(kind)) {
            final SpawnerGroup group = config.group(id);
            if (group == null) {
                messages.sendPrefixed(player, "spawner.unknown-mob", Map.of("id", id));
                return;
            }
            giveItem(player, essenceItem(group, amount));
            messages.sendPrefixed(player, "spawner.given", Map.of(
                    "amount", String.valueOf(amount), "item", group.essenceName()));
        } else if ("drop".equalsIgnoreCase(kind)) {
            final SpawnerGroup group = config.groupOf(id);
            if (group == null) {
                messages.sendPrefixed(player, "spawner.unknown-mob", Map.of("id", id));
                return;
            }
            giveItem(player, dropItem(group.mob(id), amount));
            messages.sendPrefixed(player, "spawner.given", Map.of(
                    "amount", String.valueOf(amount), "item", group.mob(id).dropName()));
        } else if ("relic".equalsIgnoreCase(kind)) {
            final SpawnerGroup group = config.group(id);
            if (group == null) {
                messages.sendPrefixed(player, "spawner.unknown-mob", Map.of("id", id));
                return;
            }
            giveItem(player, relicItem(group, amount));
            messages.sendPrefixed(player, "spawner.given", Map.of(
                    "amount", String.valueOf(amount), "item", group.relicName()));
        } else {
            messages.sendPrefixed(player, "spawner.unknown-mob", Map.of("id", kind));
        }
    }

    private void giveItem(final Player player, final ItemStack stack) {
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (final ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------
    // Placement / upgrade / break
    // ------------------------------------------------------------------

    /** Registers a placed spawner item (listener already validated the item). */
    public void registerPlaced(final Player player, final Block block, final String mobId,
                               final SpawnerVariant variant, final int amount) {
        final SpawnerGroup group = config.groupOf(mobId);
        if (group == null) {
            return;
        }
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            return;
        }
        final SpawnerMob mob = group.mob(mobId);
        final SpawnerEntry entry = new SpawnerEntry(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                mobId, variant, island.id(), Math.max(1, amount));
        spawners.put(entry.key(), entry);
        persist();
        applySpawnerState(block, mob, variant, boostOf(island.id()), entry.amount());
        refreshHologram(entry);
        if (entry.amount() > 1) {
            messages.sendPrefixed(player, "spawner.stacked", Map.of(
                    "amount", String.valueOf(entry.amount()),
                    "mob", mob.name(), "variant", variant.display()));
        } else {
            messages.sendPrefixed(player, "spawner.placed", Map.of(
                    "mob", mob.name(), "variant", variant.display()));
        }
    }

    /** True when the player may place a spawner at that block: own island, inside the border. */
    public boolean canPlaceAt(final Player player, final Block block) {
        final Island island = islands.islandOf(player.getUniqueId());
        return island != null
                && island.contains(block.getWorld().getName(), block.getX(), block.getZ());
    }

    /** Applies vanilla spawner tuning for a mob+variant to a spawner block. */
    public void applySpawnerState(final Block block, final SpawnerMob mob, final SpawnerVariant variant) {
        applySpawnerState(block, mob, variant, 1.0, 1);
    }

    /**
     * Applies vanilla spawner tuning with an extra rate multiplier
     * (the island's Spawner Overdrive buff): delays shrink by it.
     */
    public void applySpawnerState(final Block block, final SpawnerMob mob, final SpawnerVariant variant,
                                  final double boost) {
        applySpawnerState(block, mob, variant, boost, 1);
    }

    /**
     * Full spawner tuning: variant rate and buff boost shrink the
     * delays, and a stacked spawner ({@code amount} spawners on one
     * block) spawns proportionally more per cycle.
     */
    public void applySpawnerState(final Block block, final SpawnerMob mob, final SpawnerVariant variant,
                                  final double boost, final int amount) {
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            return;
        }
        final SpawnerVariantSettings settings = config.variantSettings(variant);
        final int[] delays = settings.delays(
                (int) Math.round(config.minDelayTicks() / boost),
                (int) Math.round(config.maxDelayTicks() / boost));
        spawner.setSpawnedType(mob.entity());
        spawner.setMinSpawnDelay(delays[0]);
        spawner.setMaxSpawnDelay(delays[1]);
        spawner.setSpawnCount(Math.max(1, settings.count() * Math.max(1, amount)));
        spawner.setMaxNearbyEntities(settings.nearbyLimit());
        spawner.setRequiredPlayerRange(config.playerRange());
        spawner.setSpawnRange(4);
        spawner.update(true, false);
    }

    /**
     * Stacks a held spawner item onto a placed spawner (sneak-click
     * placement). Returns the new stack total, or -1 when rejected.
     */
    public int stack(final Player player, final SpawnerEntry target, final String mobId,
                     final SpawnerVariant variant, final int addAmount) {
        final Island playerIsland = islands.islandOf(player.getUniqueId());
        if (playerIsland == null || !playerIsland.id().equals(target.islandId())) {
            messages.sendPrefixed(player, "spawner.not-your-spawner");
            return -1;
        }
        if (!target.mobId().equals(mobId) || target.variant() != variant) {
            final SpawnerMob targetMob = mobById(target.mobId());
            messages.sendPrefixed(player, "spawner.stack-mismatch", Map.of(
                    "target", SpawnerStacks.stackName(
                            targetMob == null ? "?" : targetMob.name(),
                            target.variant(), target.amount())));
            return -1;
        }
        if (target.amount() >= config.maxSpawnerStack()) {
            messages.sendPrefixed(player, "spawner.stack-limit", Map.of(
                    "max", String.valueOf(config.maxSpawnerStack())));
            return -1;
        }
        final int newAmount = Math.min(config.maxSpawnerStack(),
                target.amount() + Math.max(1, addAmount));
        final SpawnerEntry updated = target.withAmount(newAmount);
        spawners.put(updated.key(), updated);
        persist();
        final SpawnerMob mob = mobById(updated.mobId());
        final Block block = blockOf(updated);
        if (block != null && mob != null) {
            applySpawnerState(block, mob, updated.variant(),
                    boostOf(updated.islandId()), newAmount);
        }
        refreshHologram(updated);
        messages.sendPrefixed(player, "spawner.stacked", Map.of(
                "amount", String.valueOf(newAmount),
                "mob", mob == null ? "?" : mob.name(),
                "variant", updated.variant().display()));
        return newAmount;
    }

    /** Normal break of a stacked spawner: one comes out, the rest stay. */
    public void unstackOne(final Block block, final SpawnerEntry entry, final Player breaker) {
        final SpawnerEntry updated = entry.withAmount(entry.amount() - 1);
        spawners.put(updated.key(), updated);
        persist();
        final SpawnerMob mob = mobById(updated.mobId());
        if (mob != null) {
            applySpawnerState(block, mob, updated.variant(),
                    boostOf(updated.islandId()), updated.amount());
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                    spawnerItem(mob, updated.variant(), 1));
        }
        refreshHologram(updated);
        if (breaker != null) {
            messages.sendPrefixed(breaker, "spawner.unstacked-one", Map.of(
                    "amount", String.valueOf(updated.amount())));
        }
    }

    /** Final break (or explosion): the entry goes and the whole stack drops as one item. */
    public void unstackAll(final Block block, final SpawnerEntry entry, final Player breaker) {
        spawners.remove(entry.key());
        persist();
        if (holograms != null) {
            holograms.remove(entry);
        }
        final SpawnerGroup group = config.groupOf(entry.mobId());
        if (group != null) {
            final SpawnerMob mob = group.mob(entry.mobId());
            if (mob != null) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                        spawnerItem(mob, entry.variant(), entry.amount()));
            }
        }
        if (breaker != null && entry.amount() > 1) {
            messages.sendPrefixed(breaker, "spawner.took-stack", Map.of(
                    "amount", String.valueOf(entry.amount()),
                    "mob", mobById(entry.mobId()) == null ? "?" : mobById(entry.mobId()).name()));
        }
    }

    // ------------------------------------------------------------------
    // Island spawner boost (Spawner Overdrive buff)
    // ------------------------------------------------------------------

    /** Current boost multiplier for an island (1.0 when unboosted). */
    public double boostOf(final UUID islandId) {
        return boosts.getOrDefault(islandId, 1.0);
    }

    /**
     * Sets an island's spawner-rate multiplier and re-tunes every placed
     * spawner on that island immediately.
     */
    public void setBoost(final UUID islandId, final double multiplier) {
        final double boost = Math.max(1.0, multiplier);
        if (boost == 1.0) {
            boosts.remove(islandId);
        } else {
            boosts.put(islandId, boost);
        }
        refreshIslandSpawners(islandId);
    }

    /** Re-applies spawner state (with the island's current boost) to every placed spawner. */
    public void refreshIslandSpawners(final UUID islandId) {
        for (final SpawnerEntry entry : List.copyOf(spawners.values())) {
            if (!entry.islandId().equals(islandId)) {
                continue;
            }
            final SpawnerGroup group = config.groupOf(entry.mobId());
            final SpawnerMob mob = group == null ? null : group.mob(entry.mobId());
            final Block block = blockOf(entry);
            if (mob != null && block != null) {
                applySpawnerState(block, mob, entry.variant(), boostOf(islandId), entry.amount());
            }
        }
    }

    /** /spawner upgrade — upgrades the spawner the player is looking at. */
    public void upgrade(final Player player) {
        final SpawnerEntry entry = targetEntry(player);
        if (entry == null) {
            messages.sendPrefixed(player, "spawner.upgrade-look");
            return;
        }
        final Island playerIsland = islands.islandOf(player.getUniqueId());
        if (playerIsland == null || !playerIsland.id().equals(entry.islandId())) {
            messages.sendPrefixed(player, "spawner.not-your-spawner");
            return;
        }
        final SpawnerVariant next = entry.variant().next();
        if (next == null) {
            messages.sendPrefixed(player, "spawner.already-mythic");
            return;
        }
        final SpawnerGroup group = config.groupOf(entry.mobId());
        final SpawnerMob mob = group == null ? null : group.mob(entry.mobId());
        final SpawnerUpgradeCost cost = mob == null ? null : mob.upgradeCost(next);
        if (mob == null || cost == null) {
            messages.sendPrefixed(player, "spawner.upgrade-look");
            return;
        }

        // A stacked spawner upgrades as one stack — the cost scales with it.
        final int stackScale = entry.amount();
        final List<String> missing = new ArrayList<>();
        if (countOf(player, essenceItemMatcher(group)) < cost.essence() * stackScale) {
            missing.add((cost.essence() * stackScale - countOf(player, essenceItemMatcher(group)))
                    + " " + group.essenceName());
        }
        if (countOf(player, dropMatcher(mob)) < cost.drops() * stackScale) {
            missing.add((cost.drops() * stackScale - countOf(player, dropMatcher(mob)))
                    + " " + mob.dropName());
        }
        if (countOf(player, relicMatcher(group)) < cost.relics() * stackScale) {
            missing.add((cost.relics() * stackScale - countOf(player, relicMatcher(group)))
                    + " " + group.relicName());
        }
        if (!missing.isEmpty()) {
            messages.sendPrefixed(player, "spawner.missing-items",
                    Map.of("missing", String.join(", ", missing)));
            return;
        }

        removeUpTo(player, essenceItemMatcher(group), cost.essence() * stackScale);
        removeUpTo(player, dropMatcher(mob), cost.drops() * stackScale);
        removeUpTo(player, relicMatcher(group), cost.relics() * stackScale);

        final SpawnerEntry upgraded = new SpawnerEntry(entry.world(), entry.x(), entry.y(), entry.z(),
                entry.mobId(), next, entry.islandId(), entry.amount());
        spawners.put(upgraded.key(), upgraded);
        persist();
        applySpawnerState(blockOf(upgraded), mob, next, boostOf(entry.islandId()), upgraded.amount());
        refreshHologram(upgraded);

        final SpawnerVariantSettings settings = config.variantSettings(next);
        messages.sendPrefixed(player, "spawner.upgraded", Map.of(
                "variant", next.display(),
                "benefit", "x" + trimRate(settings.rate()) + " spawn rate, "
                        + settings.count() + " per cycle"
                        + (settings.autoKill() ? ", auto-kill" : "")
                        + (stackScale > 1 ? ", x" + stackScale + " stack" : "")));
    }

    /** /spawner info — describes the spawner the player is looking at. */
    public void info(final Player player) {
        final SpawnerEntry entry = targetEntry(player);
        if (entry == null) {
            messages.sendPrefixed(player, "spawner.upgrade-look");
            return;
        }
        final SpawnerGroup group = config.groupOf(entry.mobId());
        final SpawnerMob mob = group == null ? null : group.mob(entry.mobId());
        if (mob == null) {
            messages.sendPrefixed(player, "spawner.upgrade-look");
            return;
        }
        final SpawnerVariantSettings settings = config.variantSettings(entry.variant());
        player.sendMessage(messages.prefix() + ColorUtil.colorize(
                "&b" + (entry.amount() > 1 ? entry.amount() + "x " : "") + mob.name()
                        + " Spawner &8— &f" + variantColor(entry.variant())
                        + entry.variant().display()));
        player.sendMessage(messages.prefix() + ColorUtil.colorize(
                "&7Spawn rate &fx" + trimRate(settings.rate()) + "&7, &f"
                        + settings.count() * entry.amount() + " &7per cycle, up to &f"
                        + settings.nearbyLimit() + " &7mobs nearby"
                        + (settings.autoKill() ? "&7, &dauto-kill" : "")));
        if (entry.amount() > 1) {
            player.sendMessage(messages.prefix() + ColorUtil.colorize(
                    "&7Stack: &f" + entry.amount() + "x"
                    + (entry.amount() >= config.maxSpawnerStack() ? " &8(max)" : "")));
        }
        final SpawnerVariant next = entry.variant().next();
        if (next != null) {
            final SpawnerUpgradeCost cost = mob.upgradeCost(next);
            if (cost != null) {
                player.sendMessage(messages.prefix() + ColorUtil.colorize(
                        "&7Next: &f" + next.display() + " &8— &e" + cost.essence() + " "
                                + group.essenceName() + "&8, &e" + cost.drops() + " " + mob.dropName()
                                + (cost.relics() > 0 ? "&8, &d" + cost.relics() + " " + group.relicName() : "")));
            }
        } else {
            player.sendMessage(messages.prefix() + ColorUtil.colorize("&7This spawner is &dmaxed&7."));
        }
    }

    /** Called by the listener when a registered spawner block is broken: drop the item. */
    public void onSpawnerBreak(final Block block, final SpawnerEntry entry) {
        spawners.remove(entry.key());
        persist();
        final SpawnerGroup group = config.groupOf(entry.mobId());
        if (group != null) {
            final SpawnerMob mob = group.mob(entry.mobId());
            if (mob != null) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                        spawnerItem(mob, entry.variant()));
            }
        }
    }

    public SpawnerEntry entryAt(final Block block) {
        return spawners.get(new SpawnerEntry(block.getWorld().getName(), block.getX(), block.getY(),
                block.getZ(), "", SpawnerVariant.NORMAL, UUID.randomUUID()).key());
    }

    private SpawnerEntry targetEntry(final Player player) {
        final Block target = player.getTargetBlockExact(6);
        return target == null ? null : entryAt(target);
    }

    private Block blockOf(final SpawnerEntry entry) {
        final org.bukkit.World world = Bukkit.getWorld(entry.world());
        return world == null ? null : world.getBlockAt(entry.x(), entry.y(), entry.z());
    }

    // ------------------------------------------------------------------
    // Island luck
    // ------------------------------------------------------------------

    /** /spawner luck — shows the island's luck level. */
    public void luckShow(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "spawner.no-island");
            return;
        }
        final int level = luck.getOrDefault(island.id(), 0);
        messages.sendPrefixed(player, "spawner.luck", Map.of(
                "level", String.valueOf(level),
                "max", String.valueOf(config.luckMaxLevel()),
                "chance", chanceText(config.uniqueDropChance(level))));
    }

    /** /spawner luck upgrade — buys the next luck level with coins. */
    public void luckUpgrade(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "spawner.no-island");
            return;
        }
        final int level = luck.getOrDefault(island.id(), 0);
        if (level >= config.luckMaxLevel()) {
            messages.sendPrefixed(player, "spawner.luck-maxed", Map.of(
                    "chance", chanceText(config.uniqueDropChance(level))));
            return;
        }
        final double cost = config.luckCost(level + 1);
        if (cost < 0 || !economy.has(player.getUniqueId(), cost)) {
            messages.sendPrefixed(player, "spawner.cannot-afford", Map.of(
                    "cost", com.coremc.core.shop.Money.format(Math.max(0, cost), "$"),
                    "balance", com.coremc.core.shop.Money.format(economy.balance(player.getUniqueId()), "$")));
            return;
        }
        economy.withdraw(player.getUniqueId(), cost);
        luck.put(island.id(), level + 1);
        persist();
        messages.sendPrefixed(player, "spawner.luck-upgraded", Map.of(
                "level", String.valueOf(level + 1),
                "chance", chanceText(config.uniqueDropChance(level + 1))));
    }

    /** Admin: force an online player's island luck level. */
    public void setLuck(final Player target, final int level) {
        final Island island = islands.islandOf(target.getUniqueId());
        if (island == null) {
            return;
        }
        final int clamped = Math.max(0, Math.min(config.luckMaxLevel(), level));
        luck.put(island.id(), clamped);
        persist();
        messages.sendPrefixed(target, "spawner.set-luck", Map.of(
                "level", String.valueOf(clamped),
                "chance", chanceText(config.uniqueDropChance(clamped))));
    }

    public int luckOf(final Island island) {
        return island == null ? 0 : luck.getOrDefault(island.id(), 0);
    }

    private String chanceText(final double chance) {
        return Math.round(chance * 100) + "%";
    }

    // ------------------------------------------------------------------
    // Kills: essence / unique drops / relics
    // ------------------------------------------------------------------

    /** Credits kill rewards for a configured mob death. */
    public void onMobDeath(final LivingEntity entity, final Player killer) {
        final SpawnerEntry autoKill = autoKillMobs.remove(entity.getUniqueId());
        final SpawnerMob mob = config.mobByEntity(entity.getType());
        if (mob == null) {
            return;
        }
        final SpawnerGroup group = config.groupOf(mob.id());

        Player beneficiary = killer;
        Island island = killer == null ? null : islands.islandOf(killer.getUniqueId());
        if (killer == null) {
            // Mythic auto-kill: credit the spawner's island owner.
            if (autoKill == null) {
                return;
            }
            final Island spawnerIsland = islands.islandById(autoKill.islandId());
            if (spawnerIsland == null) {
                return;
            }
            island = spawnerIsland;
            final Player owner = Bukkit.getPlayer(spawnerIsland.owner());
            if (owner == null) {
                return; // offline: no credit (vanilla loot still drops)
            }
            beneficiary = owner;
        }

        final int luckLevel = luckOf(island);

        // a killed mob stack counts as that many kills, but only says so once
        final int kills = mobStacks.countOf(entity);
        boolean foundEssence = false;
        boolean foundDrop = false;
        boolean foundRelic = false;
        for (int kill = 0; kill < kills; kill++) {
            if (random.nextDouble() < config.essenceChance()) {
                giveItem(beneficiary, essenceItem(group, 1));
                foundEssence = true;
            }
            if (random.nextDouble() < config.uniqueDropChance(luckLevel)) {
                giveItem(beneficiary, dropItem(mob, 1));
                foundDrop = true;
            }
            if (random.nextDouble() < config.relicChance()) {
                giveItem(beneficiary, relicItem(group, 1));
                foundRelic = true;
            }
        }
        if (foundEssence && config.announceEssence()) {
            messages.sendPrefixed(beneficiary, "spawner.found-essence",
                    Map.of("essence", group.essenceName()));
        }
        if (foundDrop) {
            messages.sendPrefixed(beneficiary, "spawner.found-drop",
                    Map.of("drop", mob.dropName()));
        }
        if (foundRelic) {
            messages.sendPrefixed(beneficiary, "spawner.found-relic",
                    Map.of("relic", group.relicName()));
        }
    }

    /** Scheduled auto-kill for Mythic spawns (and mob stacking). */
    public void onCreatureSpawn(final LivingEntity entity) {
        if (mobStacks.tryStack(entity)) {
            return; // merged into a nearby stack — this entity is gone
        }
        if (autoKillMobs.size() > 10_000) {
            autoKillMobs.clear(); // paranoia valve
        }
        final SpawnerEntry mythic = nearbyMythicSpawner(entity.getLocation(), entity.getType());
        if (mythic == null) {
            return;
        }
        autoKillMobs.put(entity.getUniqueId(), mythic);
        final UUID entityId = entity.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final org.bukkit.entity.Entity current = Bukkit.getEntity(entityId);
            if (current instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                autoKillMobs.put(entityId, mythic); // in case it despawned from the map
                living.setHealth(0);
            } else {
                autoKillMobs.remove(entityId);
            }
        }, AUTO_KILL_DELAY_TICKS);
    }

    /** The registered spawner of this mob type within {@code range} of a location, or null. */
    SpawnerEntry nearbySpawnerEntry(final Location location, final EntityType type, final int range) {
        for (final SpawnerEntry entry : spawners.values()) {
            if (!entry.world().equals(location.getWorld().getName())) {
                continue;
            }
            final SpawnerMob mob = mobById(entry.mobId());
            if (mob == null || mob.entity() != type) {
                continue;
            }
            if (Math.abs(entry.x() - location.getX()) <= range
                    && Math.abs(entry.y() - location.getY()) <= range
                    && Math.abs(entry.z() - location.getZ()) <= range) {
                return entry;
            }
        }
        return null;
    }

    private SpawnerEntry nearbyMythicSpawner(final Location location, final EntityType type) {
        final SpawnerEntry entry = nearbySpawnerEntry(location, type, AUTO_KILL_SPAWN_RANGE);
        return entry != null && entry.variant() == SpawnerVariant.MYTHIC ? entry : null;
    }

    // ------------------------------------------------------------------
    // Island deletion
    // ------------------------------------------------------------------

    /** Removes all spawner-system state for a deleted island. */
    public void onIslandDeleted(final Island island) {
        luck.remove(island.id());
        for (final SpawnerEntry entry : List.copyOf(spawners.values())) {
            if (!entry.islandId().equals(island.id())) {
                continue;
            }
            spawners.remove(entry.key());
            if (holograms != null) {
                holograms.remove(entry);
            }
            // The island blocks may outlive the registry entry — clear the
            // spawner itself so no orphaned vanilla spawner keeps running.
            final Block block = blockOf(entry);
            if (block != null && block.getType() == Material.SPAWNER) {
                block.setType(Material.AIR);
            }
        }
        persist();
    }

    // ------------------------------------------------------------------
    // Inventory helpers (matchers)
    // ------------------------------------------------------------------

    private interface ItemMatcher {

        boolean matches(ItemStack stack);
    }

    private ItemMatcher essenceItemMatcher(final SpawnerGroup group) {
        return stack -> isCustomItem(stack, "essence:" + group.id(), group.essenceMaterial(),
                "&b" + group.essenceName());
    }

    private ItemMatcher dropMatcher(final SpawnerMob mob) {
        return stack -> isCustomItem(stack, "drop:" + mob.id(), mob.dropMaterial(),
                "&e" + mob.dropName());
    }

    private ItemMatcher relicMatcher(final SpawnerGroup group) {
        return stack -> isCustomItem(stack, "relic:" + group.id(), group.relicMaterial(),
                "&d" + group.relicName());
    }

    private int countOf(final Player player, final ItemMatcher matcher) {
        int total = 0;
        for (final ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && matcher.matches(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} matching items; returns the removed count. */
    private int removeUpTo(final Player player, final ItemMatcher matcher, final int amount) {
        if (amount <= 0) {
            return 0;
        }
        final ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            final ItemStack stack = contents[i];
            if (stack == null || !matcher.matches(stack)) {
                continue;
            }
            final int take = Math.min(stack.getAmount(), remaining);
            if (take >= stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            remaining -= take;
        }
        if (remaining < amount) {
            player.getInventory().setStorageContents(contents);
        }
        return amount - remaining;
    }

    // ------------------------------------------------------------------
    // misc
    // ------------------------------------------------------------------

    private static String variantColor(final SpawnerVariant variant) {
        return SpawnerStacks.variantColor(variant);
    }

    /** Mob config by id, or null. */
    public SpawnerMob mobById(final String mobId) {
        final SpawnerGroup group = config.groupOf(mobId);
        return group == null ? null : group.mob(mobId);
    }

    /** The entry registered under a position key, or null. */
    public SpawnerEntry entryByKey(final String key) {
        return spawners.get(key);
    }

    /** Entries whose block lies in the given chunk. */
    public List<SpawnerEntry> entriesIn(final String world, final int chunkX, final int chunkZ) {
        final List<SpawnerEntry> found = new ArrayList<>();
        for (final SpawnerEntry entry : spawners.values()) {
            if (entry.world().equals(world)
                    && (entry.x() >> 4) == chunkX && (entry.z() >> 4) == chunkZ) {
                found.add(entry);
            }
        }
        return found;
    }

    /** True when this entity is scheduled for a Mythic auto-kill. */
    public boolean isAutoKillMob(final LivingEntity entity) {
        return autoKillMobs.containsKey(entity.getUniqueId());
    }

    /** Mob stacking runtime. */
    public MobStacks mobStacks() {
        return mobStacks;
    }

    /** Attaches the hologram manager and labels every already-loaded spawner. */
    public void attach(final SpawnerHolograms holograms) {
        this.holograms = holograms;
        for (final SpawnerEntry entry : List.copyOf(spawners.values())) {
            holograms.ensure(entry);
        }
    }

    private void refreshHologram(final SpawnerEntry entry) {
        if (holograms != null) {
            holograms.ensure(entry);
        }
    }

    private static String trimRate(final double rate) {
        return rate == Math.floor(rate) ? String.valueOf((long) rate) : String.valueOf(rate);
    }

    public SpawnerConfig config() {
        return config;
    }
}
