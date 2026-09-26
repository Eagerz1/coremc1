package com.coremc.core.gens;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandPointsService;
import com.coremc.core.island.IslandService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Generator runtime: the custom item factory and matching, buying,
 * placement, stacking, upgrading, breaking and the production tick
 * that pays out every placed generator.
 *
 * <p>All state is main-thread only and every mutation is written
 * through to {@code generators-data.yml} immediately — the same
 * contract the island and spawner systems follow, so a restart (or a
 * crash) never loses a placed generator.</p>
 */
public final class GeneratorService {

    /** Faces a generator pushes its physical output into. */
    private static final BlockFace[] OUTPUT_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    private final JavaPlugin plugin;
    private final GeneratorConfig config;
    private final GeneratorDataStore store;
    private final EconomyService economy;
    private final MessageService messages;
    private final IslandService islands;
    private final IslandPointsService points;
    private final Logger logger;

    private final NamespacedKey genKey;
    private final NamespacedKey amountKey;

    /** Placed generators by block key. */
    private final Map<String, GeneratorEntry> generators = new LinkedHashMap<>();
    /** When each placed generator next produces (epoch millis). */
    private final Map<String, Long> nextRun = new LinkedHashMap<>();

    private GeneratorHolograms holograms;
    private int taskId = -1;

