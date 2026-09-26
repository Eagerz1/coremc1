package com.coremc.core.crate;

import com.coremc.core.config.MessageService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.store.TransactionLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The crate machinery: admin-bound crate blocks, key-gated openings
 * and safe reward delivery.
 *
 * <p>Opening rules that make duplication impossible:</p>
 * <ul>
 *   <li>the key is validated by PDC only — display names are never
 *       trusted,</li>
 *   <li>exactly one key is consumed from the clicked hand and the
 *       rolled reward is written to the pending ledger BEFORE anything
 *       is handed out — a crash, disconnect or full inventory can
 *       never lose or duplicate the win,</li>
 *   <li>a short per-player cooldown swallows double-clicks and rapid
 *       interaction spam (interactions also arrive strictly ordered on
 *       the main thread).</li>
 * </ul>
 */
public final class CrateService {

    /** Milliseconds between crate openings per player (double-click guard). */
    private static final long COOLDOWN_MS = 600;

    private final CrateConfig config;
    private final KeyItems keyItems;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final TransactionLog transactions;
    private final MessageService messages;
    private final Logger logger;
    private final Path bindingsFile;
    private final Random random = new Random();

    /** "world;x;y;z" → crate id. */
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    public CrateService(final CrateConfig config, final KeyItems keyItems,
                        final PendingRewards pending, final RewardDeliverer deliverer,
                        final TransactionLog transactions, final MessageService messages,
                        final Logger logger, final Path bindingsFile) {
        this.config = config;
        this.keyItems = keyItems;
        this.pending = pending;
        this.deliverer = deliverer;
        this.transactions = transactions;
        this.messages = messages;
        this.logger = logger;
        this.bindingsFile = bindingsFile;
    }

    // ------------------------------------------------------------------
    // bindings
    // ------------------------------------------------------------------

    /** Loads the crate block bindings (crates-data.yml). */
    public void load() {
        bindings.clear();
        if (!Files.isRegularFile(bindingsFile)) {
            return;
        }
        final org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(bindingsFile, StandardCharsets.UTF_8));
        } catch (final IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            logger.warning("Corrupt crate bindings " + bindingsFile + " — starting fresh: "
                    + e.getMessage());
            return;
        }
        for (final String key : yaml.getKeys(false)) {
            final String crateId = yaml.getString(key, "");
            if (config.crate(crateId) == null) {
                logger.warning("Skipping crate binding at '" + key + "' — unknown crate '"
                        + crateId + "'.");
                continue;
            }
            bindings.put(key.replace(',', ';'), crateId.toLowerCase(Locale.ROOT));
        }
    }

    private void persist() {
        final org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        for (final Map.Entry<String, String> entry : bindings.entrySet()) {
            yaml.set(entry.getKey().replace(';', ','), entry.getValue());
        }
        try {
            Files.createDirectories(bindingsFile.getParent());
            final Path temp = bindingsFile.resolveSibling(bindingsFile.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temp, bindingsFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            logger.severe("Could not save crate bindings: " + exception.getMessage());
        }
    }

    private static String locationKey(final String world, final int x, final int y, final int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    /** Binds a block as a crate. */
    public void bind(final String world, final int x, final int y, final int z,
                     final CrateDef crate) {
        bindings.put(locationKey(world, x, y, z), crate.id());
        persist();
    }

    /** Unbinds a crate block; true when one was bound. */
    public boolean unbind(final String world, final int x, final int y, final int z) {
        final boolean removed = bindings.remove(locationKey(world, x, y, z)) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    /** The crate bound at a block (null when none). */
    public CrateDef crateAt(final Block block) {
        if (block == null) {
            return null;
        }
        final String crateId = bindings.get(locationKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        return crateId == null ? null : config.crate(crateId);
    }

    /** How many blocks are bound (admin listing). */
    public Map<String, String> bindings() {
        return Map.copyOf(bindings);
    }

    // ------------------------------------------------------------------
    // opening
    // ------------------------------------------------------------------

    /**
     * Opens a crate with the key held in the main hand. The item must
     * carry the matching PDC key tag — names are never trusted.
     *
     * @return true when a reward was rolled and the key consumed
     */
    public boolean open(final Player player, final CrateDef crate, final ItemStack held,
                        final Block block) {
        final UUID playerId = player.getUniqueId();
        final long now = System.currentTimeMillis();
        final Long last = lastOpen.get(playerId);
        if (last != null && now - last < COOLDOWN_MS) {
            return false; // double-click / spam guard, silently swallowed
        }

        final String heldKeyId = keyItems.keyId(held);
        if (heldKeyId == null || !heldKeyId.equals(crate.keyId())) {
            final KeyDef needed = config.key(crate.keyId());
            messages.sendPrefixed(player, "store.crate-needs-key", Map.of(
                    "crate", plain(crate.name()),
                    "key", needed == null ? crate.keyId() : plain(needed.name())));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
            return false;
        }
        lastOpen.put(playerId, now);

        // 1. consume exactly one key BEFORE anything is rolled or granted
        //    (setAmount(0) empties the live stack in the hand slot)
        held.setAmount(held.getAmount() - 1);

        // 2. roll and immediately persist the win as a pending transaction —
        //    from this instant the reward can no longer be lost
        final com.coremc.core.reward.RewardDef rolled = crate.rewards().pick(random.nextDouble());
        final RewardGrant grant = RewardGrant.of(rolled, rolled.rollAmount(random.nextDouble()));
        final String txnId = UUID.randomUUID().toString();
        pending.add(playerId, txnId, "crate:" + crate.id(), java.util.List.of(grant));
        transactions.record(txnId, playerId, "CRATE_OPEN",
                "crate=" + crate.id() + " reward=" + grant.serialize());

        // 3. deliver (whatever cannot be delivered right now stays pending)
        pending.deliver(playerId, owed -> deliverer.deliver(player, owed));
        if (pending.has(playerId, txnId)) {
            messages.sendPrefixed(player, "store.delivery-pending");
        }

        // 4. effects + reveal
        messages.sendPrefixed(player, "store.crate-opened", Map.of(
                "crate", plain(crate.name()),
                "reward", RewardDeliverer.describe(grant)));
        playSound(player, crate.winSound());
        spawnParticles(block, crate.particle());
        if (crate.broadcastRare() && rolled.rare()) {
            Bukkit.broadcastMessage(com.coremc.core.util.ColorUtil.colorize(
                    messages.get("store.crate-rare-broadcast", Map.of(
                            "player", player.getName(),
                            "crate", plain(crate.name()),
                            "reward", RewardDeliverer.describe(grant)))));
        }
        return true;
    }

    private void playSound(final Player player, final String soundName) {
        try {
            player.playSound(player.getLocation(), Sound.valueOf(soundName), 0.9f, 1.1f);
        } catch (final IllegalArgumentException exception) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.1f);
        }
    }

    private void spawnParticles(final Block block, final String particleName) {
        if (block == null) {
            return;
        }
        Particle particle;
        try {
            particle = Particle.valueOf(particleName);
        } catch (final IllegalArgumentException exception) {
            particle = Particle.HAPPY_VILLAGER;
        }
        final Location center = block.getLocation().add(0.5, 1.2, 0.5);
        block.getWorld().spawnParticle(particle, center, 30, 0.4, 0.4, 0.4, 0.05);
    }

    private static String plain(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '&' && index + 1 < text.length()) {
                index++;
                continue;
            }
            out.append(text.charAt(index));
        }
        return out.toString();
    }
}