    public GeneratorService(final JavaPlugin plugin, final GeneratorConfig config,
                            final GeneratorDataStore store, final EconomyService economy,
                            final MessageService messages, final IslandService islands,
                            final IslandPointsService points) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.economy = economy;
        this.messages = messages;
        this.islands = islands;
        this.points = points;
        this.logger = plugin.getLogger();
        this.genKey = new NamespacedKey(plugin, "coremc_generator");
        this.amountKey = new NamespacedKey(plugin, "coremc_generator_amount");
    }

    /** Loads persisted generators (call once, after the island service). */
    public void load() {
        try {
            for (final GeneratorEntry entry : store.load()) {
                if (config.byId(entry.genId()) == null) {
                    logger.warning("Generator '" + entry.genId() + "' at " + entry.key()
                            + " is not in generators.yml — keeping the data, skipping production.");
                }
                generators.put(entry.key(), entry);
            }
        } catch (final IOException exception) {
            logger.severe("Could not load generators-data.yml: " + exception.getMessage());
        }
    }

    /** Starts the production tick (once per second). */
    public void start() {
        if (taskId != -1) {
            return;
        }
        this.taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L).getTaskId();
    }

    /** Stops the production tick and flushes the store. */
    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        persist();
    }

    private void persist() {
        try {
            store.save(List.copyOf(generators.values()));
        } catch (final IOException exception) {
            logger.severe("Could not save generators-data.yml: " + exception.getMessage());
        }
    }

    /** Attaches the hologram renderer (after construction, like the spawners). */
    public void attach(final GeneratorHolograms attached) {
        this.holograms = attached;
    }

    public GeneratorConfig config() {
        return config;
    }

    // ------------------------------------------------------------------
    // items
    // ------------------------------------------------------------------

    /** The placeable generator item (a stacked one when {@code amount > 1}). */
    public ItemStack generatorItem(final GeneratorTier tier, final int amount) {
        final int count = Math.max(1, amount);
        final ItemStack stack = new ItemStack(tier.block());
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(GeneratorLore.title(tier, count)));
            final List<String> lore = new ArrayList<>();
            for (final String line : GeneratorLore.item(tier, count)) {
                lore.add(ColorUtil.colorize(line));
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(genKey, PersistentDataType.STRING, tier.id());
            if (count > 1) {
                meta.getPersistentDataContainer().set(amountKey, PersistentDataType.INTEGER, count);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** The generator id carried by an item, or null when it is not one of ours. */
    public String itemGeneratorId(final ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || stack.getItemMeta() == null) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(genKey, PersistentDataType.STRING);
    }

    /** How many generators one unit of a generator item represents. */
    public int itemAmount(final ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || stack.getItemMeta() == null) {
            return 1;
        }
        final Integer amount = stack.getItemMeta().getPersistentDataContainer()
                .get(amountKey, PersistentDataType.INTEGER);
        return amount == null || amount < 1 ? 1 : amount;
    }

    private void giveItem(final Player player, final ItemStack stack) {
        for (final ItemStack rest : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------
    // buying
    // ------------------------------------------------------------------

    /**
     * Buys {@code quantity} generators of a tier: island requirement
     * first, then coins. Returns true when the purchase happened.
     */
    public boolean buy(final Player player, final String genId, final int quantity) {
        final GeneratorTier tier = config.byId(genId);
        if (tier == null) {
            messages.sendPrefixed(player, "gens.unknown-generator", Map.of("id", String.valueOf(genId)));
            return false;
        }
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "gens.no-island");
            return false;
        }
        if (!unlocked(island, tier)) {
            messages.sendPrefixed(player, "gens.locked", Map.of(
                    "generator", tier.name(),
                    "points", GuiText.number(tier.requiredPoints()),
                    "have", GuiText.number(points.points(island))));
            return false;
        }
        final int count = Math.max(1, quantity);
        final double price = tier.price() * count;
        if (!economy.has(player.getUniqueId(), price)) {
            messages.sendPrefixed(player, "gens.cannot-afford", Map.of(
                    "cost", GuiText.money(price),
                    "balance", GuiText.money(economy.balance(player.getUniqueId()))));
            return false;
        }
        economy.withdraw(player.getUniqueId(), price);
        giveItem(player, generatorItem(tier, 1).asQuantity(count));
        messages.sendPrefixed(player, "gens.bought", Map.of(
                "amount", String.valueOf(count),
                "generator", tier.name(),
                "cost", GuiText.money(price)));
        return true;
    }

    /** Admin: hand generator items to a player. */
    public void give(final Player target, final GeneratorTier tier, final int amount) {
        giveItem(target, generatorItem(tier, 1).asQuantity(Math.max(1, amount)));
        messages.sendPrefixed(target, "gens.given", Map.of(
                "amount", String.valueOf(Math.max(1, amount)),
                "generator", tier.name()));
    }

    /** Whether an island has earned the points a generator requires. */
    public boolean unlocked(final Island island, final GeneratorTier tier) {
        return island != null && points.points(island) + 1e-9 >= tier.requiredPoints();
    }

    /** The island's current points (0 without an island). */
    public double pointsOf(final Island island) {
        return island == null ? 0 : points.points(island);
    }

    // ------------------------------------------------------------------
    // placement
    // ------------------------------------------------------------------

    /** True when the player may place a generator at that block: own island, inside the border. */
    public boolean canPlaceAt(final Player player, final Block block) {
        final Island island = islands.islandOf(player.getUniqueId());
        return island != null
                && island.contains(block.getWorld().getName(), block.getX(), block.getZ());
    }

    /**
     * Registers a placed generator. Returns false (with a message and
     * without registering) when the island is at a cap — the caller
     * then cancels the placement.
     */
    public boolean registerPlaced(final Player player, final Block block, final String genId,
                                  final int amount) {
        final GeneratorTier tier = config.byId(genId);
        if (tier == null) {
            return false;
        }
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "gens.no-island");
            return false;
        }
        final int adding = Math.max(1, amount);
        if (countOnIsland(island.id()) + adding > config.maxPerIsland()) {
            messages.sendPrefixed(player, "gens.island-limit",
                    Map.of("max", String.valueOf(config.maxPerIsland())));
            return false;
        }
        if (countOnIsland(island.id(), tier.id()) + adding > tier.maxPlaced()) {
            messages.sendPrefixed(player, "gens.type-limit", Map.of(
                    "generator", tier.name(), "max", String.valueOf(tier.maxPlaced())));
            return false;
        }
        final GeneratorEntry entry = new GeneratorEntry(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), tier.id(), island.id(),
                player.getUniqueId(), adding);
        generators.put(entry.key(), entry);
        schedule(entry, tier);
        persist();
        refreshHologram(entry);
        messages.sendPrefixed(player, adding > 1 ? "gens.stacked" : "gens.placed", Map.of(
                "generator", tier.name(),
                "amount", String.valueOf(adding),
                "value", GuiText.money(tier.value() * adding),
                "interval", GuiText.seconds(tier.intervalSeconds())));
        return true;
    }

    /**
     * Stacks a held generator item onto a placed one (sneak-click).
     * Returns the new stack total, or -1 when rejected.
     */
    public int stack(final Player player, final GeneratorEntry target, final String genId,
                     final int addAmount) {
        if (!mayManage(player, target)) {
            messages.sendPrefixed(player, "gens.not-your-generator");
            return -1;
        }
        if (!target.genId().equals(genId)) {
            final GeneratorTier targetTier = config.byId(target.genId());
            messages.sendPrefixed(player, "gens.stack-mismatch", Map.of(
                    "target", targetTier == null ? target.genId() : targetTier.name()));
            return -1;
        }
        if (target.amount() >= config.maxStack()) {
            messages.sendPrefixed(player, "gens.stack-limit",
                    Map.of("max", String.valueOf(config.maxStack())));
            return -1;
        }
        final GeneratorTier tier = config.byId(target.genId());
        if (tier == null) {
            return -1;
        }
        final int adding = Math.max(1, addAmount);
        if (target.amount() + adding > tier.maxPlaced()) {
            messages.sendPrefixed(player, "gens.type-limit", Map.of(
                    "generator", tier.name(), "max", String.valueOf(tier.maxPlaced())));
            return -1;
        }
        if (countOnIsland(target.islandId()) + adding > config.maxPerIsland()) {
            messages.sendPrefixed(player, "gens.island-limit",
                    Map.of("max", String.valueOf(config.maxPerIsland())));
            return -1;
        }
        final int newAmount = Math.min(config.maxStack(), target.amount() + adding);
        final GeneratorEntry updated = target.withAmount(newAmount);
        generators.put(updated.key(), updated);
        persist();
        refreshHologram(updated);
        messages.sendPrefixed(player, "gens.stacked", Map.of(
                "generator", tier.name(),
                "amount", String.valueOf(newAmount),
                "value", GuiText.money(tier.value() * newAmount),
                "interval", GuiText.seconds(tier.intervalSeconds())));
        return newAmount;
    }

    /** Normal break of a stacked generator: one comes out, the rest stay. */
    public void unstackOne(final Block block, final GeneratorEntry entry, final Player breaker) {
        final GeneratorTier tier = config.byId(entry.genId());
        final GeneratorEntry updated = entry.withAmount(entry.amount() - 1);
        generators.put(updated.key(), updated);
        persist();
        refreshHologram(updated);
        if (tier != null) {
            final ItemStack one = generatorItem(tier, 1);
            if (breaker != null) {
                giveItem(breaker, one);
            } else {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), one);
            }
        }
        if (breaker != null) {
            messages.sendPrefixed(breaker, "gens.unstacked-one",
                    Map.of("amount", String.valueOf(updated.amount())));
        }
    }

    /** Final break (or pick-up): the whole stack comes back as one item. */
    public void unstackAll(final Block block, final GeneratorEntry entry, final Player breaker) {
        generators.remove(entry.key());
        nextRun.remove(entry.key());
        persist();
        if (holograms != null) {
            holograms.remove(entry);
        }
        final GeneratorTier tier = config.byId(entry.genId());
        if (tier != null) {
            final ItemStack recovered = generatorItem(tier, entry.amount());
            if (breaker != null) {
                giveItem(breaker, recovered);
            } else if (block != null) {
                block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5), recovered);
            }
            if (breaker != null) {
                messages.sendPrefixed(breaker, "gens.took-stack", Map.of(
                        "amount", String.valueOf(entry.amount()),
                        "generator", tier.name()));
            }
        }
        if (block != null && block.getType() != Material.AIR) {
            block.setType(Material.AIR, false);
        }
    }

    /** GUI pick-up: the whole stack back into the player's inventory. */
    public boolean pickup(final Player player, final GeneratorEntry entry) {
        if (!mayManage(player, entry)) {
            messages.sendPrefixed(player, "gens.not-your-generator");
            return false;
        }
        unstackAll(blockOf(entry), entry, player);
        return true;
    }

    // ------------------------------------------------------------------
    // upgrading
    // ------------------------------------------------------------------

    /** Total cost of upgrading a placed generator stack. */
    public double upgradeCost(final GeneratorEntry entry) {
        final GeneratorTier tier = config.byId(entry.genId());
        return tier == null ? 0 : tier.upgradeCost() * entry.amount();
    }

    /**
     * Upgrades a placed generator (the whole stack) into the next
     * tier. Returns true when it happened; every failure explains
     * itself with a branded message.
     */
    public boolean upgrade(final Player player, final GeneratorEntry entry) {
        final GeneratorEntry current = generators.get(entry.key());
        if (current == null) {
            messages.sendPrefixed(player, "gens.gone");
            return false;
        }
        if (!mayManage(player, current)) {
            messages.sendPrefixed(player, "gens.not-your-generator");
            return false;
        }
        final GeneratorTier tier = config.byId(current.genId());
        final GeneratorTier next = config.next(tier);
        if (tier == null) {
            return false;
        }
        if (next == null) {
            messages.sendPrefixed(player, "gens.max-tier", Map.of("generator", tier.name()));
            return false;
        }
        final Island island = islands.islandById(current.islandId());
        if (!unlocked(island, next)) {
            messages.sendPrefixed(player, "gens.locked", Map.of(
                    "generator", next.name(),
                    "points", GuiText.number(next.requiredPoints()),
                    "have", GuiText.number(pointsOf(island))));
            return false;
        }
        final double cost = tier.upgradeCost() * current.amount();
        if (!economy.has(player.getUniqueId(), cost)) {
            messages.sendPrefixed(player, "gens.cannot-afford", Map.of(
                    "cost", GuiText.money(cost),
                    "balance", GuiText.money(economy.balance(player.getUniqueId()))));
            return false;
        }
        economy.withdraw(player.getUniqueId(), cost);

        final GeneratorEntry upgraded = current.withGenerator(next.id());
        generators.put(upgraded.key(), upgraded);
        schedule(upgraded, next);
        persist();
        final Block block = blockOf(upgraded);
        if (block != null) {
            block.setType(next.block(), false);
        }
        refreshHologram(upgraded);
        messages.sendPrefixed(player, "gens.upgraded", Map.of(
                "from", tier.name(),
                "to", next.name(),
                "cost", GuiText.money(cost),
                "value", GuiText.money(next.value() * upgraded.amount()),
                "interval", GuiText.seconds(next.intervalSeconds())));
        return true;
    }

    // ------------------------------------------------------------------
    // production
    // ------------------------------------------------------------------

    /** One production pass over every placed generator (once per second). */
    void tick() {
        if (generators.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final GeneratorEntry entry : List.copyOf(generators.values())) {
            final GeneratorTier tier = config.byId(entry.genId());
            if (tier == null) {
                continue;
            }
            if (config.requireOnline() && !islandHasOnlineMember(entry.islandId())) {
                nextRun.put(entry.key(), now + tier.intervalSeconds() * 1000L);
                continue;
            }
            final Long due = nextRun.get(entry.key());
            if (due == null) {
                schedule(entry, tier);
                continue;
            }
            if (now < due) {
                continue;
            }
            nextRun.put(entry.key(), now + tier.intervalSeconds() * 1000L);
            produce(entry, tier);
        }
    }

    private void produce(final GeneratorEntry entry, final GeneratorTier tier) {
        // A generator whose block is gone (world edit, other plugin) is
        // dropped from the registry instead of paying forever.
        final Block block = blockOf(entry);
        if (block != null && block.getChunk().isLoaded() && block.getType() != tier.block()) {
            generators.remove(entry.key());
            nextRun.remove(entry.key());
            persist();
            if (holograms != null) {
                holograms.remove(entry);
            }
            return;
        }
        final UUID payee = payeeOf(entry);
        if (payee != null && tier.value() > 0) {
            economy.deposit(payee, tier.value() * entry.amount());
        }
        if (config.produceItems() && tier.output() != null && block != null
                && block.getChunk().isLoaded()) {
            depositItems(block, tier, entry.amount());
        }
    }

    /** Pushes the physical output into an adjacent container, if there is one. */
    private void depositItems(final Block block, final GeneratorTier tier, final int amount) {
        final int total = tier.outputAmount() * amount;
        if (total <= 0) {
            return;
        }
        int remaining = total;
        for (final BlockFace face : OUTPUT_FACES) {
            if (remaining <= 0) {
                return;
            }
            final Block neighbour = block.getRelative(face);
            if (!(neighbour.getState() instanceof Container container)) {
                continue;
            }
            final ItemStack produced = new ItemStack(tier.output(), remaining);
            final Map<Integer, ItemStack> leftover = container.getInventory().addItem(produced);
            remaining = 0;
            for (final ItemStack rest : leftover.values()) {
                remaining += rest.getAmount();
            }
        }
    }

    /** Who a generator pays: its placer, falling back to the island owner. */
    private UUID payeeOf(final GeneratorEntry entry) {
        if (entry.owner() != null) {
            return entry.owner();
        }
        final Island island = islands.islandById(entry.islandId());
        return island == null ? null : island.owner();
    }

    private boolean islandHasOnlineMember(final UUID islandId) {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            final Island island = islands.islandOf(online.getUniqueId());
            if (island != null && island.id().equals(islandId)) {
                return true;
            }
        }
        return false;
    }

    private void schedule(final GeneratorEntry entry, final GeneratorTier tier) {
        nextRun.put(entry.key(),
                System.currentTimeMillis() + tier.intervalSeconds() * 1000L);
    }

    /** Seconds until a generator next produces (its full interval when unknown). */
    public long secondsUntilNext(final GeneratorEntry entry) {
        final GeneratorTier tier = config.byId(entry.genId());
        final Long due = nextRun.get(entry.key());
        if (tier == null) {
            return 0;
        }
        if (due == null) {
            return tier.intervalSeconds();
        }
        return Math.max(0, (due - System.currentTimeMillis() + 999) / 1000);
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    /** The generator registered at a block, or null. */
    public GeneratorEntry entryAt(final Block block) {
        if (block == null) {
            return null;
        }
        return generators.get(block.getWorld().getName() + ":" + block.getX() + ":"
                + block.getY() + ":" + block.getZ());
    }

    /** The generator registered under a key, or null. */
    public GeneratorEntry entryByKey(final String key) {
        return key == null ? null : generators.get(key);
    }

    /** Every generator registered in one chunk. */
    public List<GeneratorEntry> entriesIn(final String world, final int chunkX, final int chunkZ) {
        final List<GeneratorEntry> found = new ArrayList<>();
        for (final GeneratorEntry entry : generators.values()) {
            if (entry.world().equals(world) && entry.chunkX() == chunkX
                    && entry.chunkZ() == chunkZ) {
                found.add(entry);
            }
        }
        return found;
    }

    /** Every generator on an island. */
    public List<GeneratorEntry> entriesOf(final UUID islandId) {
        final List<GeneratorEntry> found = new ArrayList<>();
        for (final GeneratorEntry entry : generators.values()) {
            if (entry.islandId().equals(islandId)) {
                found.add(entry);
            }
        }
        return found;
    }

    /** How many generators (counting stacks) an island has placed. */
    public int countOnIsland(final UUID islandId) {
        int total = 0;
        for (final GeneratorEntry entry : generators.values()) {
            if (entry.islandId().equals(islandId)) {
                total += entry.amount();
            }
        }
        return total;
    }

    /** How many generators of one type (counting stacks) an island has placed. */
    public int countOnIsland(final UUID islandId, final String genId) {
        int total = 0;
        for (final GeneratorEntry entry : generators.values()) {
            if (entry.islandId().equals(islandId) && entry.genId().equals(genId)) {
                total += entry.amount();
            }
        }
        return total;
    }

    /** Coins per hour every generator on an island produces together. */
    public double incomePerHour(final UUID islandId) {
        double total = 0;
        for (final GeneratorEntry entry : entriesOf(islandId)) {
            final GeneratorTier tier = config.byId(entry.genId());
            if (tier != null) {
                total += tier.valuePerHour() * entry.amount();
            }
        }
        return total;
    }

    /** Whether the player may manage (stack, upgrade, pick up) a generator. */
    public boolean mayManage(final Player player, final GeneratorEntry entry) {
        if (player.hasPermission("coremc.island.bypass")) {
            return true;
        }
        final Island island = islands.islandById(entry.islandId());
        return island != null && island.isMember(player.getUniqueId());
    }

    /** The block a registration points at (null when the world is gone). */
    public Block blockOf(final GeneratorEntry entry) {
        final World world = Bukkit.getWorld(entry.world());
        return world == null ? null : world.getBlockAt(entry.x(), entry.y(), entry.z());
    }

    /** Display name of a generator's owner. */
    public String ownerName(final GeneratorEntry entry) {
        final UUID owner = payeeOf(entry);
        if (owner == null) {
            return "unknown";
        }
        final String name = Bukkit.getOfflinePlayer(owner).getName();
        return name == null ? "unknown" : name;
    }

    /** Display name of the island a generator stands on. */
    public String islandName(final GeneratorEntry entry) {
        final Island island = islands.islandById(entry.islandId());
        return island == null ? "unknown" : island.ownerName() + "'s island";
    }

    /** The player's island (or null). */
    public Island islandOf(final Player player) {
        return islands.islandOf(player.getUniqueId());
    }

    /** The island a placed generator stands on (or null). */
    public Island islandOf(final GeneratorEntry entry) {
        return entry == null ? null : islands.islandById(entry.islandId());
    }

    // ------------------------------------------------------------------
    // housekeeping
    // ------------------------------------------------------------------

    /** Island deleted: its generators go with it. */
    public void onIslandDeleted(final Island island) {
        boolean changed = false;
        for (final GeneratorEntry entry : List.copyOf(generators.values())) {
            if (entry.islandId().equals(island.id())) {
                generators.remove(entry.key());
                nextRun.remove(entry.key());
                if (holograms != null) {
                    holograms.remove(entry);
                }
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private void refreshHologram(final GeneratorEntry entry) {
        if (holograms != null) {
            holograms.ensure(entry);
        }
    }

    /** The location above a generator (hologram anchor / effects). */
    public Location centreOf(final GeneratorEntry entry) {
        final World world = Bukkit.getWorld(entry.world());
        return world == null ? null
                : new Location(world, entry.x() + 0.5, entry.y() + 0.5, entry.z() + 0.5);
    }
}
